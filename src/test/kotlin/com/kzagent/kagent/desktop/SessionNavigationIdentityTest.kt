package com.kzagent.kagent.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
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
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFluentApi::class)
class SessionNavigationIdentityTest {
    @Test
    fun shortLabelsHandleDefaultNamesWhitespaceAndUnicode() {
        assertEquals("登录", sessionNavigationShortLabel(" 登录修复 "))
        assertEquals("Fix", sessionNavigationShortLabel("Fix terminal"))
        assertEquals("会12", sessionNavigationShortLabel("新会话 12"))
        assertEquals("会话", sessionNavigationShortLabel(" \t\n"))
        assertEquals("👩‍💻修", sessionNavigationShortLabel("👩‍💻修复"))
        assertEquals("e\u0301修", sessionNavigationShortLabel("e\u0301修复"))
        assertEquals("终", sessionNavigationShortLabel("终"))
    }

    @Test
    fun projectActionsAreVisibleWithoutHoverAndDoNotToggleGroups() = runBlocking(Dispatchers.Swing) {
        val workspace = Path.of("build", "project-actions").toAbsolutePath()
        val populated = Project("populated", "主线", workspace, Instant.EPOCH)
        val empty = populated.copy(id = "empty", name = "研究")
        val session = SessionData(id = "session", name = "任务", workspace = workspace,
            sessionFile = workspace.resolve("session.jsonl"), messages = mutableStateListOf(), projectId = populated.id)
        val additions = mutableListOf<String>()
        val renames = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val toggles = mutableListOf<String>()
        ImageComposeScene(1200, 700, coroutineContext = coroutineContext) {
            KZAgentFluentTheme {
                KZAgentNavigationView(sessions = listOf(session), projects = listOf(populated, empty),
                    onCreateProject = {}, onRenameProject = { renames += it }, onDeleteProject = { deletions += it },
                    onAddSessionInProject = { additions += it }, activeIndex = 0, sessionWorkspaceExpanded = emptyMap(),
                    settingsSelected = false, onSelectSession = {}, onAddSession = {}, onDeleteSession = {},
                    onRenameSession = {}, onWorkspaceExpandedChanged = { id, _ -> toggles += id }, onSettings = {}, pane = {})
            }
        }.use { scene ->
            frames(scene)
            fun button(label: String) = all(scene).first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label)
                }
            }
            assertTrue(all(scene).any { text(it) == "暂无会话" })
            assertTrue(button("在项目 主线 中新建会话").config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(button("重命名项目 研究").config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(button("删除项目 研究").config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(button("请先删除项目内的会话").config.contains(SemanticsProperties.Disabled))
            assertEquals(listOf(populated.id), additions)
            assertEquals(listOf(empty.id), renames)
            assertEquals(listOf(empty.id), deletions)
            assertTrue(toggles.isEmpty(), "独立项目操作不能切换展开状态")
        }
    }

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun all(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
    private suspend fun frames(scene: ImageComposeScene) {
        repeat(15) { scene.render(System.nanoTime()).close(); delay(10) }
    }

    @Test
    fun compactLabelsRemainClickableAndFocusShowsFullIdentity() = runBlocking(Dispatchers.Swing) {
        val workspace = Path.of("build", "navigation-identity-workspace").toAbsolutePath()
        val project = Project("test-project", "自定义项目名称", workspace, Instant.EPOCH)
        val sessions = listOf("登录修复", "登录测试", "新会话 12").mapIndexed { index, name ->
            SessionData(id = "identity-$index", name = name, workspace = workspace, projectId = project.id,
                sessionFile = workspace.resolve("$index.jsonl"), messages = mutableStateListOf()).also {
                it.isBusy = index == 0
            }
        }
        for (dark in listOf(false, true)) {
            var selected = -1
            ImageComposeScene(700, 500, coroutineContext = coroutineContext) {
                KZAgentFluentTheme {
                    FluentTheme(colors = if (dark) darkColors() else lightColors(), useAcrylicPopup = false) {
                        KZAgentNavigationView(sessions = sessions, projects = listOf(project),
                            onCreateProject = {}, onRenameProject = {}, onDeleteProject = {}, onAddSessionInProject = {}, activeIndex = 0,
                            sessionWorkspaceExpanded = emptyMap(), settingsSelected = false,
                            onSelectSession = { selected = it }, onAddSession = {}, onDeleteSession = {},
                            onRenameSession = {}, onWorkspaceExpandedChanged = { _, _ -> }, onSettings = {}, pane = {})
                    }
                }
            }.use { scene ->
                frames(scene)
                assertEquals(2, all(scene).count { text(it) == "登录" })
                assertTrue(all(scene).any { text(it) == "会12" })
                val identity = all(scene).first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value ->
                        value.startsWith("登录修复\n") && value.contains(workspace.toString()) && value.endsWith("运行中")
                    } == true
                }
                assertEquals(true, identity.config.getOrNull(SemanticsProperties.Selected))
                val label = all(scene).first { text(it) == "登录" }
                val status = all(scene).first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("运行中")
                }
                assertTrue(label.boundsInRoot.bottom <= status.boundsInRoot.top, "运行圆点不能覆盖短标签 label=${label.boundsInRoot} status=${status.boundsInRoot}")
                val clickable = nodes(identity).first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                scene.sendPointerEvent(PointerEventType.Move, clickable.boundsInRoot.center)
                repeat(70) { scene.render(System.nanoTime()).close(); delay(10) }
                assertTrue(all(scene).any { text(it).startsWith("登录修复\n项目：") }, "悬停必须显示完整身份提示")
                scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 300f))
                frames(scene)
                assertTrue(clickable.config[SemanticsActions.RequestFocus].action!!.invoke())
                frames(scene)
                assertTrue(all(scene).any { text(it).startsWith("登录修复\n项目：") }, "聚焦必须显示完整身份提示")
                val output = Files.createDirectories(Path.of("build", "navigation-preview"))
                scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { data ->
                    Files.write(output.resolve("compact-identity-$dark.png"), data.bytes)
                } }
                assertTrue(clickable.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(0, selected)
            }
        }
    }
}
