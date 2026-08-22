package com.kzagent.kagent.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.AppConfigLoader
import com.kzagent.kagent.config.ConfigWriter
import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.ProviderId
import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.agent.estimateContextTokens
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.ModelCatalogService
import com.kzagent.kagent.tools.ApprovalDecision
import com.kzagent.kagent.tools.ApprovalMode
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ApprovalResult
import com.kzagent.kagent.tools.ApprovalSource
import com.kzagent.kagent.tools.ToolResult
import com.kzagent.kagent.tools.UserQuestionAnswer
import com.kzagent.kagent.tools.UserQuestionPrompter
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Text as FluentText
import io.github.composefluent.component.TextField as FluentTextField
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.system.exitProcess

@Composable
internal fun KZAgentDesktopApp(
    initialWorkspace: Path,
    createStartupSession: Boolean,
    instanceCoordinator: DesktopSingleInstanceCoordinator,
    activateWindow: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val pendingApprovals = remember { mutableStateListOf<PendingApproval>() }
    val pendingUserQuestions = remember { mutableStateListOf<PendingUserQuestions>() }
    var showDeleteConfirmIndex by remember { mutableStateOf(-1) }
    var showRenameDialogIndex by remember { mutableStateOf(-1) }
    var renameText by remember { mutableStateOf("") }
    var showCompressConfirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var savedConfig by remember { mutableStateOf<AppConfig?>(null) }
    var configLoaded by remember { mutableStateOf(false) }
    val availableModels = remember { mutableStateListOf<ModelDescriptor>() }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var modelCatalogJob by remember { mutableStateOf<Job?>(null) }
    var settingsSaving by remember { mutableStateOf(false) }
    var settingsSaveError by remember { mutableStateOf<String?>(null) }
    var commandAvailability by remember { mutableStateOf<UserCommandAvailability?>(null) }
    var commandInstalling by remember { mutableStateOf(false) }
    var commandInstallMessage by remember { mutableStateOf<String?>(null) }
    var commandInstallFailed by remember { mutableStateOf(false) }
    var sessionLoadError by remember { mutableStateOf<String?>(null) }
    val sessionWorkspaceExpandedState = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val userCommandInstaller = remember { UserCommandInstaller() }
    val modelCatalogService = remember { ModelCatalogService() }

    // Check configuration on startup; if API key is missing, open settings
    LaunchedEffect(Unit) {
        try {
            savedConfig = withContext(Dispatchers.IO) { AppConfigLoader.load() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            savedConfig = null
            showSettings = true
        } finally {
            configLoaded = true
        }
    }

    LaunchedEffect(userCommandInstaller) {
        commandAvailability = withContext(Dispatchers.IO) {
            userCommandInstaller.availability()
        }
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadSessionWorkspaceExpandState() }
        sessionWorkspaceExpandedState.putAll(loaded)
    }

    val approvalPolicy = remember {
        ApprovalPolicy { request ->
            suspendCancellableCoroutine { continuation ->
                lateinit var approval: PendingApproval
                approval = PendingApproval(request) { allowed ->
                    pendingApprovals.remove(approval)
                    if (continuation.isActive) {
                        continuation.resume(
                            ApprovalResult(
                                decision = if (allowed) ApprovalDecision.ALLOW else ApprovalDecision.DENY,
                                source = ApprovalSource.HUMAN,
                                reason = if (allowed) "用户已批准。" else "用户已拒绝。",
                            ),
                        )
                    }
                }
                continuation.invokeOnCancellation { pendingApprovals.remove(approval) }
                pendingApprovals.add(approval)
            }
        }
    }

    val userQuestionPrompter = remember {
        UserQuestionPrompter { questions ->
            suspendCancellableCoroutine { continuation ->
                lateinit var pending: PendingUserQuestions
                pending = PendingUserQuestions(questions) { answers ->
                    pendingUserQuestions.remove(pending)
                    if (continuation.isActive) continuation.resume(answers)
                }
                continuation.invokeOnCancellation { pendingUserQuestions.remove(pending) }
                pendingUserQuestions.add(pending)
            }
        }
    }
    val sessionManager = remember {
        SessionManager(approvalPolicy, userQuestionPrompter = userQuestionPrompter)
    }
    DisposableEffect(sessionManager, modelCatalogService) {
        onDispose {
            sessionManager.close()
            modelCatalogService.close()
        }
    }

    LaunchedEffect(sessionManager, initialWorkspace, createStartupSession, configLoaded, savedConfig?.defaultModel) {
        if (!configLoaded || savedConfig == null) return@LaunchedEffect
        sessionManager.updateDefaultModel(savedConfig!!.defaultModel)
        try {
            sessionManager.loadOrCreate(initialWorkspace, createStartupSession)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sessionLoadError = SecretRedactor.redact(error.message ?: error.toString())
        }

        instanceCoordinator.requests.collect { request ->
            activateWindow()
            if (request !is DesktopLaunchRequest.OpenWorkspace) return@collect

            try {
                check(sessionManager.initialized) { "会话列表尚未成功初始化。" }
                val workspace = withContext(Dispatchers.IO) {
                    requireReadableWorkspace(request.workspace)
                }
                sessionManager.startNewSessionInWorkspace(workspace)
                showSettings = false
                sessionLoadError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                sessionLoadError = SecretRedactor.redact(error.message ?: error.toString())
            }
        }
    }

    fun refreshModels() {
        val config = savedConfig ?: return
        modelCatalogJob?.cancel()
        modelsLoading = true
        modelsError = null
        lateinit var refreshJob: Job
        refreshJob = scope.launch {
            val loaded = mutableListOf<ModelDescriptor>()
            val errors = mutableListOf<String>()
            try {
                config.configuredProviders.forEach { provider ->
                    try {
                        loaded += withContext(Dispatchers.IO) {
                            modelCatalogService.loadProvider(config, provider)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        errors += "${provider.displayName}: ${SecretRedactor.redact(error.message ?: error.toString())}"
                    }
                }
                availableModels.clear()
                availableModels.addAll(loaded)
                modelsError = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
            } finally {
                if (modelCatalogJob === refreshJob) modelsLoading = false
            }
        }
        modelCatalogJob = refreshJob
    }

    LaunchedEffect(savedConfig?.deepSeek, savedConfig?.openRouter) {
        if (savedConfig != null) refreshModels()
    }

    // Persist configuration away from the UI dispatcher, then invalidate only the runtimes.
    fun saveSettings(config: AppConfig) {
        if (settingsSaving) return
        settingsSaving = true
        settingsSaveError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ConfigWriter.save(config) }
                savedConfig = withContext(Dispatchers.IO) { AppConfigLoader.load() }
                sessionManager.updateDefaultModel(savedConfig!!.defaultModel)
                sessionManager.invalidateRuntimes()
                sessionManager.sessions.filter { savedConfig!!.provider(it.modelSelection.provider) == null }
                    .forEach { session -> sessionManager.updateModel(session, savedConfig!!.defaultModel) }
                showSettings = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settingsSaveError = SecretRedactor.redact(error.message ?: error.toString())
                if (!showSettings) {
                    sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)?.error =
                        "保存设置失败：$settingsSaveError"
                }
            } finally {
                settingsSaving = false
            }
        }
    }

    fun onApprovalModeChanged(mode: ApprovalMode) {
        val current = savedConfig ?: return
        if (current.approvalMode != mode) saveSettings(current.copy(approvalMode = mode))
    }

    fun onModelChanged(session: SessionData, selection: ModelSelection) {
        val currentConfig = savedConfig ?: return
        if (session.isBusy || currentConfig.provider(selection.provider) == null) return
        scope.launch {
            try {
                sessionManager.updateModel(session, selection)
                val updatedConfig = currentConfig.copy(defaultModel = selection)
                withContext(Dispatchers.IO) { ConfigWriter.save(updatedConfig) }
                savedConfig = updatedConfig
                sessionManager.updateDefaultModel(selection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                session.error = SecretRedactor.redact(error.message ?: error.toString())
                session.status = "模型切换失败"
            }
        }
    }

    fun installUserCommand() {
        if (commandInstalling || commandAvailability?.available != true) return
        commandInstalling = true
        commandInstallMessage = null
        commandInstallFailed = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    userCommandInstaller.install()
                }
                commandAvailability = withContext(Dispatchers.IO) {
                    userCommandInstaller.availability()
                }
                commandInstallMessage = buildString {
                    append("已安装到 ${result.commandPath}。")
                    if (result.restartTerminalRequired) {
                        append(" 请重新打开终端后使用。")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                commandInstallFailed = true
                commandInstallMessage = SecretRedactor.redact(error.message ?: error.toString())
            } finally {
                commandInstalling = false
            }
        }
    }

    // Auto-collapse tool messages when a session becomes idle
    LaunchedEffect(sessionManager.sessions.map { it.isBusy }) {
        sessionManager.sessions.forEach { session ->
            if (!session.isBusy) {
                session.messages.indices.forEach { i ->
                    if (session.messages[i].collapsible && !session.messages[i].collapsed) {
                        session.messages[i] = session.messages[i].copy(collapsed = true)
                    }
                }
            }
        }
    }

    fun createObserver(session: SessionData): AgentObserver {
        return object : AgentObserver {
            override suspend fun onContextCompressionStarted(usagePercent: Int) {
                session.status = "上下文超 80%，自动压缩..."
                session.messages.add(
                    DisplayMessage(
                        "tool_result",
                        "⚠️ 上下文使用率达 $usagePercent%，自动触发压缩...",
                        timestampMillis = Instant.now().toEpochMilli(),
                    ),
                )
            }
            override suspend fun onContextCompressionCompleted(estimatedTokens: Int) {
                session.usedTokens = estimatedTokens
                session.status = "上下文压缩完成"
                session.messages.add(
                    DisplayMessage(
                        "tool_result",
                        "✅ 上下文已自动压缩，保留最近消息并生成了历史摘要。",
                        timestampMillis = Instant.now().toEpochMilli(),
                    ),
                )
            }
            override suspend fun onModelRequest(turn: Int) {
                session.status = "请求模型（第 ${turn} 轮）..."
            }
            override suspend fun onAssistantMessage(content: String) {
                session.messages.add(DisplayMessage("assistant", content, timestampMillis = Instant.now().toEpochMilli()))
            }
            override suspend fun onToolCallStarted(name: String, argsJson: String) {
                val summary = formatToolCallSummary(name, argsJson)
                session.messages.add(
                    DisplayMessage(
                        "tool_call",
                        summary,
                        collapsible = true,
                        collapsed = false,
                        timestampMillis = Instant.now().toEpochMilli(),
                    ),
                )
                session.status = when (name) {
                    "run_command" -> when (savedConfig?.approvalMode) {
                        com.kzagent.kagent.tools.ApprovalMode.MANUAL -> "等待命令审批..."
                        com.kzagent.kagent.tools.ApprovalMode.AUTO -> "正在自动审批命令..."
                        com.kzagent.kagent.tools.ApprovalMode.FULL -> "执行命令..."
                        null -> "正在自动审批命令..."
                    }
                    "read_file" -> when (savedConfig?.approvalMode) {
                        com.kzagent.kagent.tools.ApprovalMode.FULL -> "读取文件..."
                        else -> "检查文件读取权限..."
                    }
                    "fetch_web_page" -> "正在获取并解析网页..."
                    "todo_read" -> "正在查看 Todo..."
                    "todo_write" -> "正在更新 Todo..."
                    "ask_user" -> "等待用户回答..."
                    else -> "执行工具：$name"
                }
            }
            override suspend fun onToolResult(name: String, result: ToolResult) {
                session.messages.add(
                    DisplayMessage(
                        "tool_result",
                        result.content,
                        collapsible = true,
                        collapsed = false,
                        timestampMillis = Instant.now().toEpochMilli(),
                    ),
                )
                session.status = when (result.approvalSource) {
                    ApprovalSource.STATIC_RULE -> "静态规则已放行：$name"
                    ApprovalSource.APPROVAL_AGENT ->
                        if (result.isError) "审批 Agent 已拒绝：$name" else "审批 Agent 已放行：$name"
                    ApprovalSource.HUMAN ->
                        if (result.isError) "人工已拒绝：$name" else "人工已批准：$name"
                    ApprovalSource.FULL_MODE -> "全部放行：$name"
                    null -> if (result.isError) "工具返回错误：$name" else "工具完成：$name"
                }
            }
        }
    }

    // Ensure active session has a runtime
    val activeSession = sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)
    LaunchedEffect(sessionManager, activeSession?.id, activeSession?.workspace, activeSession?.runtime) {
        val session = activeSession ?: return@LaunchedEffect
        session.status = "正在加载..."
        val observer = createObserver(session)
        session.error = null
        try {
            sessionManager.ensureRuntime(session, observer)
            session.status = "就绪"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            desktopLog(
                "failed to initialize session ${session.id}: ${runtimeErrorMessage(error)}",
                error,
            )
            session.error = SecretRedactor.redact(runtimeErrorMessage(error))
            session.status = "配置不可用"
        }
    }
    LaunchedEffect(activeSession?.id, activeSession?.runtime) {
        val session = activeSession ?: return@LaunchedEffect
        val runtime = session.runtime ?: return@LaunchedEffect
        runtime.todoState.collect { snapshot ->
            session.todoSnapshot = snapshot
        }
    }

    // Reusable context compression helper
    suspend fun performCompression(session: SessionData, manageBusyState: Boolean = true): Boolean {
        if ((manageBusyState && session.isBusy) || session.runtime == null) return false
        val sessionId = session.id
        val titleRevision = session.titleRevision
        if (manageBusyState) session.isBusy = true
        session.status = "正在压缩上下文..."
        return try {
            val compressed = session.runtime!!.agent.compressHistory(session.conversationHistory)
            session.conversationHistory = compressed
            session.usedTokens = estimateContextTokens(compressed)
            session.messages.add(
                DisplayMessage(
                    "tool_result",
                    "✅ 上下文已压缩。之前的对话已总结为摘要，保留最近几条消息。",
                    timestampMillis = Instant.now().toEpochMilli(),
                ),
            )
            session.status = "就绪"
            // Auto-update session title from compression summary
            val summary = compressed.firstOrNull()
            if (summary is AgentMessage.Summary) {
                val agent = session.runtime!!.agent
                scope.launch {
                    try {
                        val title = agent.generateTitle(summary.content)
                        sessionManager.renameSessionIfRevisionMatches(sessionId, titleRevision, title)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Title generation is best-effort and must not fail compression.
                    }
                }
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            session.error = "压缩失败: ${SecretRedactor.redact(e.message ?: e.toString())}"
            session.status = "压缩失败"
            false
        } finally {
            if (manageBusyState) session.isBusy = false
        }
    }

    KZAgentFluentTheme {
        KZAgentNavigationView(
            sessions = sessionManager.sessions,
            activeIndex = sessionManager.activeSessionIndex,
            sessionWorkspaceExpanded = sessionWorkspaceExpandedState,
            settingsSelected = showSettings,
            onSelectSession = { index ->
                sessionManager.switchTo(index)
                showSettings = false
            },
            onWorkspaceExpandedChanged = { key, expanded ->
                sessionWorkspaceExpandedState[key] = expanded
                scope.launch {
                    withContext(Dispatchers.IO) {
                        saveSessionWorkspaceExpandState(sessionWorkspaceExpandedState.toMap())
                    }
                }
            },
            onAddSession = {
                if (sessionManager.initialized) {
                    scope.launch {
                        try {
                            sessionManager.addNewSession()
                            showSettings = false
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            sessionLoadError = SecretRedactor.redact(error.message ?: error.toString())
                        }
                    }
                }
            },
            onDeleteSession = { showDeleteConfirmIndex = it },
            onRenameSession = { index ->
                renameText = sessionManager.sessions[index].name
                showRenameDialogIndex = index
            },
            onChooseWorkspace = {
                scope.launch {
                    val session = sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)
                        ?: return@launch
                    try {
                        chooseWorkspace(session.workspace)?.let { newWorkspace ->
                            sessionManager.startSessionInWorkspace(session, newWorkspace)
                            showSettings = false
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        session.error = SecretRedactor.redact(error.message ?: error.toString())
                    }
                }
            },
            onSettings = { showSettings = true },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showSettings) {
                SettingsPanel(
                    initialDeepSeekApiKey = savedConfig?.deepSeek?.apiKey.orEmpty(),
                    initialDeepSeekBaseUrl = savedConfig?.deepSeek?.baseUrl ?: AppConfig.DEFAULT_BASE_URL,
                    initialOpenRouterApiKey = savedConfig?.openRouter?.apiKey.orEmpty(),
                    initialOpenRouterBaseUrl = savedConfig?.openRouter?.baseUrl ?: AppConfig.DEFAULT_OPENROUTER_BASE_URL,
                    initialDefaultModel = savedConfig?.defaultModel ?: ModelSelection(
                        ProviderId.DEEPSEEK,
                        AppConfig.DEFAULT_MODEL,
                        AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
                    ),
                    initialContextWindowSize = savedConfig?.contextWindowSize ?: AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
                    initialSensitivePathProtection = savedConfig?.sensitivePathProtection ?: AppConfig.DEFAULT_SENSITIVE_PATH_PROTECTION,
                    initialUserPrompt = savedConfig?.userPrompt ?: "",
                    initialApprovalMode = savedConfig?.approvalMode ?: AppConfig.DEFAULT_APPROVAL_MODE,
                    availableModels = availableModels,
                    modelsLoading = modelsLoading,
                    modelsError = modelsError,
                    onRefreshModels = ::refreshModels,
                    saving = settingsSaving,
                    saveError = settingsSaveError,
                    commandAvailable = commandAvailability?.available == true,
                    commandInstalled = commandAvailability?.installed == true,
                    commandPath = commandAvailability?.commandPath?.toString(),
                    commandUnavailableReason = commandAvailability?.unavailableReason
                        ?: if (commandAvailability == null) "正在检测可用性..." else null,
                    commandInstalling = commandInstalling,
                    commandInstallMessage = commandInstallMessage,
                    commandInstallFailed = commandInstallFailed,
                    onInstallCommand = ::installUserCommand,
                    onSave = ::saveSettings,
                    onCancel = {
                        if (savedConfig != null) {
                            showSettings = false
                        }
                    },
                )
            } else if (!sessionManager.initialized || sessionManager.sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (sessionLoadError == null) {
                        CircularProgressIndicator()
                    } else {
                        ErrorBanner(sessionLoadError!!)
                    }
                }
            } else {
                val session = sessionManager.activeSession()
                var showTodoDialog by remember(session.id) { mutableStateOf(false) }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    val showPersistentTodo = shouldShowPersistentTodoPanel(maxWidth)
                    Column(modifier = Modifier.fillMaxSize()) {
                        Header(
                            workspace = session.workspace,
                            status = session.status,
                            isBusy = session.isBusy,
                            contextPercent = (session.usedTokens * 100) / (session.runtime?.contextWindowSize ?: 1_000_000),
                            approvalMode = savedConfig?.approvalMode ?: AppConfig.DEFAULT_APPROVAL_MODE,
                            modelSelection = session.modelSelection,
                            availableModels = availableModels,
                            modelsLoading = modelsLoading,
                            modelsError = modelsError,
                            todoSnapshot = session.todoSnapshot,
                            showTodoButton = !showPersistentTodo,
                            onShowTodo = { showTodoDialog = true },
                            onApprovalModeChanged = { onApprovalModeChanged(it) },
                            onModelChanged = { onModelChanged(session, it) },
                            onRefreshModels = ::refreshModels,
                            onCompressContext = { showCompressConfirm = true },
                        )
                        Spacer(Modifier.height(10.dp))
                        session.error?.let {
                            ErrorBanner(it)
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                MessageList(
                                    sessionId = session.id,
                                    messages = session.messages,
                                    workspace = session.workspace,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                                Spacer(Modifier.height(12.dp))
                                Composer(
                                    input = input,
                                    isBusy = session.isBusy,
                                    enabled = session.runtime != null,
                                    onInputChange = { input = it },
                                    onSend = {
                                        val prompt = input.trim()
                                        if (prompt.isEmpty()) return@Composer
                                        val currentRuntime = session.runtime ?: return@Composer
                                        input = ""
                                        session.isBusy = true
                                        session.error = null
                                        session.status = "准备发送..."
                                        session.messages.add(
                                            DisplayMessage(
                                                "user",
                                                prompt,
                                                timestampMillis = Instant.now().toEpochMilli(),
                                            ),
                                        )
                                        val sessionId = session.id
                                        val titleRevision = session.titleRevision
                                        val job = scope.launch {
                                            try {
                                                val result = currentRuntime.agent.runConversation(prompt, session.conversationHistory)
                                                session.conversationHistory = result.history
                                                session.usedTokens = result.totalTokens
                                                session.status = "就绪"
                                                // Auto-title on first user message
                                                if (result.history.count { it is AgentMessage.User } == 1) {
                                                    scope.launch {
                                                        try {
                                                            val title = currentRuntime.agent.generateTitle(prompt)
                                                            sessionManager.renameSessionIfRevisionMatches(
                                                                sessionId,
                                                                titleRevision,
                                                                title,
                                                            )
                                                        } catch (error: CancellationException) {
                                                            throw error
                                                        } catch (_: Exception) {
                                                            // Title generation is best-effort and does not fail the user request.
                                                        }
                                                    }
                                                }
                                            } catch (_: CancellationException) {
                                                session.status = "已终止"
                                            } catch (e: Exception) {
                                                session.error = SecretRedactor.redact(e.message ?: e.toString())
                                                session.status = "请求失败"
                                            } finally {
                                                session.isBusy = false
                                                session.currentJob = null
                                            }
                                        }
                                        session.currentJob = job
                                    },
                                    onTerminate = {
                                        session.currentJob?.cancel()
                                        session.status = "正在终止..."
                                    },
                                )
                            }
                            if (showPersistentTodo) {
                                TodoPanel(
                                    snapshot = session.todoSnapshot,
                                    modifier = Modifier.width(TodoPanelWidth).fillMaxHeight(),
                                )
                            }
                        }
                    }
                    if (!showPersistentTodo && showTodoDialog) {
                        TodoDialog(
                            snapshot = session.todoSnapshot,
                            onDismiss = { showTodoDialog = false },
                        )
                    }
                }
            }
        }
    }

    pendingApprovals.firstOrNull()?.let { approval ->
        ApprovalDialog(approval)
    }
    pendingUserQuestions.firstOrNull()?.let { pending ->
        UserQuestionDialog(pending)
    }

    // Compress confirmation dialog
    if (showCompressConfirm) {
        val session = sessionManager.activeSession()
        val ctxPct = (session.usedTokens * 100) / (session.runtime?.contextWindowSize ?: 1_000_000)
        ContentDialog(
            title = "压缩上下文",
            visible = true,
            content = {
                FluentText(
                    "当前上下文使用率 $ctxPct%。压缩将使用 LLM 把较早的对话总结为摘要，" +
                        "仅保留最近几条消息。是否继续？",
                )
            },
            primaryButtonText = "压缩",
            closeButtonText = "取消",
            onButtonClick = { button ->
                showCompressConfirm = false
                if (button == ContentDialogButton.Primary) {
                    scope.launch {
                        performCompression(session)
                        session.isBusy = false
                    }
                }
            },
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirmIndex >= 0) {
        val sessionName = sessionManager.sessions.getOrNull(showDeleteConfirmIndex)?.name ?: ""
        ContentDialog(
            title = "删除会话",
            visible = true,
            content = {
                FluentText("确定要删除会话「$sessionName」吗？此操作不可撤销。")
            },
            primaryButtonText = "删除",
            closeButtonText = "取消",
            onButtonClick = { button ->
                if (button == ContentDialogButton.Primary) {
                    val index = showDeleteConfirmIndex
                    showDeleteConfirmIndex = -1
                    scope.launch {
                        try {
                            sessionManager.deleteSession(index)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            sessionLoadError = SecretRedactor.redact(error.message ?: error.toString())
                        }
                    }
                } else {
                    showDeleteConfirmIndex = -1
                }
            },
        )
    }

    // Rename dialog
    if (showRenameDialogIndex >= 0) {
        ContentDialog(
            title = "重命名会话",
            visible = true,
            content = {
                FluentTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    header = { FluentText("会话名称") },
                    singleLine = true,
                )
            },
            primaryButtonText = "确定",
            closeButtonText = "取消",
            onButtonClick = { button ->
                if (button == ContentDialogButton.Primary) {
                    val index = showRenameDialogIndex
                    val name = renameText
                    showRenameDialogIndex = -1
                    if (name.isNotBlank()) {
                        scope.launch {
                            try {
                                sessionManager.renameSession(index, name)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                sessionLoadError = SecretRedactor.redact(error.message ?: error.toString())
                            }
                        }
                    }
                } else {
                    showRenameDialogIndex = -1
                }
            },
        )
    }
}
