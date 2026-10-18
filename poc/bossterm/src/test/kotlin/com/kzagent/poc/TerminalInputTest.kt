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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import com.kzagent.kagent.desktop.terminal.SessionTerminalState
import com.kzagent.kagent.desktop.terminal.TerminalTabState
import com.kzagent.kagent.desktop.terminal.TerminalStatus
import com.kzagent.kagent.desktop.ui.TerminalTabBar
import ai.rever.bossterm.compose.ConnectionState
import com.kzagent.kagent.desktop.KZAgentFluentTheme
import com.kzagent.kagent.desktop.terminal.BossTermTerminalHandle
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import com.kzagent.kagent.desktop.terminal.TerminalPhase
import com.kzagent.kagent.desktop.terminal.TerminalSettingsProfile
import com.kzagent.kagent.desktop.ui.SessionTerminalPanel
import com.kzagent.kagent.desktop.ui.TerminalFocusState
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
    fun insertionBeforeLazyMeasurementUsesTheSameSnapshotAsFocusBindings() {
        val state = tabBarState(1)
        val width = mutableStateOf(480.dp)
        val insertDuringMeasure = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.setContent {
            KZAgentFluentTheme {
                Column(Modifier.width(width.value).layout { measurable, constraints ->
                    // Force the race from the reported stack: a list mutation is
                    // visible to lazy measurement before its parent can recompose.
                    if (insertDuringMeasure.getAndSet(false)) {
                        val ordinal = state.tabs.size + 1
                        state.tabs.add(TerminalTabState("tab-$ordinal", ordinal, java.nio.file.Path.of(".")).also {
                            it.status = TerminalStatus(TerminalPhase.RUNNING)
                        })
                        state.activeTabId = "tab-$ordinal"
                    }
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                }) {
                    TerminalTabBar(state, onSelect = { state.activeTabId = it.tabId }, onClose = {},
                        onCreate = {}, onRestart = {}, onCollapse = {})
                }
            }
        }
        compose.waitForIdle()
        repeat(7) { index ->
            compose.runOnIdle { insertDuringMeasure.set(true); width.value = (481 + index % 2).dp }
            compose.waitForIdle()
        }
        assertEquals(8, state.tabs.size)
        assertWholeTabVisible(8)
    }

    @Test
    fun rapidPointerCreationKeepsLazyItemsAndFocusBindingsConsistent() {
        val state = tabBarState(1)
        var ordinal = 1
        compose.setContent {
            KZAgentFluentTheme {
                Column(Modifier.width(480.dp)) {
                    TerminalTabBar(state, onSelect = { state.activeTabId = it.tabId }, onClose = {},
                        onCreate = {
                            val number = ++ordinal
                            state.tabs.add(TerminalTabState("tab-$number", number, java.nio.file.Path.of(".")).also {
                                it.status = TerminalStatus(TerminalPhase.RUNNING)
                            })
                            state.activeTabId = "tab-$number"
                        }, onRestart = {}, onCollapse = {})
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // Input forces lazy measurement before the next parent composition frame.
        compose.onNodeWithContentDescription("新建终端").performMouseInput {
            repeat(63) { click() }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(64, state.tabs.size)
        assertWholeTabVisible(64)
        compose.onNodeWithContentDescription("新建终端").assertIsDisplayed()
    }

    @Test
    fun shortTabsShareAvailableWidthBeforeOverflowing() {
        val state = tabBarState(4)
        compose.setContent {
            KZAgentFluentTheme {
                Column(Modifier.width(760.dp)) {
                    TerminalTabBar(state, onSelect = { state.activeTabId = it.tabId }, onClose = {},
                        onCreate = {}, onRestart = {}, onCollapse = {})
                }
            }
        }
        compose.waitForIdle()
        (1..4).forEach { ordinal -> assertWholeTabVisible(ordinal) }
        compose.onNodeWithContentDescription("新建终端").assertIsDisplayed()
        compose.onNodeWithContentDescription("重启当前终端").assertIsDisplayed()
        compose.onNodeWithContentDescription("折叠终端面板").assertIsDisplayed()
    }

    @Test
    fun arrowKeysCanFocusTabsOutsideTheComposedViewport() {
        val state = tabBarState(8)
        state.activeTabId = "tab-1"
        compose.setContent {
            KZAgentFluentTheme {
                Column(Modifier.width(480.dp)) {
                    TerminalTabBar(state, onSelect = { state.activeTabId = it.tabId }, onClose = {},
                        onCreate = {}, onRestart = {}, onCollapse = {})
                }
            }
        }
        compose.onNodeWithText("终端 2").performMouseInput { click() }
        compose.onNodeWithText("终端 2").performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("终端 3，运行中").assertIsFocused()
        // Arrows move tab focus; Enter explicitly changes the selected terminal.
        assertEquals("tab-2", state.activeTabId)
        compose.onNodeWithText("终端 3").performKeyInput { pressKey(Key.Enter) }
        assertEquals("tab-3", state.activeTabId)
        compose.onNodeWithText("终端 3").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("终端 2，运行中").assertIsFocused()
    }

    @Test
    fun overflowingTabsKeepCompleteEdgeTabsWhenResizing() {
        val state = tabBarState(8)
        val width = mutableStateOf(480.dp)
        compose.setContent {
            KZAgentFluentTheme {
                Column(Modifier.width(width.value)) {
                    TerminalTabBar(state, onSelect = { state.activeTabId = it.tabId }, onClose = {},
                        onCreate = {}, onRestart = {}, onCollapse = {})
                }
            }
        }
        compose.waitForIdle()
        assertWholeTabVisible(7)
        assertWholeTabVisible(8)
        compose.runOnIdle { width.value = 760.dp }
        compose.waitForIdle()
        (5..8).forEach { assertWholeTabVisible(it) }
        compose.runOnIdle { width.value = 320.dp }
        compose.waitForIdle()
        assertWholeTabVisible(8)
        compose.onNodeWithContentDescription("新建终端").assertIsDisplayed()
        compose.onNodeWithContentDescription("折叠终端面板").assertIsDisplayed()
    }

    private fun tabBarState(count: Int) = SessionTerminalState("layout").also { state ->
        (1..count).forEach { ordinal ->
            state.tabs.add(TerminalTabState("tab-$ordinal", ordinal, java.nio.file.Path.of(".")).also {
                it.status = TerminalStatus(TerminalPhase.RUNNING)
            })
        }
        state.activeTabId = "tab-$count"
    }

    private fun assertWholeTabVisible(ordinal: Int) {
        val text = compose.onNodeWithText("终端 $ordinal").assertIsDisplayed().fetchSemanticsNode()
        assertEquals(text.size.width.toFloat(), text.boundsInRoot.width, 1f, "终端 $ordinal 的标题被裁切")
        val close = compose.onNodeWithContentDescription("关闭终端 $ordinal").assertIsDisplayed().fetchSemanticsNode()
        assertEquals(close.size.width.toFloat(), close.boundsInRoot.width, 1f, "终端 $ordinal 的关闭按钮被裁切")
    }

    @Test
    fun tabsSwitchShellsAndRepeatedSelectionRestoresFocus() {
        val workspace = Files.createTempDirectory("kzagent-terminal-tabs-")
        TerminalSettingsProfile.initialize(workspace.resolve("settings"))
        SessionTerminalManager(factory = { BossTermTerminalHandle(it, isolatedSettings(), command = isolatedShell()) },
            installShutdownHook = false).use { manager ->
            val first = manager.createTab("tabs", workspace)!!
            val second = manager.createTab("tabs", workspace)!!
            val state = manager.stateFor("tabs")
            val focus = TerminalFocusState("tabs")
            compose.setContent {
                KZAgentFluentTheme {
                    SessionTerminalPanel(state, manager, workspace, focus, onCollapse = {},
                        modifier = Modifier.testTag("terminal"))
                }
            }
            compose.waitUntil(10_000) { first.status.phase == TerminalPhase.RUNNING && second.status.phase == TerminalPhase.RUNNING }
            val firstHandle = first.handle as BossTermTerminalHandle
            val secondHandle = second.handle as BossTermTerminalHandle
            assertFalse(firstHandle.engine.processHandle?.getPid() == secondHandle.engine.processHandle?.getPid())
            // Write through the independent PTY while its renderer is unmounted.
            firstHandle.view.writeUserInput("echo BACKGROUND123\r")
            compose.waitUntil(5_000) { (0 until firstHandle.engine.textBuffer.screenLinesCount).any {
                firstHandle.engine.textBuffer.getLine(it).text.trim() == "BACKGROUND123"
            } }
            compose.onNodeWithText("终端 1", substring = true).performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.waitUntil(5_000) { firstHandle.view.isFocused.value }
            assertEquals(first.tabId, state.activeTabId)
            compose.onNodeWithText("终端 1", substring = true).performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.waitUntil(5_000) { firstHandle.view.isFocused.value }
            compose.onNodeWithText("终端 2", substring = true).performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.waitUntil(5_000) { secondHandle.view.isFocused.value }
            assertFalse(firstHandle.view.isFocused.value)
            assertTrue(firstHandle.engine.processHandle?.isAlive() == true)
            println("GATE UI/multiple-tabs-background-and-focus: PASS")
        }
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun runningTabCloseDefaultsToCancelAndDoesNotChangeSelection() {
        val workspace = Files.createTempDirectory("kzagent-terminal-confirm-")
        TerminalSettingsProfile.initialize(workspace.resolve("settings"))
        SessionTerminalManager(factory = { BossTermTerminalHandle(it, isolatedSettings(), command = isolatedShell()) },
            installShutdownHook = false).use { manager ->
            val first = manager.createTab("confirm", workspace)!!
            val second = manager.createTab("confirm", workspace)!!
            val state = manager.stateFor("confirm")
            val focus = TerminalFocusState("confirm")
            compose.setContent {
                KZAgentFluentTheme {
                    SessionTerminalPanel(state, manager, workspace, focus, onCollapse = {})
                }
            }
            compose.waitUntil(10_000) { first.status.phase == TerminalPhase.RUNNING && second.status.phase == TerminalPhase.RUNNING }
            compose.onNodeWithContentDescription("关闭终端 1").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("取消").assertIsFocused()
            compose.onNodeWithText("取消").performClick()
            assertEquals(2, state.tabs.size)
            assertEquals(second.tabId, state.activeTabId)
            compose.onNodeWithContentDescription("关闭终端 1").performClick()
            compose.onNodeWithText("关闭终端", substring = false).performClick()
            compose.waitUntil(10_000) { state.tabs.size == 1 }
            assertEquals(second.tabId, state.activeTabId)
            assertTrue((second.handle as BossTermTerminalHandle).engine.processHandle?.isAlive() == true)
            compose.onNodeWithContentDescription("关闭终端 2").performClick()
            compose.onNodeWithText("关闭终端", substring = false).performClick()
            compose.waitUntil(10_000) { state.tabs.isEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText("新建终端").assertIsFocused()
            println("GATE UI/close-confirmation-cancel-and-background: PASS")
        }
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun restoredPanelWaitsForUserClickThenAcceptsKeyboardInput() {
        val workspace = Files.createTempDirectory("kzagent-terminal-input-")
        TerminalSettingsProfile.initialize(workspace.resolve("settings"))
        SessionTerminalManager(factory = { BossTermTerminalHandle(it, isolatedSettings(), command = isolatedShell()) },
            installShutdownHook = false).use { manager ->
            manager.toggle("input", workspace)
            val state = manager.stateFor("input")
            val handle = state.activeTab!!.handle as BossTermTerminalHandle
            val focus = TerminalFocusState("input")
            compose.setContent {
                KZAgentFluentTheme {
                    SessionTerminalPanel(state = state, workspace = workspace, manager = manager, focusState = focus,
                        onCollapse = {}, modifier = Modifier.testTag("terminal"))
                }
            }
            compose.waitUntil(10_000) { state.activeTab!!.status.phase == TerminalPhase.RUNNING }
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
            compose.waitUntil(10_000) { state.activeTab!!.status.phase == TerminalPhase.EXITED }
            assertEquals(7, state.activeTab!!.status.exitCode)
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
