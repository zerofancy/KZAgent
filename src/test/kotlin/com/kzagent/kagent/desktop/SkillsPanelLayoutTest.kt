package com.kzagent.kagent.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import com.kzagent.kagent.config.SkillsConfig
import io.github.composefluent.*
import io.github.composefluent.background.Mica
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFluentApi::class)
class SkillsPanelLayoutTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun all(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
    private fun click(scene: ImageComposeScene, label: String) {
        val node = all(scene).first { label in text(it) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
    }
    private suspend fun frames(scene: ImageComposeScene) {
        repeat(15) { scene.render(System.nanoTime()).close(); delay(10) }
    }

    @Test fun narrowAndWideLayoutsRenderInBothThemesAndOpenDetails() = runBlocking(Dispatchers.Swing) {
        val root = Files.createTempDirectory("skills-layout")
        val dir = Files.createDirectories(root.resolve("long-description"))
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: long-description\ndescription: ${"用于验证较长内容的技能说明。".repeat(20)}\n---\n${"正文内容\n".repeat(100)}")
        val output = Files.createDirectories(Path.of("build", "skills-preview"))
        for (width in listOf(480, 1080)) for (dark in listOf(false, true)) {
            var config by mutableStateOf(SkillsConfig())
            ImageComposeScene(width, 760, coroutineContext = coroutineContext) {
                KZAgentFluentTheme {
                    FluentTheme(colors = if (dark) darkColors() else lightColors(), compactMode = true, useAcrylicPopup = false) {
                        Mica(Modifier.fillMaxSize()) {
                            SkillsPanel(config, true, false, save = { config = it(config) },
                                onRefresh = {}, onDeletingChanged = {}, skillsRoot = root)
                        }
                    }
                }
            }.use { scene ->
                frames(scene)
                assertTrue(all(scene).any { "skill-installer" in text(it) })
                click(scene, "禁用全部")
                frames(scene)
                assertFalse(config.enabled)
                click(scene, "启用全部")
                frames(scene)
                assertTrue(config.enabled)
                click(scene, "查看详情")
                frames(scene)
                assertTrue(all(scene).any { "返回列表" in text(it) })
                val content = all(scene).first { "Skills" == text(it) }
                assertTrue(content.boundsInRoot.right <= width)
                scene.render(System.nanoTime()).use { image ->
                    image.encodeToData()!!.use { data -> Files.write(output.resolve("skills-$width-$dark.png"), data.bytes) }
                }
                click(scene, "返回列表")
                frames(scene)
                click(scene, "加载目录")
                frames(scene)
                assertTrue(all(scene).any { "默认目录" in text(it) })
            }
        }
    }
}
