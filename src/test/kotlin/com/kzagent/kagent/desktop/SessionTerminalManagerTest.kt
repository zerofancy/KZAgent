package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.app.isTerminalToggleKey
import com.kzagent.kagent.desktop.terminal.SessionTerminalHandle
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import com.kzagent.kagent.desktop.terminal.TerminalPhase
import com.kzagent.kagent.desktop.terminal.TerminalStatus
import com.kzagent.kagent.desktop.terminal.terminalPanelHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTerminalManagerTest {
    private val workspace = Path.of(".").toAbsolutePath()

    private fun manager(limit: Int = 8, factory: (Path) -> SessionTerminalHandle) = SessionTerminalManager(
        factory = factory, limit = limit,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), installShutdownHook = false,
    )

    @Test
    fun `opening is lazy and folding preserves the same shell`() {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal().also(handles::add) }.use { manager ->
            val state = manager.stateFor("a")
            assertTrue(handles.isEmpty())
            manager.toggle("a", workspace)
            val handle = assertNotNull(state.handle)
            manager.toggle("a", workspace)
            assertFalse(state.visible)
            manager.toggle("a", workspace)
            assertTrue(state.visible)
            assertTrue(state.handle === handle)
            assertEquals(1, handles.single().starts)
            assertEquals(0, handles.single().closes.get())
        }
        assertEquals(1, handles.single().closes.get())
    }

    @Test
    fun `same workspace does not share handles across sessions`() {
        manager { FakeTerminal() }.use { manager ->
            manager.toggle("a", workspace)
            manager.toggle("b", workspace)
            assertFalse(manager.stateFor("a").handle === manager.stateFor("b").handle)
        }
    }

    @Test
    fun `resource limit never evicts an existing shell`() {
        val handles = mutableListOf<FakeTerminal>()
        manager(limit = 1) { FakeTerminal().also(handles::add) }.use { manager ->
            manager.toggle("a", workspace)
            manager.toggle("b", workspace)
            assertEquals(1, handles.size)
            assertNotNull(manager.stateFor("b").error)
            assertNull(manager.stateFor("b").handle)
            assertEquals(0, handles.single().closes.get())
        }
    }

    @Test
    fun `natural exit preserves buffer and releases active capacity`() {
        val handles = mutableListOf<FakeTerminal>()
        manager(limit = 1) { FakeTerminal().also(handles::add) }.use { manager ->
            manager.toggle("a", workspace)
            handles.single().status.value = TerminalStatus(TerminalPhase.EXITED, 7)
            assertEquals(7, manager.stateFor("a").status.exitCode)
            assertNotNull(manager.stateFor("a").handle)
            manager.toggle("b", workspace)
            assertEquals(2, handles.size)
        }
    }

    @Test
    fun `delete ignores late callbacks and keeps other sessions alive`() = runBlocking {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal().also(handles::add) }.use { manager ->
            manager.toggle("a", workspace)
            manager.toggle("b", workspace)
            val removed = manager.stateFor("a")
            manager.closeSession("a")
            handles.first().status.value = TerminalStatus(TerminalPhase.FAILED)
            assertEquals(TerminalPhase.NOT_CREATED, removed.status.phase)
            assertEquals(1, handles.first().closes.get())
            assertEquals(0, handles.last().closes.get())
            assertEquals(TerminalPhase.RUNNING, manager.stateFor("b").status.phase)
        }
    }

    @Test
    fun `factory failure is recoverable and does not expose its raw error`() {
        var fail = true
        manager { if (fail) error("private environment detail") else FakeTerminal() }.use { manager ->
            manager.toggle("a", workspace)
            val state = manager.stateFor("a")
            assertEquals(TerminalPhase.FAILED, state.status.phase)
            assertFalse(state.error.orEmpty().contains("private"))
            fail = false
            manager.toggle("a", workspace)
            manager.toggle("a", workspace)
            assertEquals(TerminalPhase.RUNNING, state.status.phase)
        }
    }

    @Test
    fun `restart stops old generation before publishing a new shell`() = runBlocking {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal().also(handles::add) }.use { manager ->
            manager.toggle("a", workspace)
            val original = manager.stateFor("a").handle
            manager.closeTerminal("a", workspace, restart = true)
            withTimeout(5_000) {
                // Teardown clears the old handle before the replacement is published.
                while (manager.stateFor("a").handle.let { it == null || it === original }) delay(10)
            }
            assertEquals(2, handles.size)
            assertEquals(1, handles.first().closes.get())
            handles.first().status.value = TerminalStatus(TerminalPhase.FAILED)
            assertEquals(TerminalPhase.RUNNING, manager.stateFor("a").status.phase)
            assertTrue(manager.stateFor("a").visible)
        }
    }

    @Test
    fun `root close is idempotent and stops every owned handle`() {
        val handles = mutableListOf<FakeTerminal>()
        val manager = manager { FakeTerminal().also(handles::add) }
        manager.toggle("a", workspace)
        manager.toggle("b", workspace)
        manager.close()
        manager.close()
        assertEquals(listOf(1, 1), handles.map { it.closes.get() })
    }

    @Test
    fun `layout preserves chat minimum and collapses on small windows`() {
        assertEquals(0f, terminalPanelHeight(380f, 0.35f))
        assertEquals(160f, terminalPanelHeight(600f, 0f))
        assertEquals(374f, terminalPanelHeight(600f, 1f))
        assertEquals(210f, terminalPanelHeight(600f, 0.35f))
    }

    @Test
    fun `toggle shortcut does not consume shell keys or other modifier combinations`() {
        assertTrue(isTerminalToggleKey(true, true, false, false, false))
        assertFalse(isTerminalToggleKey(true, false, false, false, false))
        assertFalse(isTerminalToggleKey(true, true, false, false, true))
        assertFalse(isTerminalToggleKey(false, true, false, false, false))
        assertFalse(isTerminalToggleKey(true, true, true, false, false))
    }

    private class FakeTerminal : SessionTerminalHandle {
        override val status = MutableStateFlow(TerminalStatus(TerminalPhase.RUNNING))
        var starts = 0
        val closes = AtomicInteger()
        override fun start() { starts++ }
        override fun requestClose() = Unit
        override suspend fun shutdown() { closes.incrementAndGet() }
    }
}
