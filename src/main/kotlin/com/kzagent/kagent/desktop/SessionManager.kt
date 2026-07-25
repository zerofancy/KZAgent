package com.kzagent.kagent.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.kzagent.kagent.AgentRuntime
import com.kzagent.kagent.AgentRuntimeFactory
import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.ApprovalPolicy
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionData(
    val id: String,
    name: String,
    workspace: Path,
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
    var workspace by mutableStateOf(workspace)
        private set
    var titleRevision: Int = 0
        private set
    var runtime by mutableStateOf(runtime)
    var conversationHistory by mutableStateOf(conversationHistory)
    var usedTokens by mutableStateOf(usedTokens)
    var isBusy by mutableStateOf(isBusy)
    var currentJob by mutableStateOf(currentJob)
    var status by mutableStateOf(status)
    var error by mutableStateOf(error)

    fun updateName(name: String) {
        this.name = name
        titleRevision++
    }

    fun updateWorkspace(workspace: Path) {
        this.workspace = workspace.toAbsolutePath().normalize()
    }
}

class SessionManager internal constructor(
    private val approvalPolicy: ApprovalPolicy,
    sessionsRoot: Path = AppDataDir.sessionsRoot(),
    private val repository: SessionRepository = FileSessionRepository(sessionsRoot),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val sessions: SnapshotStateList<SessionData> = mutableStateListOf()
    var activeSessionIndex by mutableStateOf(0)
        private set
    var initialized by mutableStateOf(false)
        private set
    private val renameMutex = Mutex()

    /** Loads sessions once. Recomposition and configuration refreshes reuse this manager instance. */
    suspend fun loadOrCreate(defaultWorkspace: Path) {
        if (initialized) return
        val stored = repository.loadAll(defaultWorkspace).ifEmpty {
            listOf(repository.create(defaultWorkspace, "新会话 1"))
        }
        sessions.clear()
        sessions.addAll(stored.map(::toSessionData))
        activeSessionIndex = 0
        initialized = true
    }

    suspend fun addNewSession() {
        val active = activeSession()
        val stored = repository.create(active.workspace, "新会话 ${sessions.size + 1}")
        sessions.add(0, toSessionData(stored))
        activeSessionIndex = 0
    }

    suspend fun changeWorkspace(session: SessionData, workspace: Path) {
        val normalized = workspace.toAbsolutePath().normalize()
        session.currentJob?.cancel()
        repository.updateWorkspace(session.sessionFile, normalized)
        session.currentJob = null
        session.isBusy = false
        session.runtime = null
        session.error = null
        session.updateWorkspace(normalized)
        session.status = "正在加载..."
    }

    fun switchTo(index: Int) {
        if (index in sessions.indices) {
            activeSessionIndex = index
        }
    }

    suspend fun renameSession(index: Int, name: String): Boolean = renameMutex.withLock {
        if (index !in sessions.indices || name.isBlank()) return@withLock false
        val session = sessions[index]
        val normalized = name.trim()
        repository.updateName(session.sessionFile, normalized)
        session.updateName(normalized)
        true
    }

    suspend fun renameSessionIfRevisionMatches(
        sessionId: String,
        expectedRevision: Int,
        name: String,
    ): Boolean = renameMutex.withLock {
        if (name.isBlank()) return@withLock false
        val session = sessions.firstOrNull { it.id == sessionId } ?: return@withLock false
        if (session.titleRevision != expectedRevision) return@withLock false
        val normalized = name.trim()
        repository.updateName(session.sessionFile, normalized)
        session.updateName(normalized)
        true
    }

    fun cancelAllSessions() {
        sessions.forEach { it.currentJob?.cancel() }
    }

    fun invalidateRuntimes() {
        sessions.forEach { session ->
            session.currentJob?.cancel()
            session.currentJob = null
            session.isBusy = false
            session.runtime = null
            session.error = null
            session.status = "正在加载..."
        }
    }

    suspend fun deleteSession(index: Int): Boolean {
        if (sessions.size <= 1 || index !in sessions.indices) return false
        val session = sessions[index]
        session.currentJob?.cancel()
        repository.delete(session.sessionFile)
        sessions.removeAt(index)
        if (activeSessionIndex >= sessions.size) {
            activeSessionIndex = sessions.size - 1
        } else if (activeSessionIndex > index) {
            activeSessionIndex--
        }
        return true
    }

    fun activeSession(): SessionData = sessions[activeSessionIndex]

    suspend fun ensureRuntime(session: SessionData, observer: AgentObserver) {
        if (session.runtime != null) return
        val runtime = withContext(ioDispatcher) {
            AgentRuntimeFactory.create(
                workspace = session.workspace,
                approvalPolicy = approvalPolicy,
                observer = observer,
                sessionFile = session.sessionFile,
            )
        }
        session.runtime = runtime
    }

    private fun toSessionData(stored: StoredSession): SessionData = SessionData(
        id = stored.id,
        name = stored.name,
        workspace = stored.workspace,
        sessionFile = stored.sessionFile,
        conversationHistory = stored.history,
        messages = mutableStateListOf<DisplayMessage>().also {
            it.addAll(stored.history.toDisplayMessages())
        },
        usedTokens = stored.usedTokens,
        status = "就绪",
    )
}
