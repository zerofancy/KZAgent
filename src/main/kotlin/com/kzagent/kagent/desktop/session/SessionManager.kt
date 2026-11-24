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
    val projectId: String? = null,
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
    private val projectRepository: ProjectRepository = FileProjectRepository(
        sessionsRoot.resolveSibling(if (sessionsRoot == AppDataDir.sessionsRoot()) "projects.json" else "${sessionsRoot.fileName}.projects.json"),
    ),
    private val projectExpandFile: Path = sessionsRoot.resolveSibling(
        if (sessionsRoot == AppDataDir.sessionsRoot()) "session-project-collapses.json" else "${sessionsRoot.fileName}.project-collapses.json",
    ),
    private val legacyExpandFile: Path = sessionsRoot.resolveSibling("session-group-collapses.json"),
) : AutoCloseable {
    val sessions: SnapshotStateList<SessionData> = mutableStateListOf()
    val projects: SnapshotStateList<Project> = mutableStateListOf()
    val loadIssues: SnapshotStateList<String> = mutableStateListOf()
    var projectExpanded by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set
    private var registry = ProjectRegistry()
    // Project deletion, migration and session creation share ownership of references.
    private val projectMutex = Mutex()
    private var lastDefaultWorkspace: Path? = null
    var projectDataAvailable by mutableStateOf(false)
        private set
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
    suspend fun loadSessions(defaultWorkspace: Path) = projectMutex.withLock {
        if (initialized) return@withLock
        lastDefaultWorkspace = defaultWorkspace
        loadProjectSessions(defaultWorkspace)
    }

    private suspend fun loadProjectSessions(defaultWorkspace: Path) {
        projectDataAvailable = false
        registry = projectRepository.load()
        val stored = repository.loadAll(defaultWorkspace, registry.projects.map { it.workspace })
        val issues = repository.loadIssues.toMutableList()
        val loaded = mutableListOf<SessionData>()
        val oldByFile = sessions.associateBy { it.sessionFile }
        for (original in stored) {
            try {
                val project = if (original.projectId == null) legacyProject(original.workspace) else
                    registry.projects.firstOrNull { it.id == original.projectId }
                        ?: error("项目引用无效：${original.projectId}")
                check(projectWorkspaceKey(project.workspace) == projectWorkspaceKey(original.workspace)) { "项目与会话工作区不一致" }
                if (original.projectId == null) repository.updateProject(original.sessionFile, project.id)
                if (original.modelSelection == null) repository.updateModel(original.sessionFile, defaultModel)
                val existing = oldByFile[original.sessionFile]?.takeIf {
                    it.projectId == project.id && projectWorkspaceKey(it.workspace) == projectWorkspaceKey(original.workspace)
                }
                loaded += existing ?: toSessionData(original.copy(projectId = project.id), original.modelSelection ?: defaultModel)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                issues += "${original.sessionFile.fileName}：${error.message ?: "项目归属加载失败"}"
            }
        }
        val expanded = try {
            val previous = loadProjectExpandState(projectExpandFile)
            val current = previous.toMutableMap()
            val legacy = loadSessionWorkspaceExpandState(legacyExpandFile)
            registry.legacyDefaults.values.forEach { id ->
                val project = registry.projects.first { it.id == id }
                if (id !in current) current[id] = legacy[sessionWorkspaceKey(project.workspace)] != false
            }
            if (current != previous) {
                try { saveProjectExpandState(current, projectExpandFile) }
                catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    issues += "项目展开状态保存失败：${error.message}"
                    current.clear()
                    current.putAll(previous)
                }
            }
            current.toMap()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            issues += "项目展开状态：${error.message}"
            emptyMap()
        }
        val activeId = activeSessionOrNull()?.id
        sessions.filter { it !in loaded }.forEach {
            it.currentJob?.cancelAndJoin()
            it.titleJob?.cancelAndJoin()
            it.runtime?.close()
            it.runtime = null
        }
        sessions.clear()
        sessions.addAll(loaded)
        projects.clear()
        projects.addAll(registry.projects)
        projectExpanded = expanded
        loadIssues.clear()
        loadIssues.addAll(issues)
        activeSessionIndex = sessions.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: if (sessions.isEmpty()) -1 else 0
        projectDataAvailable = true
        initialized = true
    }

    /** Retry only while idle: preserve existing SessionData/runtime identities. */
    suspend fun retryLoadIssues() = projectMutex.withLock {
        check(sessions.none { it.isBusy }) { "请等待运行中的任务结束后重试" }
        loadProjectSessions(requireNotNull(lastDefaultWorkspace))
    }

    private suspend fun persistRegistry(next: ProjectRegistry) = withContext(NonCancellable) {
        projectRepository.save(next)
        registry = next
        projects.clear()
        projects.addAll(next.projects)
    }

    private suspend fun legacyProject(workspace: Path): Project {
        val key = projectWorkspaceKey(workspace)
        registry.legacyDefaults[key]?.let { id -> return registry.projects.first { it.id == id } }
        val project = Project(java.util.UUID.randomUUID().toString(), projectDefaultName(workspace), workspace.toAbsolutePath().normalize(), java.time.Instant.now())
        // Publish the mapping before sidecars, so interrupted migration is idempotent.
        persistRegistry(registry.copy(projects = registry.projects + project, legacyDefaults = registry.legacyDefaults + (key to project.id)))
        return project
    }

    fun projectById(id: String?): Project? = projects.firstOrNull { it.id == id }

    suspend fun createProject(name: String, workspace: Path): Project = projectMutex.withLock {
        check(initialized && projectDataAvailable && !closed) { "项目尚未成功加载或管理器已关闭" }
        val normalized = withContext(ioDispatcher) { readableProjectWorkspace(workspace) }
        val project = Project(java.util.UUID.randomUUID().toString(), validateProjectName(name), normalized, java.time.Instant.now())
        persistRegistry(registry.copy(projects = registry.projects + project))
        project
    }

    suspend fun renameProject(id: String, name: String) = projectMutex.withLock {
        check(projectDataAvailable && !closed) { "项目数据不可用" }
        val project = requireNotNull(projectById(id)) { "项目不存在" }
        persistRegistry(registry.copy(projects = registry.projects.map { if (it.id == id) project.copy(name = validateProjectName(name)) else it }))
    }

    suspend fun deleteProject(id: String) = projectMutex.withLock {
        check(projectDataAvailable && !closed) { "项目数据不可用" }
        requireNotNull(projectById(id)) { "项目不存在" }
        // Re-scan sidecars as well: a quarantined session still owns its reference.
        val disk = repository.loadAll(requireNotNull(lastDefaultWorkspace), registry.projects.map { it.workspace })
        check(sessions.none { it.projectId == id } && disk.none { it.projectId == id }) { "请先删除项目内的会话" }
        check(repository.loadIssues.isEmpty()) { "存在未恢复会话，请先修复后再删除项目" }
        persistRegistry(registry.copy(projects = registry.projects.filterNot { it.id == id }, legacyDefaults = registry.legacyDefaults.filterValues { it != id }))
        projectExpanded = projectExpanded - id
        try { saveProjectExpandState(projectExpanded, projectExpandFile) }
        catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error }
    }

    suspend fun setProjectExpanded(id: String, expanded: Boolean) = projectMutex.withLock {
        check(projectDataAvailable && !closed) { "项目数据不可用" }
        requireNotNull(projectById(id)) { "项目不存在" }
        val next = projectExpanded + (id to expanded)
        withContext(NonCancellable) {
            saveProjectExpandState(next, projectExpandFile)
            projectExpanded = next
        }
    }

    suspend fun startNewSessionInProject(projectId: String, selection: ModelSelection = defaultModel): SessionData = projectMutex.withLock {
        createProjectSession(requireNotNull(projectById(projectId)) { "项目不存在，请重新选择" }, selection)
    }

    private suspend fun createProjectSession(project: Project, selection: ModelSelection): SessionData {
        check(projectDataAvailable && !closed) { "项目数据不可用" }
        withContext(ioDispatcher) { readableProjectWorkspace(project.workspace) }
        val stored = repository.createInProject(project.workspace, "新会话 ${sessions.size + 1}", selection, project.id)
        val created = toSessionData(stored, selection)
        sessions.add(0, created)
        activeSessionIndex = 0
        return created
    }

    suspend fun addNewSession() {
        val active = activeSession()
        startNewSessionInProject(requireNotNull(active.projectId))
    }

    /** Creates and activates a fresh session even when [workspace] is already active. */
    internal suspend fun startNewSessionInWorkspace(workspace: Path, selection: ModelSelection = defaultModel): SessionData = projectMutex.withLock {
        // Compatibility entry for existing callers/tests, never used by the desktop selector.
        val normalized = withContext(ioDispatcher) { readableProjectWorkspace(workspace) }
        createProjectSession(legacyProject(normalized), selection)
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
        val id = sessions.getOrNull(index)?.id ?: return false
        return deleteSessionById(id)
    }

    suspend fun deleteSessionById(id: String): Boolean = projectMutex.withLock {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return@withLock false
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
        true
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
        check(projectDataAvailable) { "项目数据不可用，请修复后重试加载" }
        val project = requireNotNull(projectById(session.projectId)) { "会话项目归属无效" }
        check(projectWorkspaceKey(project.workspace) == projectWorkspaceKey(session.workspace)) { "项目与会话工作区不一致" }
        withContext(ioDispatcher) { readableProjectWorkspace(session.workspace) }
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
        projectId = stored.projectId,
    )
}

internal fun readableProjectWorkspace(workspace: Path): Path {
    val path = workspace.toAbsolutePath().normalize()
    require(java.nio.file.Files.isDirectory(path) && java.nio.file.Files.isReadable(path)) { "工作区目录不存在或不可读：$path" }
    return path
}
