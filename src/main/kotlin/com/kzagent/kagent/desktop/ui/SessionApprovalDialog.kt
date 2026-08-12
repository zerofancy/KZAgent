package com.kzagent.kagent.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.tools.ApprovalRequest
import com.kzagent.kagent.tools.actionLabel
import com.kzagent.kagent.tools.details
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.DialogSize
import io.github.composefluent.component.Text as FluentText

internal enum class ApprovalKeyAction { Approve, Deny, Consume, PassThrough }

internal fun resolveApprovalKeyAction(isEnter: Boolean, isEscape: Boolean, eventType: KeyEventType,
    highRisk: Boolean): ApprovalKeyAction = when {
    !isEnter && !isEscape -> ApprovalKeyAction.PassThrough
    eventType != KeyEventType.KeyUp -> ApprovalKeyAction.Consume
    isEscape -> ApprovalKeyAction.Deny
    highRisk -> ApprovalKeyAction.Consume
    else -> ApprovalKeyAction.Approve
}

@Composable
internal fun ApprovalDialog(approval: PendingApproval) {
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    ContentDialog(
        title = if (approval.highRisk) "高风险操作审批" else approval.request.actionLabel(), visible = true,
        content = {
            // ContentDialog mounts its content in a separate overlay composition. Request focus
            // from that composition so the requester is already attached to the focusable node.
            LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
            Box(Modifier.focusRequester(focusRequester).focusable().fillMaxWidth().heightIn(max = 320.dp)
                .onKeyEvent { event ->
                    when (resolveApprovalKeyAction(event.key == Key.Enter, event.key == Key.Escape,
                        event.type, approval.highRisk)) {
                        ApprovalKeyAction.Approve -> { approval.complete(true); true }
                        ApprovalKeyAction.Deny -> { approval.complete(false); true }
                        ApprovalKeyAction.Consume -> true
                        ApprovalKeyAction.PassThrough -> false
                    }
                }) {
                Column(Modifier.fillMaxWidth().verticalScroll(scrollState).padding(end = 12.dp)) {
                    if (approval.highRisk) {
                        FluentText("此操作可能产生高风险影响，请确认后再继续。",
                            color = FluentTheme.colors.system.critical, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                    }
                    FluentText(approval.request.actionLabel(), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        FluentText(buildString {
                            append(approval.request.details())
                            if (approval.request.risk.isHighRisk) {
                                appendLine(); appendLine()
                                append("风险：${approval.request.risk.reasons.joinToString("；")}")
                            }
                        }, style = FluentTheme.typography.body)
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scrollState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        },
        primaryButtonText = if (approval.highRisk) "仍然执行" else "允许", closeButtonText = "拒绝",
        onButtonClick = { approval.complete(it == ContentDialogButton.Primary) }, size = DialogSize.Max,
    )
}

internal data class PendingApproval(val request: ApprovalRequest, val complete: (Boolean) -> Unit) {
    val highRisk: Boolean get() = request.risk.isHighRisk
}
