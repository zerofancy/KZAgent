package com.kzagent.kagent.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.ApprovalDialog
import com.kzagent.kagent.desktop.PendingApproval
import com.kzagent.kagent.desktop.PendingUserQuestions
import com.kzagent.kagent.desktop.SessionData
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.desktop.UserQuestionDialog
import com.kzagent.kagent.llm.AgentMessage
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Button as FluentButton
import io.github.composefluent.component.Text as FluentText
import io.github.composefluent.component.TextField as FluentTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Renders all session-related dialogs: approval, user questions, compress
 * confirmation, delete confirmation, and rename.
 *
 * All mutable state is owned by the caller; this composable is purely presentational
 * with callback-based interactions.
 */
@Composable
internal fun SessionDialogs(
    pendingApprovals: List<PendingApproval>,
    pendingUserQuestions: List<PendingUserQuestions>,
    showCompressConfirm: Boolean,
    onDismissCompressConfirm: () -> Unit,
    onCompress: () -> Unit,
    showDeleteConfirmIndex: Int,
    onDismissDeleteConfirm: () -> Unit,
    onDeleteSession: (Int) -> Unit,
    showRenameDialogIndex: Int,
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    renameSuggesting: Boolean,
    onSuggestName: () -> Unit,
    onDismissRenameDialog: () -> Unit,
    onRenameSession: (name: String) -> Unit,
    onCancelRenameDialog: () -> Unit,
    sessionManager: SessionManager,
    contextWindowSize: Int,
    sessionUsedTokens: Int,
) {
    // --- Approval ---
    pendingApprovals.firstOrNull()?.let { ApprovalDialog(it) }

    // --- User questions ---
    pendingUserQuestions.firstOrNull()?.let { UserQuestionDialog(it) }

    // --- Compress confirmation ---
    if (showCompressConfirm) {
        val ctxPct = (sessionUsedTokens.toLong() * 100) / contextWindowSize.coerceAtLeast(1).toLong()
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
                onDismissCompressConfirm()
                if (button == ContentDialogButton.Primary) {
                    onCompress()
                }
            },
        )
    }

    // --- Delete confirmation ---
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
                    onDeleteSession(showDeleteConfirmIndex)
                } else {
                    onDismissDeleteConfirm()
                }
            },
        )
    }

    // --- Rename ---
    if (showRenameDialogIndex >= 0) {
        ContentDialog(
            title = "重命名会话",
            visible = true,
            content = {
                Column {
                    FluentTextField(
                        value = renameText,
                        onValueChange = onRenameTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        header = { FluentText("会话名称") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    FluentButton(
                        onClick = onSuggestName,
                        disabled = renameSuggesting,
                    ) {
                        if (renameSuggesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp).align(Alignment.CenterVertically),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        FluentText(if (renameSuggesting) "推荐中..." else "推荐名称")
                    }
                }
            },
            primaryButtonText = "确定",
            closeButtonText = "取消",
            onButtonClick = { button ->
                if (button == ContentDialogButton.Primary) {
                    confirmSessionRename(renameText, onRenameSession, onDismissRenameDialog)
                } else {
                    onCancelRenameDialog()
                }
            },
        )
    }
}

internal fun confirmSessionRename(
    name: String,
    onRenameSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 关闭弹窗会清空宿主中的目标索引，必须先让重命名回调捕获它。
    if (name.isNotBlank()) onRenameSession(name)
    onDismiss()
}

/**
 * Orchestrates the rename-suggest logic: collects recent user messages,
 * asks the agent to generate a title, and updates [renameText] via
 * [onRenameTextChange]. Sets [onSuggestingChange] to track loading state.
 */
internal fun handleSuggestName(
    session: SessionData?,
    onRenameTextChange: (String) -> Unit,
    onSuggestingChange: (Boolean) -> Unit,
    scope: CoroutineScope,
) {
    val agent = session?.runtime?.agent ?: return
    onSuggestingChange(true)
    scope.launch {
        try {
            val recentText = session.conversationHistory
                .filterIsInstance<AgentMessage.User>()
                .takeLast(4)
                .joinToString("\n") { it.content.take(200) }
            if (recentText.isNotBlank()) {
                onRenameTextChange(agent.generateTitle(recentText))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Best-effort
        } finally {
            onSuggestingChange(false)
        }
    }
}

/**
 * Orchestrates delete-session logic with proper error handling.
 */
internal fun handleDeleteSession(
    index: Int,
    sessionManager: SessionManager,
    scope: CoroutineScope,
    onError: (String) -> Unit,
) {
    scope.launch {
        try {
            sessionManager.deleteSession(index)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(SecretRedactor.redact(error.message ?: error.toString()))
        }
    }
}
