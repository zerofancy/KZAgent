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
import androidx.compose.ui.text.style.TextOverflow
import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.tools.ApprovalMode
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.*
import java.nio.file.Path

/** A process-local draft, deliberately independent of persisted SessionData. */
internal class NewSessionDraft(val initialWorkspace: Path) {
    var workspace by mutableStateOf(initialWorkspace)
    var projectId by mutableStateOf<String?>(null)
    var pendingWorkspace by mutableStateOf<Path?>(initialWorkspace)

    fun selectProject(project: Project) {
        projectId = project.id
        workspace = project.workspace
        pendingWorkspace = null
        error = null
    }

    fun resolveWorkspace(workspace: Path, projects: List<Project>) {
        val normalized = workspace.toAbsolutePath().normalize()
        error = null
        val matches = projects.filter { projectWorkspaceKey(it.workspace) == projectWorkspaceKey(normalized) }
        if (matches.size == 1) selectProject(matches.single()) else {
            projectId = null
            this.workspace = normalized
            pendingWorkspace = normalized
        }
    }

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
    projects: List<Project>,
    selection: ModelSelection,
    approval: ApprovalMode,
    models: List<ModelDescriptor>,
    modelsLoading: Boolean,
    modelsError: String?,
    configured: Boolean,
    ready: Boolean,
    onRefreshModels: () -> Unit,
    onCreateProject: () -> Unit,
    onSettings: () -> Unit,
    onSend: () -> Unit,
) {
    var projectDialog by remember { mutableStateOf(false) }
    var approvalDialog by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val inputScroll = rememberScrollState()
    val isMac = remember { System.getProperty("os.name").lowercase().contains("mac") }
    val enabled = !draft.submitting
    val selectedProject = selectedDraftProject(draft, projects)
    val canSend = enabled && ready && configured && selectedProject != null && draft.input.text.isNotBlank()
    val pendingMatches = draft.pendingWorkspace?.let { pending ->
        projects.filter { projectWorkspaceKey(it.workspace) == projectWorkspaceKey(pending) }
    }.orEmpty()
    val inputFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { inputFocus.requestFocus() }
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxWidth().align(Alignment.TopCenter)
            .verticalScroll(scroll).padding(end = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("开始新会话", style = FluentTheme.typography.title)
            Text("选择项目和模型，描述你想完成的任务。", color = FluentTheme.colors.text.text.secondary)
            Text("项目", style = FluentTheme.typography.bodyStrong)
            Button(onClick = { projectDialog = true }, disabled = !enabled, modifier = Modifier.fillMaxWidth()) {
                Text(selectedProject?.let { projectChoiceLabel(it, projects) } ?: "选择项目…",
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            selectedProject?.let { project ->
                WorkspaceIdentity(workspace = project.workspace, projectName = project.name)
            }
            if (selectedProject == null) {
                Text(when {
                    draft.projectId != null -> "所选项目已删除或不可用，请重新选择项目。"
                    draft.pendingWorkspace != null && pendingMatches.size > 1 -> "启动目录关联多个项目，请明确选择一个项目。"
                    draft.pendingWorkspace != null && pendingMatches.isEmpty() -> "启动目录尚未关联项目，请新建项目或选择已有项目。"
                    projects.isEmpty() -> "请先新建项目，再发送任务。"
                    else -> "请选择项目后发送任务。"
                }, color = FluentTheme.colors.text.text.secondary)
                draft.pendingWorkspace?.let { Text("待选择目录：$it", color = FluentTheme.colors.text.text.secondary) }
            }
            Button(onClick = onCreateProject, disabled = !enabled) { Text("新建项目…") }
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
                            ComposerKeyAction.Send -> { if (canSend) onSend(); true }
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
            AccentButton(onClick = onSend, disabled = !canSend) {
                Text(if (draft.submitting) "正在创建…" else "发送 ↑")
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
    if (projectDialog) {
        val projectScroll = rememberScrollState()
        ContentDialog(title = "选择项目", visible = true, content = {
            Box(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(projectScroll).padding(end = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.pendingWorkspace?.let { pending ->
                        Text("待选择目录：$pending", color = FluentTheme.colors.text.text.secondary)
                        Text(when (pendingMatches.size) {
                            0 -> "此目录尚无项目。可新建项目，也可选择下方其他项目。"
                            1 -> "此目录对应以下标注的候选项目，也可选择其他项目。"
                            else -> "此目录对应多个项目，请明确选择；这些项目共享目录中的文件。"
                        })
                    }
                    if (projects.isEmpty()) Text("暂无项目，请先新建项目。")
                    projects.forEach { project ->
                        Button(onClick = {
                            draft.selectProject(project)
                            draft.error = null
                            projectDialog = false
                        }, disabled = !enabled, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                WorkspaceIdentity(project.workspace, projectName = projectChoiceLabel(project, projects))
                                if (pendingMatches.any { it.id == project.id }) Text("启动目录候选项目",
                                    style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary)
                            }
                        }
                    }
                    Button(onClick = { projectDialog = false; onCreateProject() }, disabled = !enabled) { Text("新建项目…") }
                }
                VerticalScrollbar(rememberScrollbarAdapter(projectScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }, primaryButtonText = "完成", closeButtonText = "关闭", onButtonClick = { projectDialog = false })
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

internal fun selectedDraftProject(draft: NewSessionDraft, projects: List<Project>): Project? =
    projects.singleOrNull { it.id == draft.projectId }?.takeIf {
        draft.pendingWorkspace == null && projectWorkspaceKey(it.workspace) == projectWorkspaceKey(draft.workspace)
    }

/** The ID suffix only disambiguates projects whose names and normalized paths both coincide. */
internal fun projectChoiceLabel(project: Project, projects: List<Project>): String {
    val duplicates = projects.filter {
        it.name == project.name && projectWorkspaceKey(it.workspace) == projectWorkspaceKey(project.workspace)
    }
    if (duplicates.size <= 1) return project.name
    // Extend a colliding short prefix rather than presenting indistinguishable candidates.
    val prefixLength = (minOf(8, project.id.length)..project.id.length).firstOrNull { length ->
        duplicates.none { it.id != project.id && it.id.take(length) == project.id.take(length) }
    } ?: project.id.length
    return "${project.name} · ${project.id.take(prefixLength)}"
}
