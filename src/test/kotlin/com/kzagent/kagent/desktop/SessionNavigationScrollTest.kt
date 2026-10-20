package com.kzagent.kagent.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.darkColors
import io.github.composefluent.lightColors
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFluentApi::class)
class SessionNavigationScrollTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::nodes)

    private fun all(scene: ImageComposeScene) =
        scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }

    private fun text(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private suspend fun frames(scene: ImageComposeScene) {
        repeat(15) { scene.render(System.nanoTime()).close(); delay(10) }
    }

    @Test
    fun newSessionStaysFixedAndClickableWhileHistoryScrolls() = runBlocking(Dispatchers.Swing) {
        val workspace = Path.of("build", "navigation-scroll-workspace").toAbsolutePath()
        val sessions = List(40) { index ->
            SessionData(
                id = "session-$index", name = "历史会话 $index", workspace = workspace,
                sessionFile = workspace.resolve("session-$index.jsonl"), messages = mutableStateListOf(),
            )
        }
        for (width in listOf(700, 1200)) for (dark in listOf(false, true)) {
            var created = 0
            ImageComposeScene(width, 500, coroutineContext = coroutineContext) {
                KZAgentFluentTheme {
                    FluentTheme(colors = if (dark) darkColors() else lightColors(), useAcrylicPopup = false) {
                        KZAgentNavigationView(
                            sessions = sessions, activeIndex = -1, sessionWorkspaceExpanded = emptyMap(),
                            settingsSelected = false, onSelectSession = {}, onAddSession = { created++ },
                            onDeleteSession = {}, onRenameSession = {}, onWorkspaceExpandedChanged = { _, _ -> },
                            onSettings = {}, pane = {},
                        )
                    }
                }
            }.use { scene ->
                frames(scene)
                // 紧凑模式先展开，以便通过文字定位控件并验证同一滚动行为。
                if (width < 1000) {
                    val toggle = all(scene).first { text(it) == "\uE700" }
                    toggle.config[SemanticsActions.OnClick].action!!.invoke()
                    frames(scene)
                }
                val button = all(scene).firstOrNull { text(it) == "创建新会话" } ?: error("Missing new session at width=$width")
                val initialBounds = button.boundsInRoot
                val title = all(scene).first { text(it) == "KZAgent" }
                val listHeader = all(scene).first { text(it) == "会话列表" }
                val initialHeaderBounds = listHeader.boundsInRoot
                assertTrue(title.boundsInRoot.bottom <= initialBounds.top, "新建按钮必须位于标题下方")
                assertTrue(initialBounds.bottom <= listHeader.boundsInRoot.top, "新建按钮必须位于会话列表上方")
                val previews = Files.createDirectories(Path.of("build", "navigation-preview"))
                scene.render(System.nanoTime()).use { image ->
                    image.encodeToData()!!.use { data ->
                        Files.write(previews.resolve("navigation-$width-$dark.png"), data.bytes)
                    }
                }
                val scroll = all(scene).firstOrNull {
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke()?.let { max -> max > 0 } == true
                }
                val range = requireNotNull(scroll) { "Missing scroll at width=$width" }.config[SemanticsProperties.VerticalScrollAxisRange]
                val before = range.value()
                assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 800f))
                frames(scene)
                assertNotEquals(before, range.value())
                val fixedButton = all(scene).first { text(it) == "创建新会话" }
                assertEquals(initialBounds, fixedButton.boundsInRoot)
                val fixedHeader = all(scene).first { text(it) == "会话列表" }
                assertEquals(initialHeaderBounds, fixedHeader.boundsInRoot, "会话列表标题不应随内容滚动")
                assertTrue(fixedHeader.boundsInRoot.bottom <= scroll.boundsInRoot.top)
                assertFalse(all(scene).any { text(it) == "切换工作区" })
                val clickable = all(scene).last {
                    it.config.getOrNull(SemanticsActions.OnClick) != null &&
                        nodes(it).any { descendant -> text(descendant) == "创建新会话" }
                }
                assertTrue(clickable.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(1, created)
                if (width < 1000) {
                    frames(scene)
                    assertFalse(all(scene).any { text(it) == "KZAgent" })
                }
            }
        }
    }
}
