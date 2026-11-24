package com.kzagent.kagent.desktop

import com.kzagent.kagent.config.AppDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

@Serializable
private data class ProjectExpandStateDocument(
    val schemaVersion: Int,
    val expandedGroups: Map<String, Boolean>,
)

private val projectExpandStateJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal suspend fun loadProjectExpandState(
    stateFile: Path = AppDataDir.appDir().resolve("session-project-collapses.json"),
): Map<String, Boolean> = withContext(Dispatchers.IO) {
    coroutineContext.ensureActive()
    readMetadataRecovering(stateFile, ::decodeProjectExpandState) ?: emptyMap()
}

internal suspend fun saveProjectExpandState(
    expandedGroups: Map<String, Boolean>,
    stateFile: Path = AppDataDir.appDir().resolve("session-project-collapses.json"),
) = withContext(Dispatchers.IO) {
    coroutineContext.ensureActive()
    expandedGroups.keys.forEach(::validateProjectId)
    readMetadataRecovering(stateFile, ::decodeProjectExpandState)
    coroutineContext.ensureActive()
    atomicWriteMetadata(
        stateFile,
        projectExpandStateJson.encodeToString(ProjectExpandStateDocument(1, expandedGroups)),
    )
}

private fun decodeProjectExpandState(content: String): Map<String, Boolean> {
    val document = projectExpandStateJson.decodeFromString<ProjectExpandStateDocument>(content)
    require(document.schemaVersion == 1) { "不支持此项目展开状态文件版本" }
    document.expandedGroups.keys.forEach(::validateProjectId)
    return document.expandedGroups
}
