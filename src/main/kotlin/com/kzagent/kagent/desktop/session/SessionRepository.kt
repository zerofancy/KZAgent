package com.kzagent.kagent.desktop

import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.agent.SessionEntry
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.todo.TodoFiles
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.stream.Collectors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class StoredSession(
    val id: String,
    val name: String,
    val workspace: Path,
    val sessionFile: Path,
    val history: List<AgentMessage> = emptyList(),
    val historyEntries: List<SessionEntry> = emptyList(),
    val usedTokens: Int = 0,
    val modelSelection: ModelSelection? = null,
    val projectId: String? = null,
)

internal interface SessionRepository {
    suspend fun loadAll(defaultWorkspace: Path): List<StoredSession>
    suspend fun loadAll(defaultWorkspace: Path, knownWorkspaces: List<Path>): List<StoredSession> = loadAll(defaultWorkspace)
    suspend fun create(workspace: Path, name: String, modelSelection: ModelSelection? = null): StoredSession
    suspend fun updateName(sessionFile: Path, name: String)
    suspend fun updateModel(sessionFile: Path, selection: ModelSelection)
    suspend fun delete(sessionFile: Path)
    val loadIssues: List<String> get() = emptyList()
    suspend fun updateProject(sessionFile: Path, projectId: String) = Unit
    suspend fun createInProject(workspace: Path, name: String, modelSelection: ModelSelection?, projectId: String): StoredSession {
        val stored = create(workspace, name, modelSelection)
        try {
            updateProject(stored.sessionFile, projectId)
            return stored.copy(projectId = projectId)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { delete(stored.sessionFile) }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
    }
}

internal class FileSessionRepository(
    private val sessionsRoot: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository {
    override var loadIssues: List<String> = emptyList()
        private set

    override suspend fun loadAll(defaultWorkspace: Path): List<StoredSession> = loadAll(defaultWorkspace, emptyList())

    override suspend fun loadAll(defaultWorkspace: Path, knownWorkspaces: List<Path>): List<StoredSession> =
        withContext(ioDispatcher) {
            Files.createDirectories(sessionsRoot)
            if (!Files.isDirectory(sessionsRoot)) return@withContext emptyList()
            val files = Files.walk(sessionsRoot, 2).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".jsonl") }
                    .sorted(compareByDescending { Files.getLastModifiedTime(it).toMillis() })
                    .collect(Collectors.toList())
            }
            val issues = mutableListOf<String>()
            val result = files.mapNotNull { file ->
                try {
                    load((listOf(defaultWorkspace) + knownWorkspaces).distinct(), file)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    issues += "${file.fileName}：${error.message ?: "会话读取失败"}"
                    null
                }
            }
            loadIssues = issues
            result
        }

    override suspend fun create(workspace: Path, name: String, modelSelection: ModelSelection?): StoredSession =
        createStored(workspace, name, modelSelection, null)

    override suspend fun createInProject(workspace: Path, name: String, modelSelection: ModelSelection?, projectId: String): StoredSession =
        createStored(workspace, name, modelSelection, projectId)

    private suspend fun createStored(workspace: Path, name: String, modelSelection: ModelSelection?, projectId: String?): StoredSession {
        var createdFile: Path? = null
        try {
            return withContext(ioDispatcher) {
                Files.createDirectories(sessionsRoot)
                val id = "session-${UUID.randomUUID()}"
                val file = sessionsRoot.resolve("$id.jsonl")
                val stored = StoredSession(
                    id = id,
                    name = name,
                    workspace = workspace.toAbsolutePath().normalize(),
                    sessionFile = file,
                    modelSelection = modelSelection,
                    projectId = projectId,
                )
                Files.createFile(file)
                createdFile = file
                writeMetadata(nameFile(file), stored.name)
                writeMetadata(workspaceFile(file), stored.workspace.toString())
                modelSelection?.let { writeModel(modelFile(file), it) }
                projectId?.let { writeMetadata(projectFile(file), it) }
                stored
            }
        } catch (failure: Throwable) {
            // Cancellation can arrive when IO returns to the caller, after all files
            // were written. Keep rollback outside withContext to cover that boundary.
            withContext(NonCancellable + ioDispatcher) {
                createdFile?.let { file ->
                    listOf(file, nameFile(file), workspaceFile(file), modelFile(file), projectFile(file)).forEach { path ->
                        runCatching { Files.deleteIfExists(path) }.exceptionOrNull()?.let(failure::addSuppressed)
                    }
                }
            }
            throw failure
        }
    }

    override suspend fun updateName(sessionFile: Path, name: String) {
        withContext(ioDispatcher) {
            writeMetadata(nameFile(sessionFile), name)
        }
    }

    override suspend fun updateModel(sessionFile: Path, selection: ModelSelection) {
        withContext(ioDispatcher) { writeModel(modelFile(sessionFile), selection) }
    }

    override suspend fun updateProject(sessionFile: Path, projectId: String) {
        withContext(ioDispatcher) { atomicWriteMetadata(projectFile(sessionFile), projectId) }
    }

    override suspend fun delete(sessionFile: Path) {
        withContext(ioDispatcher) {
            Files.deleteIfExists(sessionFile)
            Files.deleteIfExists(nameFile(sessionFile))
            Files.deleteIfExists(workspaceFile(sessionFile))
            Files.deleteIfExists(modelFile(sessionFile))
            Files.deleteIfExists(projectFile(sessionFile))
            Files.deleteIfExists(TodoFiles.forSession(sessionFile))
        }
    }

    private fun load(knownWorkspaces: List<Path>, file: Path): StoredSession {
        val workspace = if (!Files.notExists(workspaceFile(file))) {
            requireNotNull(readWorkspace(file)) { "工作区元数据损坏" }
        } else {
            knownWorkspaces.firstOrNull { file.parent.fileName.toString() == AppDataDir.workspaceKey(it) }
                ?.toAbsolutePath()?.normalize() ?: error("无法恢复工作区路径")
        }
        val reader = SessionReader(file.parent)
        val historyEntries = reader.loadEntries(file).filter { it.message !is AgentMessage.System }
        val history = historyEntries.map { it.message }
        val stored = StoredSession(
            id = file.fileName.toString().removeSuffix(".jsonl"),
            name = readName(file) ?: sessionDisplayName(file),
            workspace = workspace,
            sessionFile = file,
            history = history,
            historyEntries = historyEntries,
            usedTokens = reader.loadTokenCount(file),
            modelSelection = readModel(file),
            // Empty/invalid explicit references must not be treated as unassigned legacy data.
            projectId = if (!Files.notExists(projectFile(file))) Files.readString(projectFile(file), StandardCharsets.UTF_8).trim() else null,
        )
        // Older sessions located in workspace-specific directories did not have sidecar metadata.
        if (Files.notExists(workspaceFile(file))) atomicWriteMetadata(workspaceFile(file), stored.workspace.toString())
        return stored
    }

    private fun readName(sessionFile: Path): String? = runCatching {
        Files.readString(nameFile(sessionFile), StandardCharsets.UTF_8)
            .trim()
            .takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun readWorkspace(sessionFile: Path): Path? = runCatching {
        val raw = Files.readString(workspaceFile(sessionFile), StandardCharsets.UTF_8).trim()
        require(raw.isNotEmpty()) { "工作区路径为空" }
        Path.of(raw).also { require(it.isAbsolute) { "工作区路径必须为绝对路径" } }.normalize()
    }.getOrNull()

    private fun writeMetadata(path: Path, value: String) {
        Files.writeString(
            path,
            value,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun readModel(sessionFile: Path): ModelSelection? = runCatching {
        val values = Files.readAllLines(modelFile(sessionFile), StandardCharsets.UTF_8)
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap()
        ModelSelection(
            provider = requireNotNull(values["provider"]) { "Missing provider in model sidecar." },
            modelId = values.getValue("model"),
            contextWindowSize = values["contextWindowSize"]?.toIntOrNull(),
            supportsToolChoice = values["supportsToolChoice"]?.toBooleanStrictOrNull() ?: true,
        )
    }.getOrNull()

    private fun writeModel(path: Path, selection: ModelSelection) {
        writeMetadata(
            path,
            buildString {
                appendLine("provider=${selection.provider}")
                appendLine("model=${selection.modelId}")
                selection.contextWindowSize?.let { appendLine("contextWindowSize=$it") }
                appendLine("supportsToolChoice=${selection.supportsToolChoice}")
            },
        )
    }

    private fun sessionDisplayName(file: Path): String {
        val raw = file.fileName.toString().removeSuffix(".jsonl")
        val name = raw.removePrefix("session-")
        return runCatching {
            val instant = Instant.parse(
                name.replaceFirst(
                    Regex("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(\\d{2,3})Z"),
                    "$1-$2-$3T$4:$5:$6.$7Z",
                ),
            )
            DISPLAY_DATE_FORMATTER.format(instant)
        }.getOrDefault(raw.take(19))
    }

    private fun nameFile(sessionFile: Path): Path =
        sessionFile.resolveSibling("${sessionFile.fileName}.name")

    private fun workspaceFile(sessionFile: Path): Path =
        sessionFile.resolveSibling("${sessionFile.fileName}.workspace")

    private fun projectFile(sessionFile: Path): Path =
        sessionFile.resolveSibling("${sessionFile.fileName}.project")

    private fun modelFile(sessionFile: Path): Path =
        sessionFile.resolveSibling("${sessionFile.fileName}.model")

    private companion object {
        val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }
}
