package com.kzagent.kagent.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.kzagent.kagent.AgentRuntime
import com.kzagent.kagent.AgentRuntimeFactory
import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.UserQuestionPrompter
import com.kzagent.kagent.todo.TodoSnapshot
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

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
    todoSnapshot: TodoSnapshot = TodoSnapshot(),
    modelSelection: ModelSelection = ModelSelection(
        AppConfig.DEFAULT_PROVIDER_ID,
        AppConfig.DEFAULT_MODEL,
        AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
    ),
) {
    var name by mutableStateOf(name)
    var titleRevision: Int = 0
        private set
    var runtime by mutableStateOf(runtime)
    internal val runtimeMutex = Mutex()
    internal var skillsVersion = -1L
    internal var titleJob: Job? = null
    var conversationHistory by mutableStateOf(conversationHistory)
    var usedTokens by mutableStateOf(usedTokens)
    var isBusy by mutableStateOf(isBusy)
    var currentJob by mutableStateOf(currentJob)
    var status by mutableStateOf(status)
    var error by mutableStateOf(error)
    var todoSnapshot by mutableStateOf(todoSnapshot)
    var modelSelection by mutableStateOf(modelSelection)

    suspend fun reloadSavedHistory() {
        val saved = withContext(Dispatchers.IO) {
            val reader = SessionReader(sessionFile.parent)
            reader.loadFile(sessionFile).filter { it !is AgentMessage.System } to
                reader.loadTokenCount(sessionFile)
        }
        conversationHistory = saved.first
        usedTokens = saved.second
    }

    fun updateName(name: String) {
        this.name = name
        titleRevision++
    }

}

class SessionManager internal constructor(
    private val approvalPolicy: ApprovalPolicy,
    sessionsRoot: Path = AppDataDir.sessionsRoot(),
    private val repository: SessionRepository = FileSessionRepository(sessionsRoot),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialDefaultModel: ModelSelection = ModelSelection(
        AppConfig.DEFAULT_PROVIDER_ID,
        AppConfig.DEFAULT_MODEL,
        AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
    ),
    private val userQuestionPrompter: UserQuestionPrompter = UserQuestionPrompter { questions ->
        questions.map { com.kzagent.kagent.tools.UserQuestionAnswer(null) }
    },
    private val createRuntime: ((SessionData, AgentObserver) -> AgentRuntime)? = null,
) : AutoCloseable {
    val sessions: SnapshotStateList<SessionData> = mutableStateListOf()
    var activeSessionIndex by mutableStateOf(-1)
        private set
    var initialized by mutableStateOf(false)
        private set
    private val renameMutex = Mutex()
    private var defaultModel: ModelSelection = initialDefaultModel
    private var skillsVersion = 0L
    private var closed = false

    fun markSkillsChanged() { skillsVersion++ }

    fun updateDefaultModel(selection: ModelSelection) {
        defaultModel = selection
    }

    /** Loads history without creating an empty session. */
    suspend fun loadSessions(defaultWorkspace: Path) {
        if (initialized) return
        val stored = repository.loadAll(defaultWorkspace)
        sessions.clear()
        sessions.addAll(stored.map { storedSession ->
            if (storedSession.modelSelection == null) {
                repository.updateModel(storedSession.sessionFile, defaultModel)
            }
            toSessionData(storedSession, storedSession.modelSelection ?: defaultModel)
        })
        activeSessionIndex = if (sessions.isEmpty()) -1 else 0
        initialized = true
    }

    suspend fun addNewSession() {
        val active = activeSession()
        val stored = repository.create(active.workspace, "新会话 ${sessions.size + 1}", defaultModel)
        sessions.add(0, toSessionData(stored, defaultModel))
        activeSessionIndex = 0
    }

    /** Creates and activates a fresh session even when [workspace] is already active. */
    suspend fun startNewSessionInWorkspace(workspace: Path, selection: ModelSelection = defaultModel): SessionData {
        val normalized = workspace.toAbsolutePath().normalize()
        val stored = repository.create(normalized, "新会话 ${sessions.size + 1}", selection)
        val created = toSessionData(stored, selection)
        sessions.add(0, created)
        activeSessionIndex = 0
        return created
    }

    /**
     * Starts a fresh session for a different workspace.
     *
     * A session's workspace is immutable because its history may contain source
     * code, tool output, and scoped AGENTS.md instructions from that workspace.
     * Rebinding the same session would send that old project context to the new
     * runtime. Keeping the original session also avoids destructive history loss.
     */
    suspend fun startSessionInWorkspace(session: SessionData, workspace: Path): SessionData {
        val normalized = workspace.toAbsolutePath().normalize()
        if (normalized == session.workspace) return session

        return startNewSessionInWorkspace(normalized)
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
            session.titleJob?.cancel()
            session.currentJob = null
            session.isBusy = false
            session.runtime?.close()
            session.runtime = null
            session.error = null
            session.status = "正在加载..."
        }
    }

    suspend fun updateModel(session: SessionData, selection: ModelSelection) {
        check(!session.isBusy) { "Cannot switch models while the session is busy." }
        repository.updateModel(session.sessionFile, selection)
        session.titleJob?.cancelAndJoin()
        session.runtime?.close()
        session.runtime = null
        session.modelSelection = selection
        session.error = null
        session.status = "正在切换模型..."
    }

    suspend fun deleteSession(index: Int): Boolean {
        if (index !in sessions.indices) return false
        val session = sessions[index]
        session.currentJob?.cancelAndJoin()
        session.titleJob?.cancelAndJoin()
        repository.delete(session.sessionFile)
        session.runtime?.close()
        sessions.removeAt(index)
        if (activeSessionIndex >= sessions.size) {
            activeSessionIndex = sessions.size - 1
        } else if (activeSessionIndex > index) {
            activeSessionIndex--
        }
        return true
    }

    fun activeSessionOrNull(): SessionData? = sessions.getOrNull(activeSessionIndex)

    fun activeSession(): SessionData = requireNotNull(activeSessionOrNull()) { "没有活动会话" }

    override fun close() {
        closed = true
        sessions.forEach { session ->
            session.currentJob?.cancel()
            session.titleJob?.cancel()
            session.runtime?.close()
            session.runtime = null
        }
    }

    suspend fun ensureRuntime(session: SessionData, observer: AgentObserver, refreshSkills: Boolean = false) = session.runtimeMutex.withLock {
        check(!closed) { "会话管理器已关闭" }
        if (session.runtime != null && (!refreshSkills || session.skillsVersion == skillsVersion)) return@withLock
        session.titleJob?.cancelAndJoin()
        session.runtime?.close()
        session.runtime = null
        val version = skillsVersion
        // Retain ownership across dispatcher cancellation so a newly created client is always closed.
        var created: AgentRuntime? = null
        try {
            withContext(ioDispatcher + NonCancellable) {
                created = createRuntime?.invoke(session, observer) ?: AgentRuntimeFactory.create(
                    workspace = session.workspace,
                    approvalPolicy = approvalPolicy,
                    userQuestionPrompter = userQuestionPrompter,
                    observer = observer,
                    sessionFile = session.sessionFile,
                    modelSelection = session.modelSelection,
                )
            }
            coroutineContext.ensureActive()
            check(!closed && session in sessions) { "会话已关闭" }
            val runtime = requireNotNull(created)
            session.runtime = runtime
            session.skillsVersion = version
            session.todoSnapshot = runtime.todoState.value
        } catch (error: Throwable) {
            created?.close()
            throw error
        }
    }

    private fun toSessionData(stored: StoredSession, modelSelection: ModelSelection): SessionData = SessionData(
        id = stored.id,
        name = stored.name,
        workspace = stored.workspace,
        sessionFile = stored.sessionFile,
        conversationHistory = stored.history,
        messages = mutableStateListOf<DisplayMessage>().also {
            it.addAll(stored.historyEntries.toDisplayMessagesWithTimestamps())
        },
        usedTokens = stored.usedTokens,
        status = "就绪",
        modelSelection = modelSelection,
    )
}
