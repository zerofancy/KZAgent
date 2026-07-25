package com.kzagent.kagent.desktop

import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.llm.AgentMessage
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
import kotlinx.coroutines.withContext

internal data class StoredSession(
    val id: String,
    val name: String,
    val workspace: Path,
    val sessionFile: Path,
    val history: List<AgentMessage> = emptyList(),
    val usedTokens: Int = 0,
)

internal interface SessionRepository {
    suspend fun loadAll(defaultWorkspace: Path): List<StoredSession>
    suspend fun create(workspace: Path, name: String): StoredSession
    suspend fun updateName(sessionFile: Path, name: String)
    suspend fun updateWorkspace(sessionFile: Path, workspace: Path)
    suspend fun delete(sessionFile: Path)
}

internal class FileSessionRepository(
    private val sessionsRoot: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository {
    override suspend fun loadAll(defaultWorkspace: Path): List<StoredSession> =
        withContext(ioDispatcher) {
            Files.createDirectories(sessionsRoot)
            if (!Files.isDirectory(sessionsRoot)) return@withContext emptyList()
            val files = Files.walk(sessionsRoot, 2).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".jsonl") }
                    .sorted(compareByDescending { Files.getLastModifiedTime(it).toMillis() })
                    .collect(Collectors.toList())
            }
            files.mapNotNull { file ->
                runCatching { load(defaultWorkspace, file) }.getOrNull()
            }
        }

    override suspend fun create(workspace: Path, name: String): StoredSession =
        withContext(ioDispatcher) {
            Files.createDirectories(sessionsRoot)
            val id = "session-${UUID.randomUUID()}"
            val file = sessionsRoot.resolve("$id.jsonl")
            Files.createFile(file)
            val stored = StoredSession(
                id = id,
                name = name,
                workspace = workspace.toAbsolutePath().normalize(),
                sessionFile = file,
            )
            writeMetadata(nameFile(file), stored.name)
            writeMetadata(workspaceFile(file), stored.workspace.toString())
            stored
        }

    override suspend fun updateName(sessionFile: Path, name: String) {
        withContext(ioDispatcher) {
            writeMetadata(nameFile(sessionFile), name)
        }
    }

    override suspend fun updateWorkspace(sessionFile: Path, workspace: Path) {
        withContext(ioDispatcher) {
            writeMetadata(workspaceFile(sessionFile), workspace.toAbsolutePath().normalize().toString())
        }
    }

    override suspend fun delete(sessionFile: Path) {
        withContext(ioDispatcher) {
            Files.deleteIfExists(sessionFile)
            Files.deleteIfExists(nameFile(sessionFile))
            Files.deleteIfExists(workspaceFile(sessionFile))
        }
    }

    private fun load(defaultWorkspace: Path, file: Path): StoredSession? {
        val workspace = readWorkspace(file)
            ?: defaultWorkspace.takeIf {
                file.parent.fileName.toString() == AppDataDir.workspaceKey(it)
            }
            ?: return null
        val reader = SessionReader(file.parent)
        val history = reader.loadFile(file).filter { it !is AgentMessage.System }
        val stored = StoredSession(
            id = file.fileName.toString().removeSuffix(".jsonl"),
            name = readName(file) ?: sessionDisplayName(file),
            workspace = workspace,
            sessionFile = file,
            history = history,
            usedTokens = reader.loadTokenCount(file),
        )
        // Older sessions located in workspace-specific directories did not have sidecar metadata.
        writeMetadata(workspaceFile(file), stored.workspace.toString())
        return stored
    }

    private fun readName(sessionFile: Path): String? = runCatching {
        Files.readString(nameFile(sessionFile), StandardCharsets.UTF_8)
            .trim()
            .takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun readWorkspace(sessionFile: Path): Path? = runCatching {
        Path.of(Files.readString(workspaceFile(sessionFile), StandardCharsets.UTF_8).trim())
            .toAbsolutePath()
            .normalize()
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

    private companion object {
        val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }
}
