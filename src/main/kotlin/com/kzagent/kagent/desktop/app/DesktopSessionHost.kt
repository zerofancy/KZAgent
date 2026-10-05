package com.kzagent.kagent.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kzagent.kagent.desktop.KZAgentFluentTheme
import com.kzagent.kagent.desktop.SessionManager
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
    val settingsState = rememberDesktopSessionSettingsState(
        sessionManager = sessionManager,
        scope = scope,
        onDismiss = { state.showSettings = false; state.showSkills = false },
        onConfigurationRequired = {},
        onSessionError = { message -> sessionManager.activeSessionOrNull()?.error = message },
    )

    DesktopSessionEffects(
        state, sessionManager, settingsState, initialWorkspace,
        openStartupWorkspace, instanceCoordinator, activateWindow,
    )

    KZAgentFluentTheme {
        DesktopSessionNavigation(state, sessionManager, scope, initialWorkspace) {
            DesktopSessionPane(state, sessionManager, settingsState, scope) { session, text ->
                sendDesktopSessionMessage(state, sessionManager, settingsState, scope, session, text)
            }
        }
    }
    DesktopSessionDialogs(state, sessionManager, scope)
}
