package com.kzagent.kagent.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.desktop.loadSessionWorkspaceExpandState
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Side effects are scoped to the root composition and preserve background session jobs. */
@Composable
internal fun DesktopSessionEffects(
    state: DesktopSessionUiState,
    sessionManager: SessionManager,
    settingsState: DesktopSessionSettingsState,
    initialWorkspace: Path,
    openStartupWorkspace: Boolean,
    instanceCoordinator: DesktopSingleInstanceCoordinator,
    activateWindow: () -> Unit,
) {
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadSessionWorkspaceExpandState() }
        state.sessionWorkspaceExpandedState.putAll(loaded)
    }

    LaunchedEffect(settingsState.savedConfig?.providers, state.draft.model) {
        val selected = state.draft.model ?: return@LaunchedEffect
        if (settingsState.savedConfig?.provider(selected.provider) == null) {
            state.draft.error = "所选 Provider 已不可用，请重新选择模型"
        }
    }

    DisposableEffect(sessionManager, settingsState) {
        onDispose {
            sessionManager.close()
            settingsState.close()
        }
    }

    LaunchedEffect(sessionManager, initialWorkspace, openStartupWorkspace, settingsState.configLoaded, settingsState.savedConfig?.defaultModel) {
        if (!settingsState.configLoaded) return@LaunchedEffect
        settingsState.savedConfig?.let { sessionManager.updateDefaultModel(it.defaultModel) }
        try {
            sessionManager.loadSessions(initialWorkspace)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.sessionLoadError = SecretRedactor.redact(error.message ?: error.toString())
        }

        instanceCoordinator.requests.collect { request ->
            activateWindow()
            if (request !is DesktopLaunchRequest.OpenWorkspace) return@collect

            try {
                check(sessionManager.initialized) { "会话列表尚未成功初始化。" }
                val workspace = withContext(Dispatchers.IO) {
                    requireReadableWorkspace(request.workspace)
                }
                if (state.draft.submitting) return@collect
                state.draft.workspace = workspace
                state.hasDraft = true
                state.showNewSession = true
                state.showSettings = false; state.showSkills = false
                state.sessionLoadError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = SecretRedactor.redact(error.message ?: error.toString())
                state.draft.error = message
                sessionManager.activeSessionOrNull()?.error = message
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
    val activeSession = if (state.showNewSession || state.showSettings || state.showSkills) null else sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)
    LaunchedEffect(sessionManager, activeSession?.id, activeSession?.workspace, activeSession?.runtime) {
        val session = activeSession ?: return@LaunchedEffect
        if (session.isBusy || session.runtime != null) return@LaunchedEffect
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



}
