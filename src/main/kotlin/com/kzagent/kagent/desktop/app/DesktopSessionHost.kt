package com.kzagent.kagent.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kzagent.kagent.desktop.KZAgentFluentTheme
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import java.nio.file.Path

@Composable
internal fun KZAgentDesktopApp(
    initialWorkspace: Path,
    openStartupWorkspace: Boolean,
    instanceCoordinator: DesktopSingleInstanceCoordinator,
    activateWindow: () -> Unit,
) {
    val state = remember { DesktopSessionUiState(initialWorkspace) }
    val scope = rememberCoroutineScope()
    val sessionManager = remember {
        SessionManager(state.approvalPolicy, userQuestionPrompter = state.userQuestionPrompter)
    }
    val terminalManager = remember {
        SessionTerminalManager(factory = { workspace ->
            check(sessionManager.projectDataAvailable) { "项目数据不可用，请修复后重试加载" }
            createDesktopTerminal(workspace)
        })
    }
    val settingsState = rememberDesktopSessionSettingsState(
        sessionManager = sessionManager,
        scope = scope,
        onDismiss = { state.showSettings = false; state.showSkills = false; state.showMcp = false },
        onConfigurationRequired = {},
        onSessionError = { message -> sessionManager.activeSessionOrNull()?.error = message },
    )

    DesktopSessionEffects(
        state, sessionManager, settingsState, initialWorkspace,
        openStartupWorkspace, instanceCoordinator, activateWindow, terminalManager,
    )

    KZAgentFluentTheme {
        DesktopSessionNavigation(state, sessionManager, scope, initialWorkspace) {
            Column(Modifier.fillMaxSize()) {
                ProjectLoadStatus(state, sessionManager, scope, initialWorkspace)
                DesktopSessionPane(state, sessionManager, settingsState, scope, terminalManager) { session, text ->
                    sendDesktopSessionMessage(state, sessionManager, settingsState, scope, session, text)
                }
            }
        }
        DesktopSessionDialogs(state, sessionManager, scope, terminalManager)
        ProjectDialogs(state, sessionManager, scope)
    }
}
