package com.kzagent.kagent.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.tools.ApprovalMode
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.*
import java.nio.file.Path

/** A process-local draft, deliberately independent of persisted SessionData. */
internal class NewSessionDraft(val initialWorkspace: Path) {
    var workspace by mutableStateOf(initialWorkspace)
    var model by mutableStateOf<ModelSelection?>(null)
    var approval by mutableStateOf<ApprovalMode?>(null)
    val input = TextFieldState()
    var submitting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun beginSubmission(): Boolean {
        if (submitting || input.text.isBlank()) return false
        submitting = true
        error = null
        return true
    }
}

@Composable
internal fun NewSessionScreen(
    draft: NewSessionDraft,
    workspaces: List<Path>,
    selection: ModelSelection,
    approval: ApprovalMode,
    models: List<ModelDescriptor>,
    modelsLoading: Boolean,
    modelsError: String?,
    configured: Boolean,
    ready: Boolean,
    onRefreshModels: () -> Unit,
    onChooseWorkspace: () -> Unit,
    onSettings: () -> Unit,
    onSend: () -> Unit,
) {
    var workspaceDialog by remember { mutableStateOf(false) }
    var approvalDialog by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val inputScroll = rememberScrollState()
    val isMac = remember { System.getProperty("os.name").lowercase().contains("mac") }
    val enabled = !draft.submitting
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { inputFocus.requestFocus() }
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxWidth().align(Alignment.TopCenter)
            .verticalScroll(scroll).padding(end = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("开始新会话", style = FluentTheme.typography.title)
            Text("选择工作区和模型，描述你想完成的任务。", color = FluentTheme.colors.text.text.secondary)
            Text("工作区", style = FluentTheme.typography.bodyStrong)
            Button(onClick = { workspaceDialog = true }, disabled = !enabled, modifier = Modifier.fillMaxWidth()) {
                Text(draft.workspace.toString())
            }
            Button(onClick = onChooseWorkspace, disabled = !enabled) { Text("打开文件夹…") }
            Text("模型", style = FluentTheme.typography.bodyStrong)
            ModelSelector(selection, models, modelsLoading, modelsError, enabled && configured,
                { draft.model = it; draft.error = null }, onRefreshModels, Modifier.fillMaxWidth())
            Text("审批方式", style = FluentTheme.typography.bodyStrong)
            Button(onClick = { approvalDialog = true }, disabled = !enabled) { Text(approvalModeLabel(approval)) }
            Text("全局设置，提交后对所有会话生效。", color = FluentTheme.colors.text.text.secondary)
            if (!configured) {
                Text("请先配置 Provider 和模型。")
                Button(onClick = onSettings, disabled = !enabled) { Text("打开设置") }
            }
            Text("任务", style = FluentTheme.typography.bodyStrong)
            Box(Modifier.fillMaxWidth()) {
                TextField(state = draft.input, enabled = enabled, isClearable = false,
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 10),
                    scrollState = inputScroll, placeholder = { Text("向 KZAgent 描述任务…") },
                    modifier = Modifier.fillMaxWidth().padding(end = 14.dp).focusRequester(inputFocus).onPreviewKeyEvent { event ->
                        // Let the IME consume Enter when a candidate is being composed.
                        if (draft.input.composition != null) return@onPreviewKeyEvent false
                        when (resolveComposerKeyAction(event.key == Key.Enter, event.isCtrlPressed,
                            event.isMetaPressed, isMac, event.type, false)) {
                            ComposerKeyAction.Send -> { if (enabled && ready && configured) onSend(); true }
                            ComposerKeyAction.InsertLineBreak -> {
                                if (enabled) draft.input.edit {
                                    val start = this.selection.min
                                    replace(start, this.selection.max, "\n")
                                    this.selection = androidx.compose.ui.text.TextRange(start + 1)
                                }
                                true
                            }
                            ComposerKeyAction.Consume -> true
                            else -> false
                        }
                    })
                // The input lives inside a vertically scrolling column. Measure the field
                // first, then constrain its scrollbar to the resulting height.
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbar(rememberScrollbarAdapter(inputScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
            draft.error?.let { Text(it, color = FluentTheme.colors.system.critical) }
            Text(if (isMac) "Enter 发送 · Command+Enter 换行" else "Enter 发送 · Ctrl+Enter 换行",
                color = FluentTheme.colors.text.text.secondary)
            AccentButton(onClick = onSend, disabled = !enabled || !ready || !configured || draft.input.text.isBlank()) {
                Text(if (draft.submitting) "正在创建…" else "发送 ↑")
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    if (workspaceDialog) {
        val directoryScroll = rememberScrollState()
        ContentDialog(title = "选择已有工作区", visible = true, content = {
            Box(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(directoryScroll).padding(end = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(draft.workspace) + workspaces).map { it.toAbsolutePath().normalize() }.distinct().forEach { path ->
                        Button(onClick = { draft.workspace = path; draft.error = null; workspaceDialog = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(path.toString())
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(directoryScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }, primaryButtonText = "完成", closeButtonText = "关闭", onButtonClick = { workspaceDialog = false })
    }
    if (approvalDialog) ContentDialog(title = "选择审批方式", visible = true, content = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ApprovalMode.entries.forEach { mode ->
                Button(onClick = {
                    approvalDialog = false
                    if (requiresFullModeConfirmation(approval, mode)) confirmFull = true else draft.approval = mode
                }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(approvalModeLabel(mode))
                        Text(when (mode) {
                            ApprovalMode.AUTO -> "静态规则、审批 Agent、必要时人工"
                            ApprovalMode.MANUAL -> "所有受控操作逐次人工确认"
                            ApprovalMode.FULL -> "命令和外部读取直接放行"
                        }, color = if (mode == ApprovalMode.FULL) FluentTheme.colors.system.critical
                            else FluentTheme.colors.text.text.secondary)
                    }
                }
            }
        }
    }, primaryButtonText = "完成", closeButtonText = "关闭", onButtonClick = { approvalDialog = false })
    if (confirmFull) ContentDialog(title = "确认全部放行", visible = true,
        content = { Text("全部放行会以当前系统用户权限直接执行命令，并允许读取工作区外或敏感文件，不会调用审批 Agent 或弹出人工确认。") },
        primaryButtonText = "我了解风险，继续", closeButtonText = "取消", onButtonClick = {
            confirmFull = false
            if (it == ContentDialogButton.Primary) draft.approval = ApprovalMode.FULL
        })
}
