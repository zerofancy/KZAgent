package com.kzagent.poc

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isFocused
import ai.rever.bossterm.compose.ConnectionState
import com.kzagent.kagent.desktop.KZAgentFluentTheme
import com.kzagent.kagent.desktop.terminal.BossTermTerminalHandle
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import com.kzagent.kagent.desktop.terminal.TerminalPhase
import com.kzagent.kagent.desktop.terminal.TerminalSettingsProfile
import com.kzagent.kagent.desktop.ui.SessionTerminalPanel
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/** Tests Compose's actual pointer/focus/key path, independently of OS keyboard automation. */
class TerminalInputTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun restoredPanelWaitsForUserClickThenAcceptsKeyboardInput() {
        org.junit.Assume.assumeFalse("Unix shell fixture; Windows UI requires a native fixture",
            System.getProperty("os.name").lowercase().contains("windows"))
        val workspace = Files.createTempDirectory("kzagent-terminal-input-")
        TerminalSettingsProfile.initialize(workspace.resolve("settings"))
        SessionTerminalManager(factory = { BossTermTerminalHandle(it, isolatedSettings(), command = "/bin/sh") },
            installShutdownHook = false).use { manager ->
            manager.toggle("input", workspace)
            val state = manager.stateFor("input")
            val handle = state.handle as BossTermTerminalHandle
            compose.setContent {
                KZAgentFluentTheme {
                    SessionTerminalPanel(state, workspace, manager, autoFocus = false,
                        onCollapse = {}, modifier = Modifier.testTag("terminal"))
                }
            }
            compose.waitUntil(10_000) { state.status.phase == TerminalPhase.RUNNING }
            compose.waitForIdle()
            assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().isEmpty(),
                "Restoring a session must not steal Composer focus")
            compose.onNodeWithTag("terminal").performMouseInput { click() }
            compose.mainClock.advanceTimeBy(250)
            compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
            // Desktop's synthetic Key events do not model lowercase text/IME. Supply the
            // command prefix, then exercise printable digits and Enter through the renderer.
            handle.view.writeUserInput("echo ")
            compose.onNodeWithTag("terminal").performKeyInput {
                pressKey(Key.One); pressKey(Key.Two); pressKey(Key.Three)
                pressKey(Key.Enter)
            }
            compose.waitUntil(5_000) {
                val buffer = handle.engine.textBuffer
                (0 until buffer.screenLinesCount).any { buffer.getLine(it).text.trim() == "123" }
            }
            assertTrue(handle.engine.processHandle?.isAlive() == true)
            println("GATE UI/pointer-focus-keyboard: PASS")
            handle.view.writeUserInput("exit 7\r")
            compose.waitUntil(10_000) { state.status.phase == TerminalPhase.EXITED }
            assertEquals(7, state.status.exitCode)
            assertTrue(handle.view.connectionState.value is ConnectionState.Connected)
            assertTrue((0 until handle.engine.textBuffer.screenLinesCount).any {
                handle.engine.textBuffer.getLine(it).text.trim() == "123"
            })
            handle.view.writeUserInput("touch must-not-exist\r")
            assertFalse(Files.exists(workspace.resolve("must-not-exist")))
            println("GATE UI/final-buffer-read-only: PASS")
        }
        workspace.toFile().deleteRecursively()
    }
}
