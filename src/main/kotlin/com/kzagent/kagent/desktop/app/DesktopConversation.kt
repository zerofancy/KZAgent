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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.Composer
import com.kzagent.kagent.desktop.DisplayMessage
import com.kzagent.kagent.desktop.ErrorBanner
import com.kzagent.kagent.desktop.Header
import com.kzagent.kagent.desktop.MessageList
import com.kzagent.kagent.desktop.SessionData
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.desktop.ui.SessionSidePanel
import com.kzagent.kagent.llm.AgentMessage
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal fun sendDesktopSessionMessage(
    state: DesktopSessionUiState,
    sessionManager: SessionManager,
    settingsState: DesktopSessionSettingsState,
    scope: CoroutineScope,
    session: SessionData,
    text: String,
) {
    val submittedInput = text
    val prompt = text.trim()
    if (prompt.isEmpty()) return
    if (session.isBusy || state.deletingSkill) return
    session.isBusy = true
    session.error = null
    session.status = "准备发送..."
    val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            sessionManager.ensureRuntime(session,
                createAgentObserver(session, settingsState.savedConfig?.approvalMode), refreshSkills = true)
            val currentRuntime = requireNotNull(session.runtime)
            if (state.input == submittedInput) state.input = ""
            session.messages.add(DisplayMessage("user", prompt, timestampMillis = Instant.now().toEpochMilli()))
            val sessionId = session.id
            val titleRevision = session.titleRevision
            if (session.conversationHistory.none { it is AgentMessage.User }) {
                session.titleJob = scope.launch {
                    try {
                        val title = currentRuntime.agent.generateTitle(prompt)
                        sessionManager.renameSessionIfRevisionMatches(sessionId, titleRevision, title)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Title generation must not fail the conversation. */ }
                }
            }
            // Disk includes completed tools from a previous failed turn;
            // the in-memory history is only updated after a successful run.
            session.reloadSavedHistory()
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
            desktopLog("Conversation failed for session ${session.id}", e)
            session.error =
                SecretRedactor.redact(e.message ?: e.toString())
            session.status = "请求失败"
        } finally {
            session.isBusy = false
            session.currentJob = null
        }
    }
    session.currentJob = job
    job.start()
}

@Composable
internal fun DesktopConversation(
    state: DesktopSessionUiState,
    sessionManager: SessionManager,
    settingsState: DesktopSessionSettingsState,
    onSend: (SessionData, String) -> Unit,
) {
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
                sidePanelVisible = state.showSessionSidePanel,
                onToggleSidePanel = { state.showSessionSidePanel = !state.showSessionSidePanel },
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
                        input = state.input,
                        isBusy = session.isBusy,
                        enabled = !state.deletingSkill,
                        onInputChange = { state.input = it },
                        onSend = { onSend(session, state.input) },
                        onTerminate = {
                            session.currentJob?.cancel()
                            session.status = "正在终止..."
                        },
                    )
                }
                if (state.showSessionSidePanel) {
                    SessionSidePanel(
                        approvalMode = settingsState.savedConfig?.approvalMode
                            ?: AppConfig.DEFAULT_APPROVAL_MODE,
                        onApprovalModeChanged = { settingsState.onApprovalModeChanged(it) },
                        todoSnapshot = session.todoSnapshot,
                        contextPercent = (session.usedTokens * 100) / (session.runtime?.contextWindowSize
                            ?: 1_000_000),
                        isBusy = session.isBusy,
                        onCompressContext = { state.showCompressConfirm = true },
                    )
                }
            }
        }
    }
}
