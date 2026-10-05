package com.kzagent.kagent.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.ErrorBanner
import com.kzagent.kagent.desktop.NewSessionDraft
import com.kzagent.kagent.desktop.NewSessionScreen
import com.kzagent.kagent.desktop.SessionData
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.desktop.SettingsPanel
import com.kzagent.kagent.desktop.SkillsPanel
import com.kzagent.kagent.desktop.chooseWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopSessionPane(
    state: DesktopSessionUiState,
    sessionManager: SessionManager,
    settingsState: DesktopSessionSettingsState,
    scope: CoroutineScope,
    onSend: (SessionData, String) -> Unit,
) {
    if (state.showSkills) {
        SkillsPanel(
            config = settingsState.savedConfig?.skills ?: com.kzagent.kagent.config.SkillsConfig(),
            configured = settingsState.savedConfig != null,
            busy = sessionManager.sessions.any { it.isBusy },
            save = settingsState::saveSkills,
            onRefresh = sessionManager::markSkillsChanged,
            onDeletingChanged = { state.deletingSkill = it },
        )
    } else if (state.showSettings) {
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
                    state.showSettings = false; state.showSkills = false
                }
            },
        )
    } else if (!sessionManager.initialized) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.sessionLoadError == null) {
                CircularProgressIndicator()
            } else {
                ErrorBanner(state.sessionLoadError!!)
            }
        }
    } else if (state.showNewSession || sessionManager.sessions.isEmpty()) {
        val selection = state.draft.model ?: settingsState.savedConfig?.defaultModel ?: ModelSelection(
            AppConfig.DEFAULT_PROVIDER_ID, AppConfig.DEFAULT_MODEL, AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE)
        val approval = state.draft.approval ?: settingsState.savedConfig?.approvalMode ?: AppConfig.DEFAULT_APPROVAL_MODE
        NewSessionScreen(
            draft = state.draft,
            workspaces = sessionManager.sessions.map { it.workspace },
            selection = selection, approval = approval,
            models = settingsState.availableModels, modelsLoading = settingsState.modelsLoading,
            modelsError = settingsState.modelsError, configured = settingsState.savedConfig != null,
            ready = sessionManager.initialized && !state.deletingSkill && !settingsState.settingsSaving,
            onRefreshModels = settingsState::refreshModels,
            onChooseWorkspace = {
                if (!state.draft.submitting) scope.launch {
                    try {
                        chooseWorkspace(state.draft.workspace)?.let { path ->
                            state.draft.workspace = withContext(Dispatchers.IO) { requireReadableWorkspace(path) }
                            state.draft.error = null
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { state.draft.error = SecretRedactor.redact(error.message ?: error.toString()) }
                }
            },
            onSettings = { state.showSettings = true },
            onSend = {
                if (state.draft.beginSubmission()) {
                    val submittedDraft = state.draft
                    val prompt = submittedDraft.input.text.toString()
                    val workspace = submittedDraft.workspace
                    scope.launch {
                        try {
                            val validated = withContext(Dispatchers.IO) { requireReadableWorkspace(workspace) }
                            settingsState.commitNewSessionPreferences(selection, approval)
                            val created = sessionManager.startNewSessionInWorkspace(validated, selection)
                            state.input = prompt
                            onSend(created, prompt)
                            state.showNewSession = false
                            state.draft = NewSessionDraft(validated)
                            state.hasDraft = false
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { submittedDraft.error = SecretRedactor.redact(error.message ?: error.toString()) }
                        finally { submittedDraft.submitting = false }
                    }
                }
            },
        )
    } else {
        DesktopConversation(state, sessionManager, settingsState, onSend)
    }
}
