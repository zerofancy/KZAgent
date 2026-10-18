package com.kzagent.kagent.desktop.terminal

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.compose.ConnectionState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.TerminalSession
import ai.rever.bossterm.compose.features.ContextMenuController
import ai.rever.bossterm.compose.ime.IMEState
import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.session.TerminalSessionStack
import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.tabs.TerminalTab
import ai.rever.bossterm.compose.util.normalizeSubmitNewlines
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Uses public model/engine APIs; the full BossTerm application/installer is never constructed. */
internal class BossTermTerminalHandle(
    workspace: Path,
    settings: TerminalSettings,
    private val platform: TerminalProcessPlatform = TerminalProcessPlatform(),
    command: String? = null,
) : SessionTerminalHandle {
    private val validatedWorkspace = workspace.also {
        if (!Files.isDirectory(it) || !Files.isReadable(it)) {
            platform.close()
            throw IllegalArgumentException("终端工作区不存在或不可访问")
        }
    }
    private val closed = AtomicBoolean()
    private val shutdownMutex = Mutex()
    private var engineTerminated = false
    private var viewDisposed = false
    private val terminalStatus = MutableStateFlow(TerminalStatus(TerminalPhase.STARTING))
    override val status = terminalStatus.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val display = ComposeTerminalDisplay(settings)
    private val stack = TerminalSessionStack.create(settings, display)
    private val redrawListener = object : ai.rever.bossterm.terminal.model.TerminalModelListener {
        override fun modelChanged() { display.requestImmediateRedraw() }
    }.also { stack.textBuffer.addModelListener(it) }
    private val tab = TerminalTab(
        title = mutableStateOf("终端"), terminal = stack.terminal, textBuffer = stack.textBuffer,
        display = display, dataStream = stack.dataStream, emulator = stack.emulator,
        processHandle = mutableStateOf(null), workingDirectory = mutableStateOf(workspace.toString()),
        connectionState = mutableStateOf(ConnectionState.Initializing), coroutineScope = scope,
        isFocused = mutableStateOf(false), scrollOffset = mutableStateOf(0),
        searchVisible = mutableStateOf(false), searchQuery = mutableStateOf(""),
        searchMatches = mutableStateOf(emptyList()), currentSearchMatchIndex = mutableStateOf(-1),
        selectionClipboard = mutableStateOf(null), imeState = IMEState(),
        contextMenuController = ContextMenuController(), hyperlinks = mutableStateOf(emptyList()),
        hoveredHyperlink = mutableStateOf(null),
        modelListener = redrawListener,
    )
    val engine = TerminalSessionEngine(
        settings = settings, workingDir = workspace.toString(), command = command,
        stack = stack, platformServices = platform,
    )
    val view: TerminalSession = object : TerminalSession by tab {
        override fun writeUserInput(text: String) {
            if (!closed.get() && status.value.phase == TerminalPhase.RUNNING) engine.writeInput(text)
        }

        override fun pasteText(text: String) {
            val normalized = normalizeSubmitNewlines(text)
            writeUserInput(if (display.bracketedPasteMode.value) "\u001b[200~$normalized\u001b[201~" else normalized)
        }

        override fun dispose() = close()
    }

    init {
        scope.launch {
            engine.state.collect { state ->
                terminalStatus.value = when (state) {
                    TerminalSessionEngine.State.Initializing -> TerminalStatus(TerminalPhase.STARTING)
                    TerminalSessionEngine.State.Connected -> TerminalStatus(TerminalPhase.RUNNING)
                    TerminalSessionEngine.State.Exited -> TerminalStatus(TerminalPhase.EXITED, engine.exitCode)
                    is TerminalSessionEngine.State.Error -> TerminalStatus(TerminalPhase.FAILED)
                }
                tab.connectionState.value = when (state) {
                    TerminalSessionEngine.State.Initializing -> ConnectionState.Initializing
                    TerminalSessionEngine.State.Connected -> {
                        val handle = checkNotNull(engine.processHandle)
                        val adapter = rendererHandle(handle)
                        tab.processHandle.value = adapter
                        ConnectionState.Connected(adapter)
                    }
                    is TerminalSessionEngine.State.Error -> ConnectionState.Error("终端启动或运行失败")
                    TerminalSessionEngine.State.Exited -> {
                        // ProperTerminal replaces Error screens with a reconnect UI. Keep the
                        // display connected to the drained model to retain final output; input
                        // is rejected above and the Fluent toolbar exposes explicit restart.
                        engine.processHandle?.let { raw ->
                            val adapter = rendererHandle(raw)
                            tab.processHandle.value = adapter
                            ConnectionState.Connected(adapter)
                        } ?: ConnectionState.Error("终端已退出")
                    }
                }
            }
        }
        scope.launch { engine.windowTitle.collect { tab.title.value = it.ifBlank { "终端" } } }
        scope.launch { engine.workingDirectory.collect { tab.workingDirectory.value = it } }
    }

    override fun start() = engine.start()

    private fun rendererHandle(raw: PlatformServices.ProcessService.ProcessHandle): PlatformServices.ProcessService.ProcessHandle =
        object : PlatformServices.ProcessService.ProcessHandle by engine.processHandleAdapter(raw) {
            // ProperTerminal kills its processHandle from onDispose. Switching tabs or
            // folding the panel only unmounts a renderer; the manager owns termination
            // through engine.close()/shutdown(), never through this borrowed view.
            override suspend fun kill() = Unit
        }

    override fun requestClose() { closed.set(true); engine.close() }

    override suspend fun shutdown() = shutdownMutex.withLock {
        requestClose()
        try {
            if (!engineTerminated) {
                withTimeout(5_000) { engine.awaitTermination() }
                engineTerminated = true
            }
        } finally {
            // Failed teardown remains retryable: input stays disabled, but the manager
            // retains this handle until engine and platform cleanup both succeed.
            try {
                if (!viewDisposed) {
                    tab.dispose()
                    viewDisposed = true
                }
            } finally {
                scope.cancel()
                platform.close()
            }
        }
    }
}
