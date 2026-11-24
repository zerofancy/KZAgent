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
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import com.kzagent.kagent.desktop.SettingsPanel
import com.kzagent.kagent.desktop.McpPanel
import com.kzagent.kagent.desktop.SkillsPanel
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
    terminalManager: SessionTerminalManager,
    onSend: (SessionData, String) -> Unit,
) {
    if (state.showMcp) {
        val session = sessionManager.activeSessionOrNull()
        McpPanel(session?.workspace ?: state.draft.workspace, session?.runtime?.mcp, session?.isBusy == true)
    } else if (state.showSkills) {
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
                io.github.composefluent.component.Text("项目数据暂不可用，请查看上方错误并重试加载。")
            }
        }
    } else if (state.showNewSession || sessionManager.sessions.isEmpty()) {
        val selection = state.draft.model ?: settingsState.savedConfig?.defaultModel ?: ModelSelection(
            AppConfig.DEFAULT_PROVIDER_ID, AppConfig.DEFAULT_MODEL, AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE)
        val approval = state.draft.approval ?: settingsState.savedConfig?.approvalMode ?: AppConfig.DEFAULT_APPROVAL_MODE
        NewSessionScreen(
            draft = state.draft,
            projects = sessionManager.projects,
            selection = selection, approval = approval,
            models = settingsState.availableModels, modelsLoading = settingsState.modelsLoading,
            modelsError = settingsState.modelsError, configured = settingsState.savedConfig != null,
            ready = sessionManager.initialized && sessionManager.projectDataAvailable && !state.deletingSkill && !settingsState.settingsSaving,
            onRefreshModels = settingsState::refreshModels,
            onCreateProject = {
                if (!state.draft.submitting) state.projectDialog = ProjectDialogState.create(state.draft.pendingWorkspace ?: state.draft.workspace)
            },
            onSettings = { state.showSettings = true },
            onSend = {
                if (state.draft.beginSubmission()) {
                    val submittedDraft = state.draft
                    val prompt = submittedDraft.input.text.toString()
                    val submittedProject = com.kzagent.kagent.desktop.selectedDraftProject(submittedDraft, sessionManager.projects)
                    scope.launch {
                        try {
                            val snapshot = requireNotNull(submittedProject) { "请选择一个有效项目，并完成目录归属选择" }
                            val project = requireNotNull(sessionManager.projectById(snapshot.id)) { "项目已不存在，请重新选择" }
                            check(com.kzagent.kagent.desktop.projectWorkspaceKey(project.workspace) == com.kzagent.kagent.desktop.projectWorkspaceKey(snapshot.workspace)) { "项目路径已改变，请重新选择" }
                            withContext(Dispatchers.IO) { requireReadableWorkspace(project.workspace) }
                            settingsState.commitNewSessionPreferences(selection, approval)
                            val created = sessionManager.startNewSessionInProject(project.id, selection)
                            state.input = prompt
                            onSend(created, prompt)
                            state.showNewSession = false
                            state.draft = NewSessionDraft(project.workspace).also { it.selectProject(project) }
                            state.hasDraft = false
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { submittedDraft.error = SecretRedactor.redact(error.message ?: error.toString()) }
                        finally { submittedDraft.submitting = false }
                    }
                }
            },
        )
    } else {
        DesktopConversation(state, sessionManager, settingsState, terminalManager, onSend)
    }
}
