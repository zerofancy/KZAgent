package com.kzagent.kagent.desktop.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch

internal enum class TerminalPhase { NOT_CREATED, STARTING, RUNNING, EXITED, FAILED, CLOSING }
internal data class TerminalStatus(val phase: TerminalPhase, val exitCode: Int? = null)
internal enum class TerminalOperation { NONE, CLOSING, RESTARTING, CLEANING_FAILURE, CLEANUP_FAILED }

internal interface SessionTerminalHandle : AutoCloseable {
    val status: StateFlow<TerminalStatus>
    fun start()
    fun requestClose()
    suspend fun shutdown()
    override fun close() = runBlocking { shutdown() }
}

internal class TerminalTabState(val tabId: String, val ordinal: Int, workspace: Path) {
    var workspace by mutableStateOf(workspace)
        internal set
    var status by mutableStateOf(TerminalStatus(TerminalPhase.NOT_CREATED))
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var handle by mutableStateOf<SessionTerminalHandle?>(null)
        internal set
    var generation by mutableStateOf(0L)
        internal set
    var operation by mutableStateOf(TerminalOperation.NONE)
        internal set
    internal var collector: Job? = null
    val busy: Boolean get() = operation != TerminalOperation.NONE
    val needsConfirmation: Boolean get() = status.phase in setOf(TerminalPhase.STARTING, TerminalPhase.RUNNING)
}

internal class SessionTerminalState(val sessionId: String) {
    var visible by mutableStateOf(false)
    var heightRatio by mutableStateOf(0.35f)
    val tabs = mutableStateListOf<TerminalTabState>()
    var activeTabId by mutableStateOf<String?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var deleting by mutableStateOf(false)
        internal set
    internal var hasCreatedTab = false
    internal var nextOrdinal = 1
    val activeTab: TerminalTabState? get() = tabs.firstOrNull { it.tabId == activeTabId }
}

/** Shell ownership outlives every terminal view, including views removed during cleanup. */
internal class SessionTerminalManager(
    private val factory: (Path) -> SessionTerminalHandle,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    installShutdownHook: Boolean = true,
) : AutoCloseable {
    private val sessions = mutableStateMapOf<String, SessionTerminalState>()
    // Cleanup must never need Main: root disposal can synchronously wait for it on Main.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val owned = mutableMapOf<SessionTerminalHandle, OwnedTerminal>()
    @Volatile private var closed = false
    private val shutdownComplete = CountDownLatch(1)
    private val shutdownHook = Thread({ close() }, "kzagent-terminal-shutdown")

    private inner class OwnedTerminal(val handle: SessionTerminalHandle) {
        private var attempt: Deferred<Result<Unit>>? = null
        private var succeeded = false

        @Synchronized
        fun shutdown(): Deferred<Result<Unit>> {
            attempt?.let { if (!it.isCompleted || succeeded) return it }
            return cleanupScope.async {
                val result = runCatching {
                    // Still await native teardown if requesting termination itself fails.
                    runCatching { handle.requestClose() }
                    handle.shutdown()
                }
                synchronized(this@OwnedTerminal) { succeeded = result.isSuccess }
                result
            }.also { attempt = it }
        }
    }

    init { if (installShutdownHook) Runtime.getRuntime().addShutdownHook(shutdownHook) }

    @Synchronized
    fun stateFor(id: String): SessionTerminalState {
        check(!closed) { "终端管理器已关闭" }
        return sessions.getOrPut(id) { SessionTerminalState(id) }
    }

    @Synchronized
    fun toggle(id: String, workspace: Path) {
        val state = stateFor(id)
        state.visible = !state.visible
        if (state.visible && !state.hasCreatedTab) createTab(id, workspace)
    }

    @Synchronized
    fun createTab(id: String, workspace: Path): TerminalTabState? {
        if (closed) return null
        val state = stateFor(id)
        if (state.deleting) return null
        val tab = TerminalTabState(UUID.randomUUID().toString(), state.nextOrdinal++, workspace)
        state.tabs.add(tab)
        state.activeTabId = tab.tabId
        state.hasCreatedTab = true
        state.visible = true
        state.error = null
        start(state, tab, workspace)
        return tab
    }

    @Synchronized
    fun selectTab(id: String, tabId: String) {
        val state = sessions[id] ?: return
        if (!closed && !state.deleting && state.tabs.any { it.tabId == tabId }) state.activeTabId = tabId
    }

    private fun start(state: SessionTerminalState, tab: TerminalTabState, workspace: Path) {
        tab.workspace = workspace
        tab.operation = TerminalOperation.NONE
        tab.error = null
        tab.status = TerminalStatus(TerminalPhase.STARTING)
        val generation = ++tab.generation
        try {
            val handle = factory(workspace)
            owned[handle] = OwnedTerminal(handle)
            tab.handle = handle
            handle.start()
            tab.collector = scope.launch {
                handle.status.collect { status ->
                    synchronized(this@SessionTerminalManager) {
                        if (isCurrent(state, tab) && tab.generation == generation && tab.handle === handle) {
                            tab.status = status
                            if (status.phase == TerminalPhase.FAILED) startupFailed(state, tab)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            startupFailed(state, tab)
        }
    }

    private fun isCurrent(state: SessionTerminalState, tab: TerminalTabState): Boolean =
        !closed && sessions[state.sessionId] === state && tab in state.tabs

    private fun startupFailed(state: SessionTerminalState, tab: TerminalTabState) {
        tab.status = TerminalStatus(TerminalPhase.FAILED)
        tab.error = "终端启动或运行失败，请检查 Shell 和工作区后重试。"
        if (tab.operation != TerminalOperation.NONE) return
        tab.operation = TerminalOperation.CLEANING_FAILURE
        ++tab.generation
        scope.launch {
            val success = stop(state, tab)
            synchronized(this@SessionTerminalManager) {
                if (isCurrent(state, tab) && success) tab.operation = TerminalOperation.NONE
            }
        }
    }

    /** A stale confirmation must not act on a replacement handle. */
    @Synchronized
    fun closeTab(id: String, tabId: String, expectedGeneration: Long): Job? =
        operate(id, tabId, expectedGeneration, restartWorkspace = null)

    @Synchronized
    fun restartTab(id: String, tabId: String, expectedGeneration: Long, workspace: Path): Job? =
        operate(id, tabId, expectedGeneration, restartWorkspace = workspace)

    @Synchronized
    fun retryCleanup(id: String, tabId: String): Job? {
        val tab = sessions[id]?.tabs?.firstOrNull { it.tabId == tabId } ?: return null
        if (tab.operation != TerminalOperation.CLEANUP_FAILED) return null
        return operate(id, tabId, tab.generation, restartWorkspace = null, retry = true)
    }

    private fun operate(
        id: String, tabId: String, expectedGeneration: Long, restartWorkspace: Path?, retry: Boolean = false,
    ): Job? {
        val state = sessions[id] ?: return null
        val tab = state.tabs.firstOrNull { it.tabId == tabId } ?: return null
        if (closed || state.deleting || tab.generation != expectedGeneration || (tab.busy && !retry)) return null
        state.error = null
        tab.operation = if (restartWorkspace == null) TerminalOperation.CLOSING else TerminalOperation.RESTARTING
        tab.status = TerminalStatus(TerminalPhase.CLOSING)
        ++tab.generation
        tab.collector?.cancel()
        tab.handle?.let { runCatching { it.requestClose() } }
        return scope.launch {
            val success = stop(state, tab)
            synchronized(this@SessionTerminalManager) {
                if (!isCurrent(state, tab) || state.deleting || !success) return@synchronized
                if (restartWorkspace != null) start(state, tab, restartWorkspace)
                else removeTab(state, tab)
            }
        }
    }

    private suspend fun stop(state: SessionTerminalState, tab: TerminalTabState): Boolean {
        val resource = synchronized(this) { tab.handle?.let { owned[it] } }
        val result = withContext(NonCancellable) { resource?.shutdown()?.await() ?: Result.success(Unit) }
        synchronized(this) {
            if (result.isSuccess && resource != null) owned.remove(resource.handle)
            if (!isCurrent(state, tab)) return result.isSuccess
            if (result.isSuccess) {
                tab.collector?.cancel()
                tab.collector = null
                tab.handle = null
            } else {
                tab.operation = TerminalOperation.CLEANUP_FAILED
                tab.error = "终端清理失败，部分进程可能仍在运行。请重试清理；部分终端可能已关闭。"
            }
        }
        return result.isSuccess
    }

    private fun removeTab(state: SessionTerminalState, tab: TerminalTabState) {
        val index = state.tabs.indexOf(tab)
        if (index < 0) return
        if (state.activeTabId == tab.tabId) {
            state.activeTabId = adjacentTerminalTab(state.tabs.map { it.tabId }, index)
        }
        state.tabs.remove(tab)
    }

    suspend fun closeSession(id: String) = withContext(NonCancellable) {
        val state = synchronized(this@SessionTerminalManager) {
            if (closed) return@withContext
            val state = sessions[id] ?: return@withContext
            state.deleting = true
            state.tabs.forEach { tab ->
                ++tab.generation
                tab.collector?.cancel()
                tab.operation = TerminalOperation.CLOSING
                tab.status = TerminalStatus(TerminalPhase.CLOSING)
                tab.handle?.let { runCatching { it.requestClose() } }
            }
            state
        }
        // Attempt every terminal, even when one native teardown fails.
        val results = state.tabs.toList().map { tab -> async { tab to stop(state, tab) } }.awaitAll()
        synchronized(this@SessionTerminalManager) {
            if (closed) return@synchronized
            results.filter { it.second }.forEach { removeTab(state, it.first) }
            // Keep the empty deleting state until repository deletion completes. A
            // recomposition in that interval must not recreate shells for this id.
            if (results.any { !it.second }) {
                state.deleting = false
                state.error = "部分终端清理失败，会话已保留；其他终端可能已关闭。请重试清理后再删除会话。"
                error(state.error!!)
            }
        }
    }

    @Synchronized
    fun finishSessionDeletion(id: String, deleted: Boolean) {
        if (closed) return
        val state = sessions[id] ?: return
        if (deleted && state.tabs.isEmpty()) sessions.remove(id)
        else if (!deleted) state.deleting = false
    }

    override fun close() {
        val resources = synchronized(this) {
            if (closed) null else { closed = true; owned.values.toList() }
        }
        if (resources == null) { shutdownComplete.await(); return }
        try {
            resources.forEach { runCatching { it.handle.requestClose() } }
            runBlocking(Dispatchers.IO) {
                val results = resources.map { it.shutdown() }.awaitAll()
                if (results.any { it.isFailure }) System.err.println("KZAgent: terminal cleanup did not finish successfully")
            }
        } finally {
            scope.cancel()
            cleanupScope.cancel()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            shutdownComplete.countDown()
        }
    }
}

internal fun adjacentTerminalTab(ids: List<String>, removedIndex: Int): String? =
    ids.getOrNull(removedIndex + 1) ?: ids.getOrNull(removedIndex - 1)

internal fun terminalPanelHeight(availableHeight: Float, ratio: Float): Float {
    val maximum = availableHeight - 220f - 6f
    if (maximum < 160f) return 0f
    return (availableHeight * ratio).coerceIn(160f, maximum)
}
