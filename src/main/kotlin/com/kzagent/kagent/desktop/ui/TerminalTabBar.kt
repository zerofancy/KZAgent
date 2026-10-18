package com.kzagent.kagent.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.desktop.terminal.BossTermTerminalHandle
import com.kzagent.kagent.desktop.terminal.SessionTerminalState
import com.kzagent.kagent.desktop.terminal.TerminalOperation
import com.kzagent.kagent.desktop.terminal.TerminalPhase
import com.kzagent.kagent.desktop.terminal.TerminalTabState
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Icon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.TabItem
import io.github.composefluent.component.Text
import io.github.composefluent.component.TooltipBox
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowClockwise
import io.github.composefluent.icons.regular.ChevronDown
import io.github.composefluent.icons.regular.Dismiss
import kotlinx.coroutines.launch

private data class TerminalTabEntry(val tab: TerminalTabState, val focusRequester: FocusRequester)

@Composable
@OptIn(ExperimentalFluentApi::class, ExperimentalFoundationApi::class)
internal fun TerminalTabBar(
    state: SessionTerminalState,
    onSelect: (TerminalTabState) -> Unit,
    onClose: (TerminalTabState) -> Unit,
    onCreate: () -> Unit,
    onRestart: (TerminalTabState) -> Unit,
    onCollapse: () -> Unit,
) {
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Lazy measurement can run before this parent recomposes after a list mutation.
    // Freeze item membership and each item's focus binding together, rather than
    // reading the live list against a focus map from a different composition.
    val tabs = state.tabs.toList()
    val entries = remember(state.sessionId, tabs) { tabs.map { TerminalTabEntry(it, FocusRequester()) } }
    val currentEntries by rememberUpdatedState(entries)
    val ids = entries.map { it.tab.tabId }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val edgePadding = 4.dp
            val sizing = with(density) {
                terminalTabSizing((constraints.maxWidth - 2 * edgePadding.roundToPx()).coerceAtLeast(0),
                    ids.size, 140.dp.roundToPx(), 250.dp.roundToPx())
            }
            val tabWidth = with(density) { sizing.width.toDp() }
            val endPadding = edgePadding + with(density) { sizing.endPadding.toDp() }
            LaunchedEffect(state.activeTabId, ids, constraints.maxWidth, sizing) {
                val index = ids.indexOf(state.activeTabId)
                if (index >= 0) {
                    val layout = scroll.layoutInfo
                    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                    if (item == null || item.size != sizing.width || item.offset < 0 ||
                        item.offset + item.size > layout.viewportEndOffset - layout.afterContentPadding) {
                        // BoxWithConstraints subcomposes during measurement; defer the
                        // scroll to the next measure instead of forcing nested layout.
                        scroll.requestScrollToItem(index)
                    }
                }
            }
            Column {
                LazyRow(state = scroll, modifier = Modifier.fillMaxWidth().clipToBounds(),
                    contentPadding = PaddingValues(start = edgePadding, end = endPadding)) {
                    items(entries, key = { it.tab.tabId }) { entry ->
                        val tab = entry.tab
                        val handle = tab.handle as? BossTermTerminalHandle
                        val title = handle?.view?.title?.value?.takeUnless { it == "终端" || it.isBlank() }
                        val label = "终端 ${tab.ordinal}" + (title?.let { " · $it" } ?: "") +
                            (if (tab.status.phase != TerminalPhase.RUNNING || tab.busy) " · ${terminalStatusLabel(tab)}" else "")
                        TooltipBox(tooltip = {
                            Column {
                                Text(label)
                                Text(handle?.view?.workingDirectory?.value ?: tab.workspace.toString())
                                Text(terminalStatusLabel(tab))
                            }
                        }) {
                            TabItem(
                                selected = state.activeTabId == tab.tabId,
                                onSelectedChanged = { onSelect(tab) },
                                modifier = Modifier.width(tabWidth)
                                    .focusRequester(entry.focusRequester)
                                    .semantics { role = Role.Tab; contentDescription = "$label，${terminalStatusLabel(tab)}" }
                                    .onPreviewKeyEvent { event ->
                                        val delta = terminalTabFocusDelta(event.key == Key.DirectionLeft,
                                            event.key == Key.DirectionRight, event.type == KeyEventType.KeyDown)
                                        if (delta == 0) false else {
                                            val index = (ids.indexOf(tab.tabId) + delta).coerceIn(0, ids.lastIndex)
                                            val target = entries[index]
                                            scope.launch {
                                                // An off-screen lazy item has no attached FocusRequester.
                                                scroll.scrollToItem(index)
                                                withFrameNanos { }
                                                // Ignore a delayed request after insertion, removal,
                                                // session navigation or another scroll unmounted it.
                                                if (currentEntries.any { it === target } &&
                                                    scroll.layoutInfo.visibleItemsInfo.any { it.key == target.tab.tabId }) {
                                                    target.focusRequester.requestFocus()
                                                }
                                            }
                                            true
                                        }
                                    },
                                text = { Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailing = {
                                    TooltipBox(tooltip = { Text("关闭终端 ${tab.ordinal}") }) {
                                        SubtleButton(onClick = { onClose(tab) }, disabled = tab.busy || state.deleting,
                                            iconOnly = true, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.Dismiss, "关闭终端 ${tab.ordinal}", Modifier.size(16.dp))
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                if (scroll.canScrollBackward || scroll.canScrollForward) {
                    HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxWidth().height(12.dp).padding(top = 4.dp))
                }
            }
        }
        TooltipBox(tooltip = { Text("新建终端") }) {
            SubtleButton(onClick = onCreate, disabled = state.deleting, iconOnly = true, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "新建终端", Modifier.size(18.dp))
            }
        }
        val current = state.activeTab
        TooltipBox(tooltip = { Text("重启当前终端") }) {
            SubtleButton(onClick = { current?.let(onRestart) }, disabled = current == null || current.busy || state.deleting,
                iconOnly = true, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ArrowClockwise, "重启当前终端", Modifier.size(18.dp))
            }
        }
        TooltipBox(tooltip = { Text("折叠终端面板（保留进程）") }) {
            SubtleButton(onClick = onCollapse, iconOnly = true, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ChevronDown, "折叠终端面板", Modifier.size(18.dp))
            }
        }
    }
    state.activeTab?.let { tab ->
        Text(terminalStatusLabel(tab), color = FluentTheme.colors.text.text.secondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal data class TerminalTabSizing(val width: Int, val endPadding: Int)

/** Fit short rows before scrolling; whole slots avoid an orphan close button at the left end.
 * Pixel sizing plus the trailing remainder keeps the end position aligned at fractional scales.
 */
internal fun terminalTabSizing(availableWidth: Int, tabCount: Int, minimumWidth: Int, maximumWidth: Int): TerminalTabSizing {
    if (availableWidth <= 0) return TerminalTabSizing(0, 0)
    val slots = (availableWidth / minimumWidth).coerceIn(1, tabCount.coerceAtLeast(1))
    val width = (availableWidth / slots).coerceAtMost(maximumWidth)
    val endPadding = if (tabCount > slots) availableWidth - width * slots else 0
    return TerminalTabSizing(width, endPadding)
}

internal fun terminalTabFocusDelta(left: Boolean, right: Boolean, keyDown: Boolean): Int =
    if (!keyDown) 0 else if (left) -1 else if (right) 1 else 0

internal fun terminalStatusLabel(tab: TerminalTabState): String = when (tab.operation) {
    TerminalOperation.CLEANUP_FAILED -> "清理失败"
    TerminalOperation.CLOSING, TerminalOperation.CLEANING_FAILURE -> "关闭中"
    TerminalOperation.RESTARTING -> "重启中"
    TerminalOperation.NONE -> when (tab.status.phase) {
        TerminalPhase.NOT_CREATED -> "未启动"
        TerminalPhase.STARTING -> "启动中"
        TerminalPhase.RUNNING -> "运行中"
        TerminalPhase.EXITED -> "已退出（${tab.status.exitCode?.toString() ?: "未知"}）"
        TerminalPhase.FAILED -> "启动或运行失败"
        TerminalPhase.CLOSING -> "关闭中"
    }
}
