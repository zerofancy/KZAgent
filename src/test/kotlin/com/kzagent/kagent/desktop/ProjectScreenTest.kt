package com.kzagent.kagent.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.use
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.desktop.app.DesktopSessionUiState
import com.kzagent.kagent.desktop.app.ProjectDialogState
import com.kzagent.kagent.desktop.app.ProjectDialogs
import com.kzagent.kagent.tools.AlwaysApprovePolicy
import com.kzagent.kagent.tools.ApprovalMode
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.darkColors
import io.github.composefluent.lightColors
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Fluent popups and their semantic callbacks, without a runtime or network client. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFluentApi::class)
class ProjectScreenTest {
    private val selection = ModelSelection("test-provider", "test-model", 100_000)
    private val longName = "很长的项目名称用于检查窄窗口和省略提示".repeat(8)

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun all(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
    private fun hasText(scene: ImageComposeScene, value: String) = all(scene).any { text(it) == value }
    private fun button(scene: ImageComposeScene, label: String): SemanticsNode = all(scene).lastOrNull { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any { text(it) == label }
    } ?: error("找不到可访问按钮：$label；当前文本：${all(scene).map(::text).filter { it.isNotBlank() }}")

    private fun click(scene: ImageComposeScene, label: String) {
        val target = button(scene, label)
        assertFalse(target.config.contains(SemanticsProperties.Disabled), "$label 应可操作")
        assertTrue(target.config[SemanticsActions.OnClick].action!!.invoke())
    }

    private suspend fun frames(scene: ImageComposeScene) {
        repeat(20) { scene.render(System.nanoTime()).close(); delay(10) }
    }

    private suspend fun awaitDialogClosed(scene: ImageComposeScene, state: DesktopSessionUiState) {
        repeat(300) {
            scene.render(System.nanoTime()).close()
            if (state.projectDialog == null) { frames(scene); return }
            delay(10)
        }
        error("项目弹窗未关闭：${state.projectDialog?.error}")
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        val directory = Files.createDirectories(Path.of("build", "project-preview"))
        scene.render(System.nanoTime()).use { image ->
            image.encodeToData()!!.use { data -> Files.write(directory.resolve("$name.png"), data.bytes) }
        }
    }

    private fun setProjectName(scene: ImageComposeScene, name: String) {
        val editable = all(scene).filter { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertEquals(1, editable.size, "项目弹窗只能编辑名称，路径必须只读")
        assertTrue(editable.single().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(name)))
    }

    @Test
    fun startPageRequiresExplicitProjectAndListsDuplicateAndLongNamesInBothThemes() = runBlocking(Dispatchers.Swing) {
        val workspace = Path.of("build", "project-screen-workspace").toAbsolutePath().normalize()
        val first = Project("11111111-1111-1111-1111-111111111111", "同名项目", workspace, Instant.EPOCH)
        val second = first.copy(id = "22222222-2222-2222-2222-222222222222")
        val longProject = first.copy(id = "33333333-3333-3333-3333-333333333333", name = longName)
        for (width in listOf(420, 1100)) for (dark in listOf(false, true)) {
            val projects = mutableStateListOf<Project>()
            val draft = NewSessionDraft(workspace).also {
                it.input.edit { append("保留任务文本") }
                it.model = selection
                it.approval = ApprovalMode.MANUAL
            }
            var sent = 0
            var createRequested = 0
            ImageComposeScene(width, 1000, coroutineContext = coroutineContext) {
                KZAgentFluentTheme {
                    FluentTheme(colors = if (dark) darkColors() else lightColors(), useAcrylicPopup = false) {
                        NewSessionScreen(draft, projects, selection, ApprovalMode.MANUAL, emptyList(), false, null,
                            configured = true, ready = true, onRefreshModels = {}, onCreateProject = { createRequested++ },
                            onSettings = {}, onSend = { sent++ })
                    }
                }
            }.use { scene ->
                frames(scene)
                assertTrue(button(scene, "发送 ↑").config.contains(SemanticsProperties.Disabled))
                assertEquals(1, all(scene).count { it.config.getOrNull(SemanticsActions.SetText) != null },
                    "开始页只有任务输入，不应有可自由修改的工作区路径")
                click(scene, "新建项目…")
                assertEquals(1, createRequested)
                assertEquals(0, sent)
                screenshot(scene, "start-empty-$width-$dark")

                projects.addAll(listOf(first, second, longProject))
                draft.resolveWorkspace(workspace, projects)
                frames(scene)
                assertTrue(hasText(scene, "启动目录关联多个项目，请明确选择一个项目。"))
                assertTrue(button(scene, "发送 ↑").config.contains(SemanticsProperties.Disabled))
                click(scene, "选择项目…")
                frames(scene)
                assertTrue(hasText(scene, "同名项目 · 11111111"))
                assertTrue(hasText(scene, "同名项目 · 22222222"))
                assertTrue(hasText(scene, longName), "长项目名在语义树中必须完整保留")
                assertTrue(hasText(scene, "此目录对应多个项目，请明确选择；这些项目共享目录中的文件。"))
                assertTrue(all(scene).any { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null })
                screenshot(scene, "project-selector-$width-$dark")
                click(scene, "同名项目 · 22222222")
                frames(scene)
                assertEquals(second.id, draft.projectId)
                assertEquals(workspace, draft.workspace)
                assertNull(draft.pendingWorkspace)
                assertEquals("保留任务文本", draft.input.text.toString())
                assertEquals(selection, draft.model)
                assertEquals(ApprovalMode.MANUAL, draft.approval)
                click(scene, "发送 ↑")
                assertEquals(1, sent)
                screenshot(scene, "start-selected-$width-$dark")

                projects.remove(second)
                frames(scene)
                assertTrue(button(scene, "发送 ↑").config.contains(SemanticsProperties.Disabled))
                assertTrue(hasText(scene, "所选项目已删除或不可用，请重新选择项目。"))
                assertEquals("保留任务文本", draft.input.text.toString())
            }
        }
    }

    @Test
    fun fluentProjectDialogsCancelCreateRenameAndDeleteWithoutCreatingSessionsOrChangingWorkspace() = runBlocking(Dispatchers.Swing) {
        val scope = this
        for (width in listOf(420, 1100)) for (dark in listOf(false, true)) {
            val root = Files.createTempDirectory("project-screen-dialog-test")
            val workspace = Files.createDirectory(root.resolve("workspace"))
            val sentinel = Files.writeString(workspace.resolve("keep.txt"), "保留项目文件")
            var runtimeCreations = 0
            try {
                SessionManager(approvalPolicy = AlwaysApprovePolicy, sessionsRoot = root.resolve("sessions"),
                    projectRepository = FileProjectRepository(root.resolve("projects.json")),
                    projectExpandFile = root.resolve("project-collapses.json"),
                    legacyExpandFile = root.resolve("legacy-collapses.json"),
                    createRuntime = { _, _ -> runtimeCreations++; error("项目管理不应创建 runtime") },
                ).use { manager ->
                    manager.loadSessions(workspace)
                    val state = DesktopSessionUiState(workspace)
                    state.draft.input.edit { append("项目操作不能丢失任务") }
                    state.draft.model = selection
                    state.draft.approval = ApprovalMode.MANUAL
                    state.projectDialog = ProjectDialogState.create(workspace)
                    ImageComposeScene(width, 1000, coroutineContext = coroutineContext) {
                        KZAgentFluentTheme {
                            FluentTheme(colors = if (dark) darkColors() else lightColors(), useAcrylicPopup = false) {
                                ProjectDialogs(state, manager, scope)
                            }
                        }
                    }.use { scene ->
                        frames(scene)
                        assertTrue(hasText(scene, "新建项目"))
                        assertTrue(hasText(scene, "工作区：$workspace"))
                        setProjectName(scene, "取消不保存")
                        frames(scene)
                        click(scene, "取消")
                        awaitDialogClosed(scene, state)
                        assertTrue(manager.projects.isEmpty())
                        assertTrue(manager.sessions.isEmpty())
                        assertNull(state.draft.projectId)

                        state.projectDialog = ProjectDialogState.create(workspace)
                        frames(scene)
                        setProjectName(scene, longName)
                        frames(scene)
                        screenshot(scene, "project-create-$width-$dark")
                        click(scene, "创建")
                        awaitDialogClosed(scene, state)
                        val created = manager.projects.single()
                        assertEquals(longName, created.name)
                        assertEquals(workspace, created.workspace)
                        assertEquals(created.id, state.draft.projectId)
                        assertNull(state.draft.pendingWorkspace)
                        assertTrue(state.showNewSession)
                        assertTrue(state.hasDraft)
                        assertEquals(true, manager.projectExpanded[created.id])
                        assertTrue(manager.sessions.isEmpty(), "明确创建项目不应顺带创建会话")

                        state.projectDialog = ProjectDialogState.create(workspace)
                        frames(scene)
                        assertTrue(hasText(scene, "此目录已关联其他项目，文件将共享。"))
                        click(scene, "取消")
                        awaitDialogClosed(scene, state)
                        assertEquals(listOf(created), manager.projects.toList())

                        state.projectDialog = ProjectDialogState.rename(created)
                        frames(scene)
                        assertTrue(hasText(scene, "工作区：$workspace"))
                        setProjectName(scene, "重命名后的项目")
                        frames(scene)
                        screenshot(scene, "project-rename-$width-$dark")
                        click(scene, "保存")
                        awaitDialogClosed(scene, state)
                        val renamed = manager.projects.single()
                        assertEquals(created.id, renamed.id)
                        assertEquals(created.createdAt, renamed.createdAt)
                        assertEquals(workspace, renamed.workspace)
                        assertEquals("重命名后的项目", selectedDraftProject(state.draft, manager.projects)?.name)
                        assertEquals(workspace, state.draft.workspace)

                        state.projectDialog = ProjectDialogState.delete(renamed)
                        frames(scene)
                        assertTrue(hasText(scene, "删除空项目"))
                        assertFalse(all(scene).any { it.config.getOrNull(SemanticsActions.SetText) != null })
                        screenshot(scene, "project-delete-$width-$dark")
                        click(scene, "删除")
                        awaitDialogClosed(scene, state)
                        assertTrue(manager.projects.isEmpty())
                        assertTrue(manager.sessions.isEmpty())
                        assertNull(state.draft.projectId)
                        assertNull(state.draft.pendingWorkspace)
                        assertEquals(workspace, state.draft.workspace)
                        assertEquals("所选项目已删除，请重新选择项目", state.draft.error)
                        assertEquals("项目操作不能丢失任务", state.draft.input.text.toString())
                        assertEquals(selection, state.draft.model)
                        assertEquals(ApprovalMode.MANUAL, state.draft.approval)
                        assertTrue(Files.isDirectory(workspace))
                        assertEquals("保留项目文件", Files.readString(sentinel))
                        assertEquals(0, runtimeCreations)
                        Files.walk(root).use { files ->
                            assertEquals(0L, files.filter { it.fileName.toString().endsWith(".jsonl") }.count())
                        }
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
}
