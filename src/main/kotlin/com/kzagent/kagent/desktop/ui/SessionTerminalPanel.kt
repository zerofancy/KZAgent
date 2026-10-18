package com.kzagent.kagent.desktop.ui

import ai.rever.bossterm.compose.ui.ProperTerminal
import ai.rever.bossterm.compose.util.loadTerminalFont
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.desktop.terminal.BossTermTerminalHandle
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import com.kzagent.kagent.desktop.terminal.SessionTerminalState
import com.kzagent.kagent.desktop.terminal.TerminalOperation
import com.kzagent.kagent.desktop.terminal.TerminalPhase
import com.kzagent.kagent.desktop.terminal.TerminalSettingsProfile
import com.kzagent.kagent.desktop.terminal.TerminalTabState
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.FluentDialog
import io.github.composefluent.component.Text
import kotlinx.coroutines.launch
import java.awt.Cursor
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

internal data class TerminalFocusRequest(val sessionId: String, val tabId: String?, val requestId: Int)
internal class TerminalFocusState(private val sessionId: String) {
    var request by mutableStateOf<TerminalFocusRequest?>(null)
        private set
    private var sequence = 0
    fun request(tabId: String?): TerminalFocusRequest =
        TerminalFocusRequest(sessionId, tabId, ++sequence).also { request = it }
    fun cancel() { request = null }
    fun consume(requestId: Int) { if (request?.requestId == requestId) cancel() }
    fun retargetIfCurrent(original: TerminalFocusRequest, tabId: String?) {
        if (request == original) request(tabId)
    }
}
private data class TerminalConfirmation(val tabId: String, val generation: Long, val ordinal: Int, val restart: Boolean)

@Composable
internal fun TerminalResizeHandle(onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(6.dp)
        .background(FluentTheme.colors.stroke.divider.default)
        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
        .pointerInput(onDrag, density) {
            detectVerticalDragGestures { change, delta ->
                change.consume()
                onDrag(with(density) { delta.toDp().value })
            }
        })
}

@Composable
internal fun SessionTerminalPanel(
    state: SessionTerminalState,
    manager: SessionTerminalManager,
    workspace: Path,
    focusState: TerminalFocusState,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmation by remember(state.sessionId) { mutableStateOf<TerminalConfirmation?>(null) }
    val uiScope = rememberCoroutineScope()
    fun observeClose(job: kotlinx.coroutines.Job, tabId: String, wasSelected: Boolean) {
        if (wasSelected) {
            val pending = focusState.request(tabId)
            uiScope.launch {
                job.join()
                // A later click, navigation or focus action cancels/replaces this token.
                if (state.tabs.none { it.tabId == tabId }) focusState.retargetIfCurrent(pending, state.activeTabId)
            }
        }
    }
    val create = {
        manager.createTab(state.sessionId, workspace)?.let { focusState.request(it.tabId) }
        Unit
    }
    val perform: (TerminalConfirmation) -> Unit = { target ->
        val selectedAtStart = state.activeTabId
        if (target.restart) {
            manager.restartTab(state.sessionId, target.tabId, target.generation, workspace)?.let {
                if (selectedAtStart == target.tabId) focusState.request(target.tabId)
            }
        } else {
            manager.closeTab(state.sessionId, target.tabId, target.generation)?.let { job ->
                observeClose(job, target.tabId, selectedAtStart == target.tabId)
            }
        }
    }
    val requestAction: (TerminalTabState, Boolean) -> Unit = { tab, restart ->
        val target = TerminalConfirmation(tab.tabId, tab.generation, tab.ordinal, restart)
        if (tab.needsConfirmation) confirmation = target else perform(target)
    }
    Column(modifier.background(FluentTheme.colors.background.layer.default)) {
        TerminalTabBar(state,
            onSelect = { manager.selectTab(state.sessionId, it.tabId); focusState.request(it.tabId) },
            onClose = { requestAction(it, false) }, onCreate = create,
            onRestart = { requestAction(it, true) }, onCollapse = onCollapse)
        state.error?.let { Text(it, color = FluentTheme.colors.system.critical, modifier = Modifier.padding(horizontal = 10.dp)) }
        val tab = state.activeTab
        val focus = focusState.request?.takeIf { it.sessionId == state.sessionId && it.tabId == tab?.tabId }
        val onFocusHandled = { focus?.let { focusState.consume(it.requestId) }; Unit }
        if (tab == null) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(focus?.requestId) {
                if (focus != null) { focusRequester.requestFocus(); onFocusHandled() }
            }
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("暂无终端")
                Button(onClick = create, disabled = state.deleting, modifier = Modifier.padding(top = 8.dp).focusRequester(focusRequester)) {
                    Text("新建终端")
                }
            }
        } else {
            tab.error?.let { Text(it, color = FluentTheme.colors.system.critical, modifier = Modifier.padding(horizontal = 10.dp)) }
            if (tab.status.phase == TerminalPhase.EXITED && !tab.busy) {
                val restartFocus = remember(tab.tabId) { FocusRequester() }
                LaunchedEffect(focus?.requestId) {
                    if (focus != null) { restartFocus.requestFocus(); onFocusHandled() }
                }
                Button(onClick = { requestAction(tab, true) }, modifier = Modifier.padding(horizontal = 10.dp).focusRequester(restartFocus)) {
                    Text("重启终端")
                }
            }
            key(tab.tabId, tab.generation) {
                val handle = tab.handle as? BossTermTerminalHandle
                if (handle != null && !tab.busy && tab.status.phase in setOf(TerminalPhase.STARTING, TerminalPhase.RUNNING, TerminalPhase.EXITED)) {
                    ActiveTerminal(handle, focus.takeUnless { tab.status.phase == TerminalPhase.EXITED },
                        onFocusHandled, Modifier.weight(1f).fillMaxWidth())
                } else {
                    val actionFocus = remember { FocusRequester() }
                    val canAct = !state.deleting && (!tab.busy || tab.operation == TerminalOperation.CLEANUP_FAILED)
                    LaunchedEffect(focus?.requestId, canAct) {
                        if (focus != null && canAct) { actionFocus.requestFocus(); onFocusHandled() }
                    }
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (canAct) Button(modifier = Modifier.focusRequester(actionFocus), onClick = {
                            if (tab.operation == TerminalOperation.CLEANUP_FAILED) {
                                val wasSelected = state.activeTabId == tab.tabId
                                manager.retryCleanup(state.sessionId, tab.tabId)?.let { observeClose(it, tab.tabId, wasSelected) }
                            }
                            else requestAction(tab, true)
                        }) { Text(if (tab.operation == TerminalOperation.CLEANUP_FAILED) "重试清理" else "重试启动") }
                        else Text(if (tab.operation == TerminalOperation.RESTARTING) "正在重启终端…" else "正在清理终端进程…")
                    }
                }
            }
        }
    }
    confirmation?.let { target ->
        val current = state.tabs.firstOrNull { it.tabId == target.tabId }
        if (current == null || current.generation != target.generation || state.deleting) {
            LaunchedEffect(target) { confirmation = null }
        } else TerminalActionDialog(target.ordinal, target.restart,
            onCancel = { confirmation = null },
            onConfirm = { confirmation = null; perform(target) })
    }
}

/** The locked Fluent ContentDialog has no default-focus parameter; use FluentDialog to focus Cancel. */
@Composable
private fun TerminalActionDialog(ordinal: Int, restart: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    FluentDialog(visible = true) {
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        Column(Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onCancel(); true } else false
        }) {
            Column(Modifier.fillMaxWidth().background(FluentTheme.colors.background.layer.alt).padding(24.dp)) {
                Text("${if (restart) "重启" else "关闭"}终端 $ordinal？", style = FluentTheme.typography.subtitle)
                Text(if (restart) "这会结束该终端及其中的进程，清空输出，并从会话工作区启动新 Shell。"
                    else "这会结束该终端及其中的进程。", modifier = Modifier.padding(top = 12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(if (restart) "重启终端" else "关闭终端") }
                Button(onClick = onCancel, modifier = Modifier.weight(1f).focusRequester(cancelFocus)) { Text("取消") }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
private fun ActiveTerminal(
    handle: BossTermTerminalHandle,
    focusRequest: TerminalFocusRequest?,
    onFocusHandled: () -> Unit,
    modifier: Modifier,
) {
    val foreground = FluentTheme.colors.text.text.primary
    val background = FluentTheme.colors.background.layer.default
    val selection = FluentTheme.colors.fillAccent.default
    SideEffect { TerminalSettingsProfile.updateAppearance(foreground.hex(), background.hex(), selection.hex()) }
    var active by remember(handle) { mutableStateOf(false) }
    val font = remember(handle) { loadTerminalFont(null) }
    DisposableEffect(handle) {
        handle.view.onVisible()
        onDispose { handle.view.isFocused.value = false; handle.view.onHidden() }
    }
    ProperTerminal(
        // Mounting during navigation must not request focus; explicit user actions may do so.
        tab = handle.view, isActiveTab = active || focusRequest != null, autoFocus = focusRequest != null,
        onPaneFocus = { active = true }, sharedFont = font, onTabTitleChange = {}, enableDebugPanel = false,
        onLinkClick = { info ->
            runCatching {
                val uri = URI(info.url)
                if (uri.scheme?.lowercase() in setOf("http", "https") && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri)
            }
            true // Do not fall through to system handlers for non-web schemes.
        },
        modifier = modifier.onFocusChanged {
            handle.view.isFocused.value = it.hasFocus
            active = it.hasFocus
            // ProperTerminal delays focus by 50/200 ms. Consuming before focus arrives
            // would cancel that effect, especially when selecting the same mounted tab.
            if (it.hasFocus && focusRequest != null) onFocusHandled()
        }.onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { active = true },
    )
}

private fun Color.hex() = "0x" + toArgb().toUInt().toString(16).padStart(8, '0')
