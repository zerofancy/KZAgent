package com.kzagent.kagent.desktop.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.KZAgentNavigationView
import com.kzagent.kagent.desktop.NewSessionDraft
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.desktop.chooseWorkspace
import com.kzagent.kagent.desktop.saveSessionWorkspaceExpandState
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopSessionNavigation(
    state: DesktopSessionUiState,
    sessionManager: SessionManager,
    scope: CoroutineScope,
    initialWorkspace: Path,
    pane: @Composable () -> Unit,
) {
    KZAgentNavigationView(
        sessions = sessionManager.sessions,
        activeIndex = if (state.showNewSession || sessionManager.sessions.isEmpty()) -1 else sessionManager.activeSessionIndex,
        sessionWorkspaceExpanded = state.sessionWorkspaceExpandedState,
        settingsSelected = state.showSettings,
        onSelectSession = { index ->
            if (state.draft.submitting) return@KZAgentNavigationView
            state.showNewSession = false
            sessionManager.switchTo(index)
            state.showSettings = false; state.showSkills = false
        },
        onWorkspaceExpandedChanged = { key, expanded ->
            state.sessionWorkspaceExpandedState[key] = expanded
            scope.launch {
                withContext(Dispatchers.IO) {
                    saveSessionWorkspaceExpandState(state.sessionWorkspaceExpandedState.toMap())
                }
            }
        },
        onAddSession = {
            if (!state.draft.submitting) {
                if (!state.hasDraft) {
                    state.draft = NewSessionDraft(sessionManager.activeSessionOrNull()?.workspace ?: initialWorkspace)
                    state.hasDraft = true
                }
                state.showNewSession = true
                state.showSettings = false; state.showSkills = false
            }
        },
        onDeleteSession = { if (!state.draft.submitting) state.showDeleteConfirmIndex = it },
        onRenameSession = { index ->
            if (state.draft.submitting) return@KZAgentNavigationView
            state.renameText = sessionManager.sessions[index].name
            state.showRenameDialogIndex = index
        },
        onChooseWorkspace = {
            if (!state.draft.submitting) scope.launch {
                try {
                    val currentWorkspace = if (state.hasDraft) state.draft.workspace
                        else sessionManager.activeSessionOrNull()?.workspace ?: initialWorkspace
                    chooseWorkspace(currentWorkspace)?.let { path ->
                        state.draft.workspace = withContext(Dispatchers.IO) { requireReadableWorkspace(path) }
                        state.hasDraft = true
                        state.showNewSession = true
                        state.showSettings = false; state.showSkills = false
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    val message = SecretRedactor.redact(error.message ?: error.toString())
                    state.draft.error = message
                    sessionManager.activeSessionOrNull()?.error = message
                }
            }
        },
        onSettings = { if (!state.draft.submitting) { state.showSettings = true; state.showSkills = false } },
        skillsSelected = state.showSkills,
        onSkills = { if (!state.draft.submitting) { state.showSkills = true; state.showSettings = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        pane()
    }
}

@Composable
internal fun DesktopSessionDialogs(
    state: DesktopSessionUiState,
    sessionManager: SessionManager,
    scope: CoroutineScope,
) {
    val activeSessionForDialogs = sessionManager.sessions.getOrNull(sessionManager.activeSessionIndex)
    SessionDialogs(
        pendingApprovals = state.pendingApprovals,
        pendingUserQuestions = state.pendingUserQuestions,
        showCompressConfirm = state.showCompressConfirm,
        onDismissCompressConfirm = { state.showCompressConfirm = false },
        onCompress = {
            activeSessionForDialogs?.let { session ->
                scope.launch {
                    performCompression(session)
                    session.isBusy = false
                }
            }
        },
        showDeleteConfirmIndex = state.showDeleteConfirmIndex,
        onDismissDeleteConfirm = { state.showDeleteConfirmIndex = -1 },
        onDeleteSession = { index ->
            state.showDeleteConfirmIndex = -1
            handleDeleteSession(index, sessionManager, scope) { error ->
                state.sessionLoadError = error
            }
        },
        showRenameDialogIndex = state.showRenameDialogIndex,
        renameText = state.renameText,
        onRenameTextChange = { state.renameText = it },
        renameSuggesting = state.renameSuggesting,
        onSuggestName = {
            val session = sessionManager.sessions.getOrNull(state.showRenameDialogIndex)
            handleSuggestName(session, { state.renameText = it }, { state.renameSuggesting = it }, scope)
        },
        onDismissRenameDialog = { state.showRenameDialogIndex = -1 },
        onRenameSession = { name ->
            val index = state.showRenameDialogIndex
            state.showRenameDialogIndex = -1
            scope.launch {
                try {
                    sessionManager.renameSession(index, name)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    state.sessionLoadError = SecretRedactor.redact(error.message ?: error.toString())
                }
            }
        },
        onCancelRenameDialog = {
            state.showRenameDialogIndex = -1
            state.renameSuggesting = false
        },
        sessionManager = sessionManager,
        contextWindowSize = activeSessionForDialogs?.runtime?.contextWindowSize ?: 1_000_000,
        sessionUsedTokens = activeSessionForDialogs?.usedTokens ?: 0,
    )
}
