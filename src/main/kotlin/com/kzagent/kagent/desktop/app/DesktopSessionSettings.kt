package com.kzagent.kagent.desktop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.AppConfigLoader
import com.kzagent.kagent.config.ConfigWriter
import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.SessionData
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.desktop.UserCommandAvailability
import com.kzagent.kagent.desktop.UserCommandInstaller
import com.kzagent.kagent.llm.ModelCatalogService
import com.kzagent.kagent.tools.ApprovalMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encapsulates settings-related state and callbacks extracted from [KZAgentDesktopApp].
 *
 * @param sessionManager shared session manager; owned by the caller
 * @param scope coroutine scope for launching async work; owned by the caller
 * @param onDismiss called when settings should be hidden (e.g. after successful save)
 * @param onSessionError called when a session-level error message should be set
 */
internal class DesktopSessionSettingsState(
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope,
    private val onDismiss: () -> Unit,
    private val onSessionError: (String) -> Unit,
) {
    var savedConfig by mutableStateOf<AppConfig?>(null)
    var configLoaded by mutableStateOf(false)
    val availableModels = mutableStateListOf<ModelDescriptor>()
    var modelsLoading by mutableStateOf(false)
    var modelsError by mutableStateOf<String?>(null)
    var modelCatalogJob by mutableStateOf<Job?>(null)
    var settingsSaving by mutableStateOf(false)
    var settingsSaveError by mutableStateOf<String?>(null)
    var commandAvailability by mutableStateOf<UserCommandAvailability?>(null)
    var commandInstalling by mutableStateOf(false)
    var commandInstallMessage by mutableStateOf<String?>(null)
    var commandInstallFailed by mutableStateOf(false)

    private val userCommandInstaller = UserCommandInstaller()
    private val modelCatalogService = ModelCatalogService()

    internal suspend fun loadInitialConfig(
        onConfigurationRequired: () -> Unit,
        loadConfig: suspend () -> AppConfig = { withContext(Dispatchers.IO) { AppConfigLoader.load() } },
    ) {
        try {
            savedConfig = loadConfig()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            savedConfig = null
            onConfigurationRequired()
        } finally {
            configLoaded = true
        }
    }

    /** Load available models from all configured providers. */
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
                        errors += "${provider.name}: ${SecretRedactor.redact(error.message ?: error.toString())}"
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

    /**
     * Persist configuration, reload it, then invalidate stale runtimes
     * and migrate sessions whose provider was removed.
     */
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
                sessionManager.sessions
                    .filter { savedConfig!!.provider(it.modelSelection.provider) == null }
                    .forEach { session -> sessionManager.updateModel(session, savedConfig!!.defaultModel) }
                onDismiss()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                settingsSaveError = SecretRedactor.redact(error.message ?: error.toString())
                onSessionError("保存设置失败：$settingsSaveError")
            } finally {
                settingsSaving = false
            }
        }
    }

    /** Update the app-wide approval mode and persist the change. */
    fun onApprovalModeChanged(mode: ApprovalMode) {
        val current = savedConfig ?: return
        if (current.approvalMode != mode) saveSettings(current.copy(approvalMode = mode))
    }

    /** Switch [session]'s model, persist as default, and update the config. */
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

    /** Install the user CLI command and refresh availability state. */
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

    suspend fun checkCommandAvailability(): UserCommandAvailability? {
        return withContext(Dispatchers.IO) { userCommandInstaller.availability() }
    }

    fun close() {
        modelCatalogService.close()
    }
}

/**
 * Create and remember a [DesktopSessionSettingsState] instance, wiring its
 * lifecycle to the composition.
 */
@Composable
internal fun rememberDesktopSessionSettingsState(
    sessionManager: SessionManager,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onConfigurationRequired: () -> Unit,
    onSessionError: (String) -> Unit,
): DesktopSessionSettingsState {
    val state = remember {
        DesktopSessionSettingsState(sessionManager, scope, onDismiss, onSessionError)
    }
    // Load configuration on startup; if API key is missing, open settings
    LaunchedEffect(Unit) {
        state.loadInitialConfig(onConfigurationRequired)
    }
    // Check user command availability
    LaunchedEffect(state) {
        state.commandAvailability = state.checkCommandAvailability()
    }
    // Refresh models when providers change
    LaunchedEffect(state.savedConfig?.providers) {
        if (state.savedConfig != null) state.refreshModels()
    }
    DisposableEffect(state) {
        onDispose { state.close() }
    }
    return state
}
