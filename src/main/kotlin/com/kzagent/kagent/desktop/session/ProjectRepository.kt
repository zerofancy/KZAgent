package com.kzagent.kagent.desktop

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

public data class Project(
    val id: String,
    val name: String,
    val workspace: Path,
    val createdAt: Instant,
)

internal data class ProjectRegistry(
    val projects: List<Project> = emptyList(),
    val legacyDefaults: Map<String, String> = emptyMap(),
)

internal interface ProjectRepository {
    suspend fun load(): ProjectRegistry
    suspend fun save(registry: ProjectRegistry)
}

internal class FileProjectRepository(
    private val registryFile: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProjectRepository {
    override suspend fun load(): ProjectRegistry = withContext(ioDispatcher) {
        coroutineContext.ensureActive()
        readMetadataRecovering(registryFile, ::decodeProjectRegistry) ?: ProjectRegistry()
    }

    override suspend fun save(registry: ProjectRegistry) = withContext(ioDispatcher) {
        coroutineContext.ensureActive()
        val normalized = registry.copy(projects = registry.projects.map {
            it.copy(name = validateProjectName(it.name), workspace = it.workspace.toAbsolutePath().normalize())
        })
        validateProjectRegistry(normalized)
        // Re-read on every write: even a fresh repository must not overwrite corrupt/future data.
        readMetadataRecovering(registryFile, ::decodeProjectRegistry)
        coroutineContext.ensureActive()
        atomicWriteMetadata(registryFile, projectRegistryJson.encodeToString(ProjectRegistryDocument(
            schemaVersion = 1,
            projects = normalized.projects.map {
                ProjectDocument(it.id, it.name, it.workspace.toString(), it.createdAt.toString())
            },
            legacyDefaults = normalized.legacyDefaults,
        )))
    }
}

internal fun projectWorkspaceKey(workspace: Path): String {
    val normalized = workspace.toAbsolutePath().normalize().toString()
    return if (System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")) {
        normalized.lowercase(Locale.ROOT)
    } else {
        normalized
    }
}

internal fun validateProjectName(name: String): String {
    require(name.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
        "项目名称不能包含换行或控制字符"
    }
    return name.trim().also { require(it.isNotEmpty()) { "项目名称不能为空" } }
}

internal fun projectDefaultName(workspace: Path): String {
    val absolute = workspace.toAbsolutePath().normalize()
    return absolute.fileName?.toString() ?: absolute.toString()
}

internal fun validateProjectId(id: String) {
    val parsed = try {
        UUID.fromString(id)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("项目 ID 不是有效的 UUID")
    }
    // UUID.fromString accepts shortened groups, which are not valid persisted identities.
    require(parsed.toString().equals(id, ignoreCase = true)) { "项目 ID 不是有效的 UUID" }
}

@Serializable
private data class ProjectDocument(
    val id: String,
    val name: String,
    val workspace: String,
    val createdAt: String,
)

@Serializable
private data class ProjectRegistryDocument(
    val schemaVersion: Int,
    val projects: List<ProjectDocument>,
    val legacyDefaults: Map<String, String> = emptyMap(),
)

private val projectRegistryJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private fun decodeProjectRegistry(content: String): ProjectRegistry {
    val document = projectRegistryJson.decodeFromString<ProjectRegistryDocument>(content)
    require(document.schemaVersion == 1) { "不支持此项目注册表版本，请使用兼容版本的应用打开" }
    val registry = ProjectRegistry(
        projects = document.projects.map {
            Project(it.id, it.name, Path.of(it.workspace), Instant.parse(it.createdAt))
        },
        legacyDefaults = document.legacyDefaults,
    )
    validateProjectRegistry(registry)
    return registry
}

private fun validateProjectRegistry(registry: ProjectRegistry) {
    val projectsById = registry.projects.associateBy { it.id.lowercase(Locale.ROOT) }
    require(projectsById.size == registry.projects.size) { "项目注册表包含重复 ID" }
    registry.projects.forEach { project ->
        validateProjectId(project.id)
        require(validateProjectName(project.name) == project.name) { "项目名称不能包含首尾空白" }
        require(project.workspace.isAbsolute && project.workspace.normalize() == project.workspace) {
            "项目工作区必须为规范化的绝对路径"
        }
    }
    registry.legacyDefaults.forEach { (key, id) ->
        validateProjectId(id)
        val project = registry.projects.singleOrNull { it.id == id }
        requireNotNull(project) { "历史默认归属引用了不存在的项目" }
        require(projectWorkspaceKey(Path.of(key)) == key && projectWorkspaceKey(project.workspace) == key) {
            "历史默认归属的路径与项目不一致"
        }
    }
}
