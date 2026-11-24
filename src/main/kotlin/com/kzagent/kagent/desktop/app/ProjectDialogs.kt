package com.kzagent.kagent.desktop.app

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.*
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ProjectDialogMode { Create, Rename, Delete }

internal class ProjectDialogState private constructor(
    val mode: ProjectDialogMode,
    val projectId: String?,
    workspace: Path,
    name: String,
) {
    var workspace by mutableStateOf(workspace)
    var name by mutableStateOf(name)
    var nameEdited by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    companion object {
        fun create(workspace: Path) = ProjectDialogState(ProjectDialogMode.Create, null, workspace, projectDefaultName(workspace))
        fun rename(project: Project) = ProjectDialogState(ProjectDialogMode.Rename, project.id, project.workspace, project.name)
        fun delete(project: Project) = ProjectDialogState(ProjectDialogMode.Delete, project.id, project.workspace, project.name)
    }
}

@Composable
internal fun ProjectDialogs(state: DesktopSessionUiState, manager: SessionManager, scope: CoroutineScope) {
    val dialog = state.projectDialog ?: return
    val scroll = rememberScrollState()
    ContentDialog(
        title = when (dialog.mode) {
            ProjectDialogMode.Create -> "新建项目"
            ProjectDialogMode.Rename -> "重命名项目"
            ProjectDialogMode.Delete -> "删除空项目"
        },
        visible = true,
        content = {
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dialog.mode == ProjectDialogMode.Delete) {
                        Text("确定删除项目「${dialog.name}」吗？工作区目录和文件不会被删除。")
                    } else {
                        TextField(value = dialog.name, onValueChange = {
                            if (!dialog.busy) { dialog.name = it; dialog.nameEdited = true; dialog.error = null }
                        }, header = { Text("项目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Text("工作区：${dialog.workspace}", color = FluentTheme.colors.text.text.secondary)
                    if (dialog.mode == ProjectDialogMode.Create) {
                        Button(disabled = dialog.busy, onClick = {
                            if (!dialog.busy) {
                                dialog.busy = true
                                scope.launch {
                                    try {
                                        chooseWorkspace(dialog.workspace)?.let { path ->
                                            dialog.workspace = withContext(Dispatchers.IO) { requireReadableWorkspace(path) }
                                            if (!dialog.nameEdited) dialog.name = projectDefaultName(dialog.workspace)
                                            dialog.error = null
                                        }
                                    } catch (error: CancellationException) { throw error }
                                    catch (error: Exception) { dialog.error = SecretRedactor.redact(error.message ?: error.toString()) }
                                    finally { dialog.busy = false }
                                }
                            }
                        }) { Text("选择文件夹…") }
                        if (manager.projects.any { projectWorkspaceKey(it.workspace) == projectWorkspaceKey(dialog.workspace) }) {
                            Text("此目录已关联其他项目，文件将共享。", color = FluentTheme.colors.text.text.secondary)
                        }
                    }
                    if (dialog.busy) Text("正在处理…")
                    dialog.error?.let { Text(it, color = FluentTheme.colors.system.critical) }
                }
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        },
        primaryButtonText = when (dialog.mode) {
            ProjectDialogMode.Create -> "创建"
            ProjectDialogMode.Rename -> "保存"
            ProjectDialogMode.Delete -> "删除"
        },
        closeButtonText = "取消",
        onButtonClick = { button ->
            if (!dialog.busy) {
                if (button != ContentDialogButton.Primary) state.projectDialog = null
                else {
                    // Lock synchronously, before launching: double click must not create two projects.
                    dialog.busy = true
                    scope.launch {
                        try {
                            when (dialog.mode) {
                                ProjectDialogMode.Create -> {
                                    val project = manager.createProject(dialog.name, dialog.workspace)
                                    state.draft.selectProject(project)
                                    state.hasDraft = true
                                    state.showNewSession = true
                                    state.showSettings = false; state.showSkills = false; state.showMcp = false
                                    try { manager.setProjectExpanded(project.id, true) }
                                    catch (error: CancellationException) { throw error }
                                    catch (error: Exception) { state.projectActionError = SecretRedactor.redact(error.message ?: error.toString()) }
                                }
                                ProjectDialogMode.Rename -> manager.renameProject(requireNotNull(dialog.projectId), dialog.name)
                                ProjectDialogMode.Delete -> {
                                    manager.deleteProject(requireNotNull(dialog.projectId))
                                    if (state.draft.projectId == dialog.projectId) {
                                        state.draft.projectId = null
                                        state.draft.pendingWorkspace = null
                                        state.draft.error = "所选项目已删除，请重新选择项目"
                                    }
                                }
                            }
                            state.projectDialog = null
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { dialog.error = SecretRedactor.redact(error.message ?: error.toString()) }
                        finally { dialog.busy = false }
                    }
                }
            }
        },
    )
}
