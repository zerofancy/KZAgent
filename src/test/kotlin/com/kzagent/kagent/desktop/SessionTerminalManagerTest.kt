package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.app.isTerminalToggleKey
import com.kzagent.kagent.desktop.terminal.SessionTerminalHandle
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import com.kzagent.kagent.desktop.terminal.TerminalOperation
import com.kzagent.kagent.desktop.terminal.TerminalPhase
import com.kzagent.kagent.desktop.terminal.TerminalStatus
import com.kzagent.kagent.desktop.terminal.adjacentTerminalTab
import com.kzagent.kagent.desktop.terminal.terminalPanelHeight
import com.kzagent.kagent.desktop.ui.terminalTabFocusDelta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
    private fun manager(factory: (Path) -> SessionTerminalHandle) = SessionTerminalManager(
        factory = factory,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), installShutdownHook = false,
    )
    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(1) }

    @Test
    fun `opening is lazy and folding preserves all shells and selection`() {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal().also(handles::add) }.use { manager ->
            val state = manager.stateFor("a")
            assertTrue(handles.isEmpty())
            manager.toggle("a", workspace)
            val first = state.activeTab!!
            val second = manager.createTab("a", workspace)!!
            manager.selectTab("a", first.tabId)
            manager.toggle("a", workspace)
            assertFalse(state.visible)
            manager.toggle("a", workspace)
            assertEquals(first.tabId, state.activeTabId)
            assertTrue(state.tabs[1] === second)
            assertEquals(listOf(1, 1), handles.map { it.starts })
            assertTrue(handles.all { it.closes.get() == 0 })
        }
        assertTrue(handles.all { it.closes.get() == 1 })
    }

    @Test
    fun `new tabs use session workspace and have stable nonreused ordinals`() = runBlocking<Unit> {
        val paths = mutableListOf<Path>()
        manager { paths.add(it); FakeTerminal() }.use { manager ->
            val first = manager.createTab("a", workspace)!!
            val second = manager.createTab("a", workspace)!!
            assertFalse(first.tabId == second.tabId)
            manager.closeTab("a", second.tabId, second.generation)!!.join()
            val third = manager.createTab("a", workspace)!!
            assertEquals(3, third.ordinal)
            assertEquals(listOf(workspace, workspace, workspace), paths)
        }
    }

    @Test
    fun `same workspace does not share tabs or selection across sessions`() {
        manager { FakeTerminal() }.use { manager ->
            val first = manager.createTab("a", workspace)!!
            val second = manager.createTab("b", workspace)!!
            assertFalse(first.handle === second.handle)
            manager.selectTab("b", first.tabId)
            assertEquals(second.tabId, manager.stateFor("b").activeTabId)
        }
    }

    @Test
    fun `starting and running tabs have no per session or global count limit`() {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal(TerminalPhase.STARTING).also(handles::add) }.use { manager ->
            repeat(64) { index -> assertNotNull(manager.createTab(if (index < 48) "a" else "b", workspace)) }
            assertEquals(48, manager.stateFor("a").tabs.size)
            assertEquals(16, manager.stateFor("b").tabs.size)
            handles.forEach { it.status.value = TerminalStatus(TerminalPhase.RUNNING) }
            assertNotNull(manager.createTab("a", workspace))
            assertEquals(65, handles.size)
            assertNull(manager.stateFor("a").error)
            assertNull(manager.stateFor("b").error)
            assertTrue(handles.all { it.closes.get() == 0 })
        }
        assertTrue(handles.all { it.closes.get() == 1 })
    }

    @Test
    fun `natural exit retains output handle without affecting new shells`() {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal().also(handles::add) }.use { manager ->
            val tab = manager.createTab("a", workspace)!!
            handles.single().status.value = TerminalStatus(TerminalPhase.EXITED, 7)
            assertEquals(7, tab.status.exitCode)
            assertNotNull(tab.handle)
            assertNotNull(manager.createTab("b", workspace))
            assertEquals(2, handles.size)
        }
    }

    @Test
    fun `exited tabs have no retained count limit across sessions`() = runBlocking<Unit> {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal().also(handles::add) }.use { manager ->
            repeat(64) { index ->
                assertNotNull(manager.createTab(if (index < 48) "a" else "b", workspace))
                handles.last().status.value = TerminalStatus(TerminalPhase.EXITED)
            }
            assertNotNull(manager.createTab("c", workspace))
            assertEquals(65, handles.size)
            assertTrue(handles.all { it.closes.get() == 0 })
            assertNull(manager.stateFor("c").error)
        }
    }

    @Test
    fun `closing chooses right then left and last close keeps empty panel`() = runBlocking<Unit> {
        manager { FakeTerminal() }.use { manager ->
            val a = manager.createTab("a", workspace)!!
            val b = manager.createTab("a", workspace)!!
            val c = manager.createTab("a", workspace)!!
            val state = manager.stateFor("a")
            manager.selectTab("a", b.tabId)
            manager.closeTab("a", b.tabId, b.generation)!!.join()
            assertEquals(c.tabId, state.activeTabId)
            manager.closeTab("a", c.tabId, c.generation)!!.join()
            assertEquals(a.tabId, state.activeTabId)
            manager.closeTab("a", a.tabId, a.generation)!!.join()
            assertTrue(state.visible)
            assertTrue(state.tabs.isEmpty())
            assertNull(state.activeTabId)
            manager.toggle("a", workspace)
            manager.toggle("a", workspace)
            assertTrue(state.tabs.isEmpty())
        }
    }

    @Test
    fun `closing background tab does not change selection`() = runBlocking<Unit> {
        manager { FakeTerminal() }.use { manager ->
            val first = manager.createTab("a", workspace)!!
            val second = manager.createTab("a", workspace)!!
            manager.closeTab("a", first.tabId, first.generation)!!.join()
            assertEquals(second.tabId, manager.stateFor("a").activeTabId)
        }
    }

    @Test
    fun `close keeps owned tab until teardown completes without blocking other shells`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val handle = FakeTerminal(gate = gate)
        var created = 0
        val manager = manager { if (created++ == 0) handle else FakeTerminal() }
        try {
            val tab = manager.createTab("a", workspace)!!
            val job = manager.closeTab("a", tab.tabId, tab.generation)!!
            until { handle.closes.get() == 1 }
            assertEquals(TerminalOperation.CLOSING, tab.operation)
            assertTrue(manager.stateFor("a").tabs.contains(tab))
            assertNotNull(manager.createTab("b", workspace))
            assertNull(manager.closeTab("a", tab.tabId, tab.generation))
            assertNull(manager.restartTab("a", tab.tabId, tab.generation, workspace))
            gate.complete(Unit)
            job.join()
            assertTrue(manager.stateFor("a").tabs.isEmpty())
        } finally { gate.complete(Unit); manager.close() }
    }

    @Test
    fun `startup exception cleans partial handle and keeps failed tab for retry`() = runBlocking<Unit> {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal(failStart = handles.isEmpty()).also(handles::add) }.use { manager ->
            val tab = manager.createTab("a", workspace)!!
            until { tab.operation == TerminalOperation.NONE }
            assertEquals(TerminalPhase.FAILED, tab.status.phase)
            assertFalse(tab.error!!.contains("private"))
            assertNull(tab.handle)
            assertEquals(1, handles.first().closes.get())
            manager.restartTab("a", tab.tabId, tab.generation, workspace)!!.join()
            assertEquals(TerminalPhase.RUNNING, tab.status.phase)
            assertEquals(2, handles.size)
        }
    }

    @Test
    fun `factory failure is recoverable without leaking raw error`() = runBlocking<Unit> {
        var fail = true
        manager { if (fail) error("private environment detail") else FakeTerminal() }.use { manager ->
            val tab = manager.createTab("a", workspace)!!
            until { !tab.busy }
            assertEquals(TerminalPhase.FAILED, tab.status.phase)
            assertFalse(tab.error!!.contains("private"))
            fail = false
            manager.restartTab("a", tab.tabId, tab.generation, workspace)!!.join()
            assertEquals(TerminalPhase.RUNNING, tab.status.phase)
        }
    }

    @Test
    fun `engine failure retains handle until cleanup without blocking other shells`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val handle = FakeTerminal(gate = gate)
        var created = 0
        val manager = manager { if (created++ == 0) handle else FakeTerminal() }
        try {
            val tab = manager.createTab("a", workspace)!!
            handle.status.value = TerminalStatus(TerminalPhase.FAILED)
            until { handle.closes.get() == 1 }
            assertTrue(tab.handle === handle)
            assertNotNull(manager.createTab("b", workspace))
            gate.complete(Unit)
            until { !tab.busy }
            assertNull(tab.handle)
            assertNotNull(manager.createTab("b", workspace))
        } finally { gate.complete(Unit); manager.close() }
    }

    @Test
    fun `cleanup failure retains ownership for retry without blocking other shells`() = runBlocking<Unit> {
        val handle = FakeTerminal(failShutdown = true)
        var created = 0
        manager { if (created++ == 0) handle else FakeTerminal() }.use { manager ->
            val tab = manager.createTab("a", workspace)!!
            manager.closeTab("a", tab.tabId, tab.generation)!!.join()
            assertEquals(TerminalOperation.CLEANUP_FAILED, tab.operation)
            assertTrue(tab.handle === handle)
            assertNotNull(manager.createTab("b", workspace))
            assertNull(manager.restartTab("a", tab.tabId, tab.generation, workspace))
            assertFalse(tab.error!!.contains("private"))
            handle.failShutdown = false
            manager.retryCleanup("a", tab.tabId)!!.join()
            assertTrue(manager.stateFor("a").tabs.isEmpty())
            assertEquals(2, handle.closes.get())
        }
    }

    @Test
    fun `restart waits for teardown and ignores old generation callbacks`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val handles = mutableListOf<FakeTerminal>()
        val manager = manager { FakeTerminal(gate = if (handles.isEmpty()) gate else null).also(handles::add) }
        try {
            val tab = manager.createTab("a", workspace)!!
            val oldGeneration = tab.generation
            val old = tab.handle
            val job = manager.restartTab("a", tab.tabId, tab.generation, workspace)!!
            until { handles.first().closes.get() == 1 }
            assertTrue(tab.handle === old)
            gate.complete(Unit)
            job.join()
            assertEquals(2, handles.size)
            assertEquals(1, handles.first().closes.get())
            assertTrue(tab.generation > oldGeneration)
            assertEquals(1, tab.ordinal)
            assertTrue(tab.handle !== old)
            handles.first().status.value = TerminalStatus(TerminalPhase.FAILED)
            assertEquals(TerminalPhase.RUNNING, tab.status.phase)
            assertNull(manager.closeTab("a", tab.tabId, oldGeneration))
        } finally { gate.complete(Unit); manager.close() }
    }

    @Test
    fun `exited terminal can restart with many other shells running`() = runBlocking<Unit> {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal().also(handles::add) }.use { manager ->
            val first = manager.createTab("a", workspace)!!
            handles.first().status.value = TerminalStatus(TerminalPhase.EXITED)
            repeat(40) { manager.createTab("b", workspace)!! }
            val old = first.handle
            val generation = first.generation
            manager.restartTab("a", first.tabId, generation, workspace)!!.join()
            assertTrue(first.handle !== old)
            assertTrue(first.generation > generation)
            assertEquals(1, handles.first().closes.get())
            assertEquals(42, handles.size)
            assertTrue(handles.drop(1).all { it.closes.get() == 0 })
        }
    }

    @Test
    fun `delete closes all 64 associated tabs and preserves another session`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val handles = mutableListOf<FakeTerminal>()
        val manager = manager { FakeTerminal(gate = gate).also(handles::add) }
        try {
            repeat(64) { manager.createTab("a", workspace)!! }
            val other = manager.createTab("b", workspace)!!
            val deleting = async { manager.closeSession("a") }
            until { handles.take(64).all { it.closes.get() == 1 } }
            assertNull(manager.createTab("a", workspace))
            assertEquals(0, handles.last().closes.get())
            gate.complete(Unit)
            deleting.await()
            assertTrue(handles.take(64).all { it.closes.get() == 1 })
            assertTrue(manager.stateFor("a").tabs.isEmpty())
            assertEquals(TerminalPhase.RUNNING, other.status.phase)
        } finally { gate.complete(Unit); manager.close() }
    }

    @Test
    fun `repository deletion interval cannot recreate shells and failure unlocks session`() = runBlocking<Unit> {
        manager { FakeTerminal() }.use { manager ->
            manager.createTab("a", workspace)!!
            manager.closeSession("a")
            assertTrue(manager.stateFor("a").deleting)
            assertNull(manager.createTab("a", workspace))
            manager.finishSessionDeletion("a", deleted = false)
            assertFalse(manager.stateFor("a").deleting)
            assertNotNull(manager.createTab("a", workspace))
            manager.closeSession("a")
            manager.finishSessionDeletion("a", deleted = true)
            assertTrue(manager.stateFor("a").tabs.isEmpty())
        }
    }

    @Test
    fun `delete failure cleans siblings and keeps session retryable`() = runBlocking<Unit> {
        val handles = mutableListOf<FakeTerminal>()
        manager { FakeTerminal(failShutdown = handles.isEmpty()).also(handles::add) }.use { manager ->
            val failed = manager.createTab("a", workspace)!!
            manager.createTab("a", workspace)!!
            val result = runCatching { manager.closeSession("a") }
            assertTrue(result.isFailure)
            val state = manager.stateFor("a")
            assertFalse(state.deleting)
            assertEquals(listOf(failed), state.tabs.toList())
            assertEquals(listOf(1, 1), handles.map { it.closes.get() })
            handles.first().failShutdown = false
            manager.closeSession("a")
            assertTrue(manager.stateFor("a").tabs.isEmpty())
        }
    }

    @Test
    fun `delete racing restart never creates replacement shell`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val handle = FakeTerminal(gate = gate)
        val manager = manager { handle }
        try {
            val tab = manager.createTab("a", workspace)!!
            val restart = manager.restartTab("a", tab.tabId, tab.generation, workspace)!!
            val delete = async { manager.closeSession("a") }
            until { manager.stateFor("a").deleting }
            gate.complete(Unit)
            restart.join()
            delete.await()
            assertEquals(1, handle.starts)
            assertEquals(1, handle.closes.get())
        } finally { gate.complete(Unit); manager.close() }
    }

    @Test
    fun `root close shares in flight cleanup without waiting on UI callbacks`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val handle = FakeTerminal(gate = gate)
        val manager = manager { handle }
        try {
            val tab = manager.createTab("a", workspace)!!
            manager.closeTab("a", tab.tabId, tab.generation)
            until { handle.closes.get() == 1 }
            val root = async(Dispatchers.Default) { manager.close() }
            gate.complete(Unit)
            withTimeout(5_000) { root.await() }
            assertEquals(1, handle.closes.get())
            manager.close()
        } finally { gate.complete(Unit); manager.close() }
    }

    @Test
    fun `root close retries failed cleanup and closes every owned tab`() = runBlocking<Unit> {
        val handles = mutableListOf<FakeTerminal>()
        val manager = manager { FakeTerminal().also(handles::add) }
        val tab = manager.createTab("a", workspace)!!
        manager.createTab("a", workspace)!!
        manager.createTab("b", workspace)!!
        handles.first().failShutdown = true
        manager.closeTab("a", tab.tabId, tab.generation)!!.join()
        handles.first().failShutdown = false
        manager.close()
        manager.close()
        assertEquals(listOf(2, 1, 1), handles.map { it.closes.get() })
    }

    @Test
    fun `selection helper and layout handle edges`() {
        assertEquals("c", adjacentTerminalTab(listOf("a", "b", "c"), 1))
        assertEquals("b", adjacentTerminalTab(listOf("a", "b", "c"), 2))
        assertNull(adjacentTerminalTab(listOf("a"), 0))
        assertEquals(0f, terminalPanelHeight(380f, 0.35f))
        assertEquals(160f, terminalPanelHeight(600f, 0f))
        assertEquals(374f, terminalPanelHeight(600f, 1f))
        assertEquals(210f, terminalPanelHeight(600f, 0.35f))
    }

    @Test
    fun `keyboard actions do not consume shell keys or other modifier combinations`() {
        assertTrue(isTerminalToggleKey(true, true, false, false, false))
        assertFalse(isTerminalToggleKey(true, false, false, false, false))
        assertFalse(isTerminalToggleKey(true, true, false, false, true))
        assertFalse(isTerminalToggleKey(false, true, false, false, false))
        assertFalse(isTerminalToggleKey(true, true, true, false, false))
        assertEquals(-1, terminalTabFocusDelta(true, false, true))
        assertEquals(1, terminalTabFocusDelta(false, true, true))
        assertEquals(0, terminalTabFocusDelta(false, false, true))
        assertEquals(0, terminalTabFocusDelta(true, false, false))
    }

    private class FakeTerminal(
        phase: TerminalPhase = TerminalPhase.RUNNING,
        private val gate: CompletableDeferred<Unit>? = null,
        private val failStart: Boolean = false,
        @Volatile var failShutdown: Boolean = false,
    ) : SessionTerminalHandle {
        override val status = MutableStateFlow(TerminalStatus(phase))
        var starts = 0
        val closes = AtomicInteger()
        override fun start() { starts++; if (failStart) error("private startup detail") }
        override fun requestClose() = Unit
        override suspend fun shutdown() {
            closes.incrementAndGet()
            gate?.await()
            if (failShutdown) error("private cleanup detail")
        }
    }
}
