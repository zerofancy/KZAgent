package com.kzagent.kagent.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.kzagent.kagent.AgentRuntime
import com.kzagent.kagent.AgentRuntimeFactory
import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.ApprovalPolicy
import kotlinx.coroutines.Job
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.stream.Collectors

class SessionData(
    val id: String,
    name: String,
    val workspace: Path,
    val sessionFile: Path,
    runtime: AgentRuntime? = null,
    conversationHistory: List<AgentMessage> = emptyList(),
    val messages: SnapshotStateList<DisplayMessage>,
    usedTokens: Int = 0,
    isBusy: Boolean = false,
    currentJob: Job? = null,
    status: String = "正在加载...",
    error: String? = null,
) {
    var name by mutableStateOf(name)
    var runtime by mutableStateOf(runtime)
    var conversationHistory by mutableStateOf(conversationHistory)
    var usedTokens by mutableStateOf(usedTokens)
    var isBusy by mutableStateOf(isBusy)
    var currentJob by mutableStateOf(currentJob)
    var status by mutableStateOf(status)
    var error by mutableStateOf(error)
}

class SessionManager(private val approvalPolicy: ApprovalPolicy) {
    val sessions: SnapshotStateList<SessionData> = mutableStateListOf()
    var activeSessionIndex by mutableStateOf(0)

    /** Load all existing sessions from disk, or create a default one. */
    fun loadOrCreate(workspace: Path) {
        val sessionsDir = workspace.resolve(".kagent").resolve("sessions")
        if (Files.isDirectory(sessionsDir)) {
            val sessionFiles = Files.list(sessionsDir).use { stream ->
                stream
                    .filter { it.toString().endsWith(".jsonl") }
                    .sorted(compareByDescending { Files.getLastModifiedTime(it).toMillis() })
                    .collect(Collectors.toList())
            }
            if (sessionFiles.isNotEmpty()) {
                sessionFiles.forEach { file ->
                    runCatching { createSessionFromFile(workspace, file) }
                        .onSuccess(sessions::add)
                }
                if (sessions.isNotEmpty()) {
                    // Activate the most recent readable session (first in reverse order)
                    activeSessionIndex = 0
                    return
                }
            }
        }
        // No existing sessions — create default
        val newSession = createNewSession(workspace)
        sessions.add(newSession)
        activeSessionIndex = 0
    }

    private fun createSessionFromFile(workspace: Path, file: Path): SessionData {
        val history = SessionReader(workspace).loadFile(file)
            .filter { it !is AgentMessage.System }
        val tokens = loadTokenCount(file)
        val displayMsgs = history.toDisplayMessages()
        val id = file.fileName.toString().removeSuffix(".jsonl")
        val name = loadSessionName(file) ?: sessionDisplayName(file)
        return SessionData(
            id = id,
            name = name,
            workspace = workspace,
            sessionFile = file,
            conversationHistory = history,
            messages = mutableStateListOf<DisplayMessage>().also { it.addAll(displayMsgs) },
            usedTokens = tokens,
            status = "就绪",
        )
    }

    fun createNewSession(workspace: Path): SessionData {
        val dir = workspace.resolve(".kagent").resolve("sessions")
        Files.createDirectories(dir)
        val id = "session-${UUID.randomUUID()}"
        val file = dir.resolve("$id.jsonl")
        Files.createFile(file)
        val session = SessionData(
            id = id,
            name = "新会话 ${sessions.size + 1}",
            workspace = workspace,
            sessionFile = file,
            messages = mutableStateListOf(),
            status = "就绪",
        )
        persistSessionName(session)
        return session
    }

    fun addNewSession(workspace: Path) {
        val session = createNewSession(workspace)
        sessions.add(0, session)
        activeSessionIndex = 0
    }

    fun switchTo(index: Int) {
        if (index in sessions.indices) {
            activeSessionIndex = index
        }
    }

    fun renameSession(index: Int, name: String): Boolean {
        if (index !in sessions.indices || name.isBlank()) return false
        sessions[index].name = name.trim()
        persistSessionName(sessions[index])
        return true
    }

    fun cancelAllSessions() {
        sessions.forEach { it.currentJob?.cancel() }
    }

    fun deleteSession(index: Int): Boolean {
        if (sessions.size <= 1) return false
        if (index !in sessions.indices) return false

        val session = sessions[index]
        // Cancel any running job
        session.currentJob?.cancel()

        // Remove session file from disk
        runCatching { Files.deleteIfExists(session.sessionFile) }
        runCatching { Files.deleteIfExists(nameFile(session.sessionFile)) }

        sessions.removeAt(index)
        if (activeSessionIndex >= sessions.size) {
            activeSessionIndex = sessions.size - 1
        } else if (activeSessionIndex > index) {
            activeSessionIndex--
        }
        return true
    }

    fun activeSession(): SessionData = sessions[activeSessionIndex]

    fun ensureRuntime(session: SessionData, observer: AgentObserver) {
        if (session.runtime == null) {
            session.runtime = AgentRuntimeFactory.create(
                workspace = session.workspace,
                approvalPolicy = approvalPolicy,
                observer = observer,
                sessionFile = session.sessionFile,
            )
        }
    }

    companion object {
        private val displayDateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())

        private fun nameFile(sessionFile: Path): Path =
            sessionFile.resolveSibling("${sessionFile.fileName}.name")

        private fun loadSessionName(sessionFile: Path): String? = runCatching {
            Files.readString(nameFile(sessionFile), java.nio.charset.StandardCharsets.UTF_8)
                .trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()

        private fun persistSessionName(session: SessionData) {
            Files.writeString(
                nameFile(session.sessionFile),
                session.name,
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }

        private fun sessionDisplayName(file: Path): String {
            val raw = file.fileName.toString().removeSuffix(".jsonl")
            // Try to extract a readable timestamp from session-YYYYMMDDTHHMMSSZ style
            val name = raw.removePrefix("session-")
            return runCatching {
                val instant = Instant.parse(
                    name.replaceFirst(
                        Regex("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(\\d{2,3})Z"),
                        "$1-$2-$3T$4:$5:$6.$7Z"
                    )
                )
                displayDateFormatter.format(instant)
            }.getOrDefault(raw.take(19))
        }

        private fun loadTokenCount(file: Path): Int {
            if (!Files.isRegularFile(file)) return 0
            val lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            for (i in lines.indices.reversed()) {
                val line = lines[i].trim()
                if (line.isBlank()) continue
                try {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val role = obj["role"]?.jsonPrimitive?.content.orEmpty()
                    if (role != "assistant" && role != "context_snapshot") continue
                    val tokens = obj["context_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: obj["cumulative_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: 0
                    if (tokens > 0) return tokens
                } catch (_: Exception) { continue }
            }
            return 0
        }
    }
}
