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
import androidx.compose.foundation.layout.size
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
import com.kzagent.kagent.desktop.terminal.SessionTerminalState
import com.kzagent.kagent.desktop.terminal.TerminalPhase
import com.kzagent.kagent.desktop.terminal.TerminalSettingsProfile
import io.github.composefluent.FluentTheme
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.component.Icon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.TooltipBox
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.component.Text
import java.awt.Cursor
import java.awt.Desktop
import java.net.URI

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
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFluentApi::class, ExperimentalFoundationApi::class)
internal fun SessionTerminalPanel(
    state: SessionTerminalState,
    autoFocus: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusHandled: () -> Unit = {},
) {
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
            TooltipBox(tooltip = { Text("关闭（折叠终端面板）") }) {
                SubtleButton(onClick = onCollapse, modifier = Modifier.size(28.dp), iconOnly = true) {
                    Icon(Icons.Default.Dismiss, contentDescription = "关闭终端面板", modifier = Modifier.size(18.dp))
                }
            }
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
                Text(if (state.status.phase == TerminalPhase.CLOSING) "正在清理终端进程…" else "终端未运行")
            }
        }
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
