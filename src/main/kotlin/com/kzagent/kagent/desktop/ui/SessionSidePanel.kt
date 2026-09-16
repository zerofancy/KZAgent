package com.kzagent.kagent.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.desktop.ApprovalModeMenu
import com.kzagent.kagent.desktop.TodoPanel
import com.kzagent.kagent.desktop.TodoPanelWidth
import com.kzagent.kagent.todo.TodoSnapshot
import com.kzagent.kagent.tools.ApprovalMode

internal val SessionSidePanelWidth = TodoPanelWidth

/**
 * 会话工具侧边栏：收纳审批模式、上下文与 Todo 进度，由顶部按钮控制显隐。
 */
@Composable
internal fun SessionSidePanel(
    approvalMode: ApprovalMode,
    onApprovalModeChanged: (ApprovalMode) -> Unit,
    todoSnapshot: TodoSnapshot,
    contextPercent: Int,
    isBusy: Boolean,
    onCompressContext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(SessionSidePanelWidth).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SidePanelCard("审批模式") {
            ApprovalModeMenu(
                approvalMode = approvalMode,
                onApprovalModeChanged = onApprovalModeChanged,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SidePanelCard("上下文") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LinearProgressIndicator(
                    progress = { contextPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.weight(1f),
                )
                Text("$contextPercent%", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onCompressContext,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (contextPercent > 80) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("压缩上下文")
            }
        }
        TodoPanel(
            snapshot = todoSnapshot,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun SidePanelCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}
