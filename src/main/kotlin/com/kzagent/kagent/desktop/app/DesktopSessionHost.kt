package com.kzagent.kagent.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.Composer
import com.kzagent.kagent.desktop.DisplayMessage
import com.kzagent.kagent.desktop.ErrorBanner
import com.kzagent.kagent.desktop.Header
import com.kzagent.kagent.desktop.KZAgentFluentTheme
import com.kzagent.kagent.desktop.KZAgentNavigationView
import com.kzagent.kagent.desktop.MessageList
import com.kzagent.kagent.desktop.PendingApproval
import com.kzagent.kagent.desktop.PendingUserQuestions
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.desktop.ui.SessionSidePanel
import com.kzagent.kagent.desktop.SettingsPanel
import com.kzagent.kagent.desktop.chooseWorkspace
import com.kzagent.kagent.desktop.loadSessionWorkspaceExpandState
import com.kzagent.kagent.desktop.saveSessionWorkspaceExpandState
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.ApprovalDecision
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ApprovalResult
import com.kzagent.kagent.tools.ApprovalSource
import com.kzagent.kagent.tools.UserQuestionPrompter
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.resume

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
    var renameSuggesting by remember { mutableStateOf(false) }
    var showCompressConfirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSessionSidePanel by remember { mutableStateOf(true) }
    var sessionLoadError by remember { mutableStateOf<String?>(null) }
    val sessionWorkspaceExpandedState = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

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

    val settingsState = rememberDesktopSessionSettingsState(
        sessionManager = sessionManager,
        scope = scope,
        onDismiss = { showSettings = false },
        onConfigurationRequired = { showSettings = true },
        onSessionError = { msg ->
            sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)?.error = msg
        },
    )

    DisposableEffect(sessionManager, settingsState) {
        onDispose {
            sessionManager.close()
            settingsState.close()
        }
    }

    LaunchedEffect(sessionManager, initialWorkspace, createStartupSession, settingsState.configLoaded, settingsState.savedConfig?.defaultModel) {
        if (!settingsState.configLoaded) return@LaunchedEffect
        val config = settingsState.savedConfig ?: return@LaunchedEffect
        sessionManager.updateDefaultModel(config.defaultModel)
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

    // Ensure active session has a runtime
    val activeSession = sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)
    LaunchedEffect(sessionManager, activeSession?.id, activeSession?.workspace, activeSession?.runtime) {
        val session = activeSession ?: return@LaunchedEffect
        session.status = "正在加载..."
        val observer = createAgentObserver(session, settingsState.savedConfig?.approvalMode)
        session.error = null
        try {
            sessionManager.ensureRuntime(session, observer)
            session.status = "就绪"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            desktopLog(
                "failed to initialize session ${session.id}: ${
                    runtimeErrorMessage(
                        error
                    )
                }",
                error,
            )
            session.error = SecretRedactor.redact(
                runtimeErrorMessage(
                    error
                )
            )
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
                            sessionLoadError =
                                SecretRedactor.redact(error.message ?: error.toString())
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
                    val session =
                        sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)
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
                    initialProviders = settingsState.savedConfig?.providers.orEmpty(),
                    initialDefaultModel = settingsState.savedConfig?.defaultModel ?: ModelSelection(
                        AppConfig.DEFAULT_PROVIDER_ID,
                        AppConfig.DEFAULT_MODEL,
                        AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
                    ),
                    initialContextWindowSize = settingsState.savedConfig?.contextWindowSize
                        ?: AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
                    initialSensitivePathProtection = settingsState.savedConfig?.sensitivePathProtection
                        ?: AppConfig.DEFAULT_SENSITIVE_PATH_PROTECTION,
                    initialUserPrompt = settingsState.savedConfig?.userPrompt ?: "",
                    initialApprovalMode = settingsState.savedConfig?.approvalMode
                        ?: AppConfig.DEFAULT_APPROVAL_MODE,
                    availableModels = settingsState.availableModels,
                    modelsLoading = settingsState.modelsLoading,
                    modelsError = settingsState.modelsError,
                    onRefreshModels = { settingsState.refreshModels() },
                    saving = settingsState.settingsSaving,
                    saveError = settingsState.settingsSaveError,
                    commandAvailable = settingsState.commandAvailability?.available == true,
                    commandInstalled = settingsState.commandAvailability?.installed == true,
                    commandPath = settingsState.commandAvailability?.commandPath?.toString(),
                    commandUnavailableReason = settingsState.commandAvailability?.unavailableReason
                        ?: if (settingsState.commandAvailability == null) "正在检测可用性..." else null,
                    commandInstalling = settingsState.commandInstalling,
                    commandInstallMessage = settingsState.commandInstallMessage,
                    commandInstallFailed = settingsState.commandInstallFailed,
                    onInstallCommand = { settingsState.installUserCommand() },
                    onSave = settingsState::saveSettings,
                    onCancel = {
                        if (settingsState.savedConfig != null) {
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Header(
                            workspace = session.workspace,
                            status = session.status,
                            isBusy = session.isBusy,
                            modelSelection = session.modelSelection,
                            availableModels = settingsState.availableModels,
                            modelsLoading = settingsState.modelsLoading,
                            modelsError = settingsState.modelsError,
                            sidePanelVisible = showSessionSidePanel,
                            onToggleSidePanel = { showSessionSidePanel = !showSessionSidePanel },
                            onModelChanged = { settingsState.onModelChanged(session, it) },
                            onRefreshModels = { settingsState.refreshModels() },
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
                                        // Auto-title on first user message: fire immediately, don't wait for the answer
                                        val isFirstUserMessage = session.conversationHistory.none { it is AgentMessage.User }
                                        if (isFirstUserMessage) {
                                            scope.launch {
                                                try {
                                                    val title = currentRuntime.agent.generateTitle(prompt)
                                                    sessionManager.renameSessionIfRevisionMatches(sessionId, titleRevision, title)
                                                } catch (error: CancellationException) {
                                                    throw error
                                                } catch (_: Exception) {
                                                    // Title generation is best-effort.
                                                }
                                            }
                                        }
                                        val job = scope.launch {
                                            try {
                                                val result = currentRuntime.agent.runConversation(
                                                    prompt,
                                                    session.conversationHistory
                                                )
                                                session.conversationHistory = result.history
                                                session.usedTokens = result.totalTokens
                                                session.status = "就绪"
                                            } catch (_: CancellationException) {
                                                session.status = "已终止"
                                            } catch (e: Exception) {
                                                session.error =
                                                    SecretRedactor.redact(e.message ?: e.toString())
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
                            if (showSessionSidePanel) {
                                SessionSidePanel(
                                    approvalMode = settingsState.savedConfig?.approvalMode
                                        ?: AppConfig.DEFAULT_APPROVAL_MODE,
                                    onApprovalModeChanged = { settingsState.onApprovalModeChanged(it) },
                                    todoSnapshot = session.todoSnapshot,
                                    contextPercent = (session.usedTokens * 100) / (session.runtime?.contextWindowSize
                                        ?: 1_000_000),
                                    isBusy = session.isBusy,
                                    onCompressContext = { showCompressConfirm = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val activeSessionForDialogs = sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)
    SessionDialogs(
        pendingApprovals = pendingApprovals,
        pendingUserQuestions = pendingUserQuestions,
        showCompressConfirm = showCompressConfirm,
        onDismissCompressConfirm = { showCompressConfirm = false },
        onCompress = {
            activeSessionForDialogs?.let { session ->
                scope.launch {
                    performCompression(session)
                    session.isBusy = false
                }
            }
        },
        showDeleteConfirmIndex = showDeleteConfirmIndex,
        onDismissDeleteConfirm = { showDeleteConfirmIndex = -1 },
        onDeleteSession = { index ->
            showDeleteConfirmIndex = -1
            handleDeleteSession(index, sessionManager, scope) { error ->
                sessionLoadError = error
            }
        },
        showRenameDialogIndex = showRenameDialogIndex,
        renameText = renameText,
        onRenameTextChange = { renameText = it },
        renameSuggesting = renameSuggesting,
        onSuggestName = {
            val session = sessionManager.sessions.getOrNull(showRenameDialogIndex)
            handleSuggestName(session, { renameText = it }, { renameSuggesting = it }, scope)
        },
        onDismissRenameDialog = { showRenameDialogIndex = -1 },
        onRenameSession = { name ->
            val index = showRenameDialogIndex
            showRenameDialogIndex = -1
            scope.launch {
                try {
                    sessionManager.renameSession(index, name)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    sessionLoadError = SecretRedactor.redact(error.message ?: error.toString())
                }
            }
        },
        onCancelRenameDialog = {
            showRenameDialogIndex = -1
            renameSuggesting = false
        },
        sessionManager = sessionManager,
        contextWindowSize = activeSessionForDialogs?.runtime?.contextWindowSize ?: 1_000_000,
        sessionUsedTokens = activeSessionForDialogs?.usedTokens ?: 0,
    )
}
