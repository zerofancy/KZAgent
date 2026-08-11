package com.kzagent.kagent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.todo.TodoSnapshot
import com.kzagent.kagent.tools.ApprovalMode
import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ModelSelection
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Text as FluentText
import java.nio.file.Path

@Composable
internal fun Header(
    workspace: Path,
    status: String,
    isBusy: Boolean,
    contextPercent: Int,
    approvalMode: ApprovalMode,
    modelSelection: ModelSelection,
    availableModels: List<ModelDescriptor>,
    modelsLoading: Boolean,
    modelsError: String?,
    todoSnapshot: TodoSnapshot,
    showTodoButton: Boolean,
    onShowTodo: () -> Unit,
    onApprovalModeChanged: (ApprovalMode) -> Unit,
    onModelChanged: (ModelSelection) -> Unit,
    onRefreshModels: () -> Unit,
    onCompressContext: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val useSingleRow = maxWidth >= 720.dp
        if (useSingleRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceIdentity(workspace = workspace, modifier = Modifier.weight(1f))
                StatusPill(status = status, isBusy = isBusy)
                HeaderActions(
                    modelSelection = modelSelection,
                    availableModels = availableModels,
                    modelsLoading = modelsLoading,
                    modelsError = modelsError,
                    approvalMode = approvalMode,
                    contextPercent = contextPercent,
                    isBusy = isBusy,
                    todoSnapshot = todoSnapshot,
                    showTodoButton = showTodoButton,
                    onShowTodo = onShowTodo,
                    onApprovalModeChanged = onApprovalModeChanged,
                    onModelChanged = onModelChanged,
                    onRefreshModels = onRefreshModels,
                    onCompressContext = onCompressContext,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorkspaceIdentity(workspace = workspace, modifier = Modifier.weight(1f))
                    StatusPill(status = status, isBusy = isBusy)
                }
                Spacer(Modifier.height(8.dp))
                ModelSelector(
                    selection = modelSelection,
                    models = availableModels,
                    loading = modelsLoading,
                    error = modelsError,
                    enabled = !isBusy,
                    onSelect = onModelChanged,
                    onRefresh = onRefreshModels,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                HeaderActions(
                    modelSelection = modelSelection,
                    availableModels = availableModels,
                    modelsLoading = modelsLoading,
                    modelsError = modelsError,
                    approvalMode = approvalMode,
                    contextPercent = contextPercent,
                    isBusy = isBusy,
                    todoSnapshot = todoSnapshot,
                    showTodoButton = showTodoButton,
                    onShowTodo = onShowTodo,
                    onApprovalModeChanged = onApprovalModeChanged,
                    onModelChanged = onModelChanged,
                    onRefreshModels = onRefreshModels,
                    onCompressContext = onCompressContext,
                    modifier = Modifier.align(Alignment.End),
                    showModelSelector = false,
                )
            }
        }
    }
}

@Composable
internal fun WorkspaceIdentity(workspace: Path, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            workspaceProjectName(workspace),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            workspace.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun HeaderActions(
    modelSelection: ModelSelection,
    availableModels: List<ModelDescriptor>,
    modelsLoading: Boolean,
    modelsError: String?,
    approvalMode: ApprovalMode,
    contextPercent: Int,
    isBusy: Boolean,
    todoSnapshot: TodoSnapshot,
    showTodoButton: Boolean,
    onShowTodo: () -> Unit,
    onApprovalModeChanged: (ApprovalMode) -> Unit,
    onModelChanged: (ModelSelection) -> Unit,
    onRefreshModels: () -> Unit,
    onCompressContext: () -> Unit,
    modifier: Modifier = Modifier,
    showModelSelector: Boolean = true,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showModelSelector) {
            ModelSelector(
                selection = modelSelection,
                models = availableModels,
                loading = modelsLoading,
                error = modelsError,
                enabled = !isBusy,
                onSelect = onModelChanged,
                onRefresh = onRefreshModels,
            )
        }
        ApprovalModeMenu(approvalMode, onApprovalModeChanged)
        if (showTodoButton) {
            OutlinedButton(
                onClick = onShowTodo,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(todoButtonLabel(todoSnapshot), maxLines = 1)
            }
        }
        Button(
            onClick = onCompressContext,
            enabled = !isBusy,
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (contextPercent > 80) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text("上下文 $contextPercent%", maxLines = 1)
        }
    }
}

@Composable
internal fun StatusPill(status: String, isBusy: Boolean) {
    Row(
        modifier = Modifier
            .widthIn(max = 180.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        } else {
            Box(Modifier.size(7.dp).background(Color(0xFF107C10), CircleShape))
        }
        Spacer(Modifier.width(7.dp))
        Text(status, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun workspaceProjectName(workspace: Path): String =
    workspace.toAbsolutePath().normalize().fileName?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: workspace.toAbsolutePath().normalize().toString()

internal fun approvalModeLabel(mode: ApprovalMode): String = when (mode) {
    ApprovalMode.AUTO -> "自动"
    ApprovalMode.MANUAL -> "手动"
    ApprovalMode.FULL -> "全部放行"
}

internal fun requiresFullModeConfirmation(current: ApprovalMode, target: ApprovalMode): Boolean =
    target == ApprovalMode.FULL && current != ApprovalMode.FULL

@Composable
internal fun ApprovalModeMenu(approvalMode: ApprovalMode, onApprovalModeChanged: (ApprovalMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirmFullMode by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) { Text("审批：${approvalModeLabel(approvalMode)} ▾", maxLines = 1) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ApprovalMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(if (mode == approvalMode) "✓ ${approvalModeLabel(mode)}" else approvalModeLabel(mode))
                            Text(
                                when (mode) {
                                    ApprovalMode.AUTO -> "静态规则、审批 Agent、必要时人工"
                                    ApprovalMode.MANUAL -> "所有受控操作逐次人工确认"
                                    ApprovalMode.FULL -> "命令和外部读取直接放行"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mode == ApprovalMode.FULL) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (requiresFullModeConfirmation(approvalMode, mode)) confirmFullMode = true
                        else onApprovalModeChanged(mode)
                    },
                )
            }
        }
    }
    if (confirmFullMode) {
        ContentDialog(
            title = "确认全部放行",
            visible = true,
            content = {
                FluentText("全部放行会以当前系统用户权限直接执行命令，并允许读取工作区外或敏感文件，不会调用审批 Agent 或弹出人工确认。")
            },
            primaryButtonText = "我了解风险，继续",
            closeButtonText = "取消",
            onButtonClick = { button ->
                confirmFullMode = false
                if (button == ContentDialogButton.Primary) onApprovalModeChanged(ApprovalMode.FULL)
            },
        )
    }
}

@Composable
internal fun ErrorBanner(message: String) {
    Box(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
