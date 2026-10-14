package com.kzagent.kagent.desktop.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CountDownLatch

internal enum class TerminalPhase { NOT_CREATED, STARTING, RUNNING, EXITED, FAILED, CLOSING }
internal data class TerminalStatus(val phase: TerminalPhase, val exitCode: Int? = null)

internal interface SessionTerminalHandle : AutoCloseable {
    val status: StateFlow<TerminalStatus>
    fun start()
    fun requestClose()
    suspend fun shutdown()
    override fun close() = runBlocking { shutdown() }
}

internal class SessionTerminalState(val sessionId: String) {
    var visible by mutableStateOf(false)
    var heightRatio by mutableStateOf(0.35f)
    var status by mutableStateOf(TerminalStatus(TerminalPhase.NOT_CREATED))
    var error by mutableStateOf<String?>(null)
    var handle by mutableStateOf<SessionTerminalHandle?>(null)
    internal var generation = 0L
}

/** Owns shells independently of AgentRuntime and of the panel's composition lifetime. */
internal class SessionTerminalManager(
    private val factory: (Path) -> SessionTerminalHandle,
    private val limit: Int = 8,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    installShutdownHook: Boolean = true,
) : AutoCloseable {
    private val sessions = mutableStateMapOf<String, SessionTerminalState>()
    @Volatile private var closed = false
    private val shutdownComplete = CountDownLatch(1)
    private val shutdownHook = Thread({ close() }, "kzagent-terminal-shutdown")

    init { if (installShutdownHook) Runtime.getRuntime().addShutdownHook(shutdownHook) }

    @Synchronized
    fun stateFor(id: String): SessionTerminalState {
        check(!closed) { "终端管理器已关闭" }
        return sessions.getOrPut(id) { SessionTerminalState(id) }
    }

    fun toggle(id: String, workspace: Path) {
        val state = stateFor(id)
        state.visible = !state.visible
        if (state.visible && state.handle == null) start(state, workspace)
    }

    @Synchronized
    private fun start(state: SessionTerminalState, workspace: Path) {
        if (closed || state.handle != null) return
        if (sessions.values.count { it.status.phase in setOf(TerminalPhase.STARTING, TerminalPhase.RUNNING, TerminalPhase.CLOSING) } >= limit) {
            state.error = "最多同时运行 $limit 个终端，请先关闭已有终端。"
            return
        }
        state.error = null
        state.status = TerminalStatus(TerminalPhase.STARTING)
        val generation = ++state.generation
        try {
            val handle = factory(workspace)
            state.handle = handle
            scope.launch {
                handle.status.collect { status ->
                    if (!closed && sessions[state.sessionId] === state && state.generation == generation && state.handle === handle) {
                        state.status = status
                        if (status.phase == TerminalPhase.FAILED) state.error = "终端启动或运行失败，请检查 Shell 和工作区后重试。"
                    }
                }
            }
            handle.start()
        } catch (_: Exception) {
            // Do not surface raw process errors: their text may contain paths or environment data.
            state.handle?.close()
            state.handle = null
            state.status = TerminalStatus(TerminalPhase.FAILED)
            state.error = "终端无法启动，请检查 Shell 和工作区。"
        }
    }

    fun closeTerminal(id: String, workspace: Path, restart: Boolean = false) {
        val state = stateFor(id)
        if (state.status.phase == TerminalPhase.CLOSING) return
        scope.launch {
            try {
                stop(state)
                if (!closed) {
                    state.visible = restart
                    if (restart) start(state, workspace)
                }
            } catch (_: Exception) {
                state.error = "终端清理失败，请关闭应用后重试。"
            }
        }
    }

    suspend fun closeSession(id: String) {
        val state = sessions[id] ?: return
        stop(state)
        sessions.remove(id)
    }

    private suspend fun stop(state: SessionTerminalState) {
        val handle = state.handle ?: return
        state.status = TerminalStatus(TerminalPhase.CLOSING)
        ++state.generation // Ignore callbacks from the dying shell before awaiting native teardown.
        handle.requestClose()
        withContext(NonCancellable + Dispatchers.IO) { handle.shutdown() }
        state.handle = null
        state.status = TerminalStatus(TerminalPhase.NOT_CREATED)
        state.error = null
    }

    override fun close() {
        val handles = synchronized(this) {
            if (closed) null else {
                closed = true
                sessions.values.mapNotNull { it.handle }
            }
        }
        if (handles == null) {
            // JVM shutdown can race with UI disposal; neither caller may leave before cleanup.
            shutdownComplete.await()
            return
        }
        try {
            handles.forEach { runCatching { it.requestClose() } }
            // Engine shutdown does not await UI callbacks, so root disposal cannot deadlock Main.
            runBlocking(Dispatchers.IO) {
                val results = handles.map { handle -> async { runCatching { handle.shutdown() } } }.awaitAll()
                if (results.any { it.isFailure }) System.err.println("KZAgent: terminal cleanup did not finish successfully")
            }
        } finally {
            scope.cancel()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            shutdownComplete.countDown()
        }
    }
}

internal fun terminalPanelHeight(availableHeight: Float, ratio: Float): Float {
    val maximum = availableHeight - 220f - 6f
    if (maximum < 160f) return 0f
    return (availableHeight * ratio).coerceIn(160f, maximum)
}
