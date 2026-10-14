package com.kzagent.kagent.desktop.ui

import ai.rever.bossterm.compose.ui.ProperTerminal
import ai.rever.bossterm.compose.util.loadTerminalFont
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.desktop.terminal.BossTermTerminalHandle
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import com.kzagent.kagent.desktop.terminal.SessionTerminalState
import com.kzagent.kagent.desktop.terminal.TerminalPhase
import com.kzagent.kagent.desktop.terminal.TerminalSettingsProfile
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Text
import java.awt.Cursor
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

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
@OptIn(ExperimentalComposeUiApi::class)
internal fun SessionTerminalPanel(
    state: SessionTerminalState,
    workspace: Path,
    manager: SessionTerminalManager,
    autoFocus: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusHandled: () -> Unit = {},
) {
    var confirmRestart by remember { mutableStateOf<Boolean?>(null) }
    val handle = state.handle as? BossTermTerminalHandle
    val foreground = FluentTheme.colors.text.text.primary
    val background = FluentTheme.colors.background.layer.default
    val selection = FluentTheme.colors.fillAccent.default
    if (handle != null) {
        SideEffect { TerminalSettingsProfile.updateAppearance(foreground.hex(), background.hex(), selection.hex()) }
    }
    Column(modifier.background(background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(handle?.view?.title?.value ?: "终端", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(terminalStatusLabel(state), color = FluentTheme.colors.text.text.secondary)
            Button(onClick = {
                if (state.status.phase in setOf(TerminalPhase.STARTING, TerminalPhase.RUNNING)) confirmRestart = true
                else manager.closeTerminal(state.sessionId, workspace, restart = true)
            }, disabled = state.status.phase == TerminalPhase.CLOSING) { Text("重启") }
            Button(onClick = onCollapse) { Text("折叠") }
            Button(onClick = {
                if (state.status.phase in setOf(TerminalPhase.STARTING, TerminalPhase.RUNNING)) confirmRestart = false
                else manager.closeTerminal(state.sessionId, workspace)
            }, disabled = state.status.phase == TerminalPhase.CLOSING) { Text("关闭") }
        }
        state.error?.let { Text(it, color = FluentTheme.colors.system.critical, modifier = Modifier.padding(horizontal = 10.dp)) }
        if (handle != null && state.status.phase in setOf(TerminalPhase.STARTING, TerminalPhase.RUNNING, TerminalPhase.EXITED)) {
            var active by remember(handle) { mutableStateOf(autoFocus) }
            LaunchedEffect(autoFocus) {
                if (autoFocus) {
                    active = true
                    onFocusHandled()
                }
            }
            val font = remember(handle) { loadTerminalFont(null) }
            DisposableEffect(handle) {
                handle.view.onVisible()
                onDispose { handle.view.onHidden() }
            }
            ProperTerminal(
                // ProperTerminal auto-focuses every active-tab mount. Keep it inactive on
                // navigation until the user explicitly opens or clicks this terminal.
                tab = handle.view, isActiveTab = active, autoFocus = autoFocus,
                onPaneFocus = { active = true },
                sharedFont = font, onTabTitleChange = {}, enableDebugPanel = false,
                onLinkClick = { info ->
                    runCatching {
                        val uri = URI(info.url)
                        if (uri.scheme?.lowercase() in setOf("http", "https") && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().browse(uri)
                        }
                    }
                    true // Block non-web schemes instead of falling through to the system opener.
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .onFocusChanged {
                        handle.view.isFocused.value = it.hasFocus
                        if (it.hasFocus) active = true
                    }
                    .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { active = true },
            )
        } else {
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (state.status.phase == TerminalPhase.CLOSING) "正在清理终端进程…" else "点击重启以启动终端")
            }
        }
    }
    confirmRestart?.let { restart ->
        ContentDialog(title = if (restart) "重启终端" else "关闭终端", visible = true,
            content = { Text("此操作会结束终端及其中运行的进程，是否继续？") },
            primaryButtonText = if (restart) "重启" else "关闭", closeButtonText = "取消",
            onButtonClick = { button ->
                confirmRestart = null
                if (button == ContentDialogButton.Primary) manager.closeTerminal(state.sessionId, workspace, restart)
            })
    }
}

private fun Color.hex() = "0x" + toArgb().toUInt().toString(16).padStart(8, '0')

private fun terminalStatusLabel(state: SessionTerminalState): String = when (state.status.phase) {
    TerminalPhase.NOT_CREATED -> "未启动"
    TerminalPhase.STARTING -> "启动中"
    TerminalPhase.RUNNING -> "运行中"
    TerminalPhase.EXITED -> "已退出（${state.status.exitCode?.toString() ?: "未知"}）"
    TerminalPhase.FAILED -> "启动失败"
    TerminalPhase.CLOSING -> "关闭中"
}
