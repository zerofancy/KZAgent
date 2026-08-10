package com.kzagent.kagent.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.MenuItem
import io.github.composefluent.component.NavigationDisplayMode
import io.github.composefluent.component.NavigationView
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.SideNavHeader
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.menuItemSeparator
import io.github.composefluent.component.rememberNavigationState
import io.github.composefluent.component.Text as FluentText
import io.github.composefluent.component.Icon as FluentIcon
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowDown
import io.github.composefluent.icons.regular.ArrowUp
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.Document
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.Rename
import io.github.composefluent.icons.regular.Settings
import java.nio.file.Files
import java.nio.file.Path

internal val NavigationCompactBreakpoint = 1000.dp

internal fun navigationDisplayModeForWidth(width: androidx.compose.ui.unit.Dp): NavigationDisplayMode =
    if (width < NavigationCompactBreakpoint) {
        NavigationDisplayMode.LeftCompact
    } else {
        NavigationDisplayMode.Left
    }

internal fun isSessionNavigationSelected(
    settingsSelected: Boolean,
    activeIndex: Int,
    sessionIndex: Int,
): Boolean = !settingsSelected && activeIndex == sessionIndex

internal fun shouldCollapseNavigationAfterDestination(displayMode: NavigationDisplayMode): Boolean =
    displayMode == NavigationDisplayMode.LeftCompact

private data class SessionSortEntry(
    val index: Int,
    val session: SessionData,
    val updatedAtMillis: Long,
)

private data class SessionGroup(
    val workspace: Path,
    val sessions: List<SessionSortEntry>,
    val updatedAtMillis: Long,
)

private sealed interface SessionNavigationRow {
    val key: String
    val contentType: String
}

private data class SessionWorkspaceHeader(
    override val key: String,
    override val contentType: String = "session-workspace-header",
    val workspace: Path,
    val expanded: Boolean,
    val workspaceKey: String,
) : SessionNavigationRow

private data class SessionEntryRow(
    override val key: String,
    override val contentType: String = "session-entry",
    val entry: SessionSortEntry,
) : SessionNavigationRow

@Composable
internal fun KZAgentNavigationView(
    sessions: List<SessionData>,
    activeIndex: Int,
    sessionWorkspaceExpanded: Map<String, Boolean>,
    settingsSelected: Boolean,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onDeleteSession: (Int) -> Unit,
    onRenameSession: (Int) -> Unit,
    onChooseWorkspace: () -> Unit,
    onWorkspaceExpandedChanged: (String, Boolean) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    pane: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val displayMode = navigationDisplayModeForWidth(maxWidth)
        val navigationState = rememberNavigationState(
            initialExpanded = displayMode == NavigationDisplayMode.Left,
        )
        val activeSessionId = sessions.getOrNull(activeIndex)?.id
        val sortedSessionGroups = sessions
            .mapIndexed { index, session ->
                val updatedAt = runCatching {
                    Files.getLastModifiedTime(session.sessionFile).toMillis()
                }.getOrDefault(0L)
                SessionSortEntry(index, session, updatedAt)
            }
            .groupBy { it.session.workspace }
            .map { (workspace, groupItems) ->
                val orderedSessions = groupItems.sortedByDescending { it.updatedAtMillis }
                SessionGroup(
                    workspace = workspace,
                    sessions = orderedSessions,
                    updatedAtMillis = orderedSessions.firstOrNull()?.updatedAtMillis ?: 0L,
                )
            }
            .sortedByDescending { it.updatedAtMillis }
        val sortedRows = sortedSessionGroups.flatMap { group ->
            val workspaceKey = sessionWorkspaceKey(group.workspace)
            val expanded = sessionWorkspaceExpanded[workspaceKey] != false
            buildList {
                add(
                    SessionWorkspaceHeader(
                        "group-${workspaceKey}",
                        workspace = group.workspace,
                        expanded = expanded,
                        workspaceKey = workspaceKey,
                    ),
                )
                if (expanded) {
                    addAll(
                        group.sessions.map { entry ->
                            SessionEntryRow("session-${entry.session.id}", entry = entry)
                        },
                    )
                }
            }
        }

        fun runDestinationAction(action: () -> Unit) {
            action()
            if (shouldCollapseNavigationAfterDestination(displayMode)) {
                navigationState.expanded = false
            }
        }

        NavigationView(
            modifier = Modifier.fillMaxSize(),
            displayMode = displayMode,
            state = navigationState,
            title = { FluentText("KZAgent", maxLines = 1) },
            menuItems = {
                item(key = "new-session") {
                    MenuItem(
                        selected = false,
                        onClick = { runDestinationAction(onAddSession) },
                        text = { FluentText("新建会话", maxLines = 1) },
                        icon = { FluentIcon(Icons.Default.Add, contentDescription = null) },
                    )
                }
                item(key = "choose-workspace") {
                    MenuItem(
                        selected = false,
                        onClick = { runDestinationAction(onChooseWorkspace) },
                        text = { FluentText("切换工作区", maxLines = 1) },
                        icon = { FluentIcon(Icons.Default.Folder, contentDescription = null) },
                    )
                }
                menuItemSeparator(key = "session-separator")
                item(key = "session-list-header") {
                    SideNavHeader {
                        FluentText("会话列表", maxLines = 1)
                    }
                }
                items(
                    count = sortedRows.size,
                    key = { sortedRows[it].key },
                    contentType = { sortedRows[it].contentType },
                ) { rowIndex ->
                    when (val row = sortedRows[rowIndex]) {
                        is SessionWorkspaceHeader -> {
                            SideNavHeader(
                                modifier = Modifier.clickable {
                                    onWorkspaceExpandedChanged(row.workspaceKey, !row.expanded)
                                },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FluentText(workspaceProjectName(row.workspace), maxLines = 1)
                                    FluentIcon(
                                        imageVector = if (row.expanded) Icons.Default.ArrowUp else Icons.Default.ArrowDown,
                                        contentDescription = if (row.expanded) "收起分组" else "展开分组",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }

                        is SessionEntryRow -> {
                            val session = row.entry.session
                            MenuItem(
                                selected = activeSessionId == session.id && !settingsSelected,
                                onClick = { runDestinationAction { onSelectSession(row.entry.index) } },
                                text = { FluentText(session.name, maxLines = 1) },
                                icon = { FluentIcon(Icons.Default.Document, contentDescription = null) },
                                badge = {
                                    SessionNavigationBadge(
                                        busy = session.isBusy,
                                        showActions = navigationState.expanded,
                                        onRename = { onRenameSession(row.entry.index) },
                                        onDelete = { onDeleteSession(row.entry.index) },
                                    )
                                },
                            )
                        }
                    }
                }
            },
            footerItems = {
                item(key = "settings") {
                    MenuItem(
                        selected = settingsSelected,
                        onClick = { runDestinationAction(onSettings) },
                        text = { FluentText("设置", maxLines = 1) },
                        icon = { FluentIcon(Icons.Default.Settings, contentDescription = null) },
                    )
                }
            },
            pane = pane,
        )
    }
}

@Composable
private fun SessionNavigationBadge(
    busy: Boolean,
    showActions: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            ProgressRing(size = ProgressRingSize.Small)
        }
        if (showActions) {
            val actionIconColor = FluentTheme.colors.text.text.secondary.copy(alpha = 0.65f)
            SubtleButton(
                onClick = onRename,
                modifier = Modifier.size(28.dp),
                iconOnly = true,
            ) {
                FluentIcon(
                    Icons.Default.Rename,
                    contentDescription = "重命名会话",
                    modifier = Modifier.size(14.dp),
                    tint = actionIconColor,
                )
            }
            SubtleButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp),
                iconOnly = true,
            ) {
                FluentIcon(
                    Icons.Default.Delete,
                    contentDescription = "删除会话",
                    modifier = Modifier.size(14.dp),
                    tint = actionIconColor,
                )
            }
        }
    }
}
