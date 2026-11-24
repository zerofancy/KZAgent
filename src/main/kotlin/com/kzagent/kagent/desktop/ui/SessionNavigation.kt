package com.kzagent.kagent.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.MenuItem
import io.github.composefluent.component.NavigationDisplayMode
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.background.Layer
import io.github.composefluent.background.Mica
import io.github.composefluent.component.NavigationDefaults
import io.github.composefluent.component.NavigationMenuItemScope
import io.github.composefluent.component.NavigationState
import io.github.composefluent.component.SideNav
import io.github.composefluent.component.SideNavHeaderArea
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.SideNavHeader
import io.github.composefluent.component.SideNavItem
import io.github.composefluent.component.TooltipBox
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.rememberNavigationState
import io.github.composefluent.component.Text as FluentText
import io.github.composefluent.component.Icon as FluentIcon
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowDown
import io.github.composefluent.icons.regular.ArrowUp
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.PlugConnected
import io.github.composefluent.icons.regular.PuzzlePiece
import io.github.composefluent.icons.regular.Rename
import io.github.composefluent.icons.regular.Settings
import java.nio.file.Files

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
    skillsSelected: Boolean = false,
    mcpSelected: Boolean = false,
): Boolean = !settingsSelected && !skillsSelected && !mcpSelected && activeIndex == sessionIndex

internal fun shouldCollapseNavigationAfterDestination(displayMode: NavigationDisplayMode): Boolean =
    displayMode == NavigationDisplayMode.LeftCompact

// 编辑/删除按钮默认隐藏：仅在侧栏展开且鼠标悬停该会话行时显示，
// 避免常驻按钮挤占标题、也降低误触概率。
internal fun shouldShowSessionEntryActions(expanded: Boolean, hovered: Boolean): Boolean = expanded && hovered

internal data class SessionSortEntry(
    val index: Int,
    val session: SessionData,
    val updatedAtMillis: Long,
)

internal data class SessionProjectGroup(
    val project: Project,
    val sessions: List<SessionSortEntry>,
    val updatedAtMillis: Long,
)

/** Project IDs, not paths or display names, are the grouping and expansion identity. */
internal fun sessionProjectGroups(
    projects: List<Project>,
    sessions: List<SessionData>,
    updatedAtMillis: (SessionData) -> Long = { session ->
        runCatching { Files.getLastModifiedTime(session.sessionFile).toMillis() }.getOrDefault(0L)
    },
): List<SessionProjectGroup> {
    val entries = sessions.mapIndexed { index, session ->
        SessionSortEntry(index, session, updatedAtMillis(session))
    }.groupBy { it.session.projectId }
    return projects.map { project ->
        val ordered = entries[project.id].orEmpty()
            .sortedWith(compareByDescending<SessionSortEntry> { it.updatedAtMillis }.thenBy { it.session.id })
        SessionProjectGroup(project, ordered, ordered.firstOrNull()?.updatedAtMillis ?: project.createdAt.toEpochMilli())
    }.sortedWith(compareByDescending<SessionProjectGroup> { it.updatedAtMillis }.thenBy { it.project.id })
}

@Composable
internal fun KZAgentNavigationView(
    sessions: List<SessionData>,
    projects: List<Project>,
    onCreateProject: () -> Unit,
    onRenameProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onAddSessionInProject: (String) -> Unit,
    activeIndex: Int,
    sessionWorkspaceExpanded: Map<String, Boolean>,
    settingsSelected: Boolean,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onDeleteSession: (Int) -> Unit,
    onRenameSession: (Int) -> Unit,
    onWorkspaceExpandedChanged: (String, Boolean) -> Unit,
    onSettings: () -> Unit,
    skillsSelected: Boolean = false,
    onSkills: () -> Unit = {},
    mcpSelected: Boolean = false,
    onMcp: () -> Unit = {},
    modifier: Modifier = Modifier,
    pane: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val displayMode = navigationDisplayModeForWidth(maxWidth)
        val navigationState = rememberNavigationState(
            initialExpanded = displayMode == NavigationDisplayMode.Left,
        )
        val sortedSessionGroups = sessionProjectGroups(projects, sessions)

        fun runDestinationAction(action: () -> Unit) {
            action()
            if (shouldCollapseNavigationAfterDestination(displayMode)) {
                navigationState.expanded = false
            }
        }

        SessionNavigationLayout(
            modifier = Modifier.fillMaxSize(),
            displayMode = displayMode,
            state = navigationState,
            title = { FluentText("KZAgent", maxLines = 1) },
            fixedHeader = {
                SideNavItem(
                    selected = activeIndex == -1 && !settingsSelected && !skillsSelected && !mcpSelected,
                    onClick = { runDestinationAction(onAddSession) },
                    icon = { FluentIcon(Icons.Default.Add, contentDescription = null) },
                ) {
                    FluentText("创建新会话", maxLines = 1)
                }
                SideNavItem(
                    selected = false,
                    onClick = { runDestinationAction(onCreateProject) },
                    icon = { FluentIcon(Icons.Default.Add, contentDescription = "新建项目") },
                ) { FluentText("新建项目", maxLines = 1) }
                SideNavHeader { FluentText("项目列表", maxLines = 1) }
            },
            menuItems = {
                sortedSessionGroups.forEach { group ->
                    key("project-${group.project.id}") {
                        val project = group.project
                        val expanded = sessionWorkspaceExpanded[project.id] != false
                        ProjectNavigationHeader(
                            project = project,
                            expanded = expanded,
                            navigationExpanded = navigationState.expanded,
                            empty = group.sessions.isEmpty(),
                            onToggle = { onWorkspaceExpandedChanged(project.id, !expanded) },
                            onExpandNavigation = { navigationState.expanded = true },
                            onAdd = { runDestinationAction { onAddSessionInProject(project.id) } },
                            onRename = { onRenameProject(project.id) },
                            onDelete = { onDeleteProject(project.id) },
                        )
                        if (expanded) {
                            if (group.sessions.isEmpty() && navigationState.expanded) {
                                FluentText("暂无会话", modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                                    color = FluentTheme.colors.text.text.secondary,
                                    style = FluentTheme.typography.caption)
                            }
                            group.sessions.forEach { entry ->
                                key("session-${entry.session.id}") {
                                    val session = entry.session
                                    SessionNavigationItem(
                                        session = session,
                                        projectName = project.name,
                                        indicatorState = navigationState.indicatorState,
                                        expanded = navigationState.expanded,
                                        selected = isSessionNavigationSelected(settingsSelected, activeIndex, entry.index, skillsSelected, mcpSelected),
                                        onClick = { runDestinationAction { onSelectSession(entry.index) } },
                                        badge = { hovered ->
                                            SessionNavigationBadge(
                                                busy = session.isBusy,
                                                showActions = shouldShowSessionEntryActions(navigationState.expanded, hovered),
                                                onRename = { onRenameSession(entry.index) },
                                                onDelete = { onDeleteSession(entry.index) },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            footerItems = {
                MenuItem(
                    selected = mcpSelected,
                    onClick = { runDestinationAction(onMcp) },
                    text = { FluentText("MCP", maxLines = 1) },
                    icon = { FluentIcon(Icons.Default.PlugConnected, contentDescription = null) },
                )
                MenuItem(
                    selected = skillsSelected,
                    onClick = { runDestinationAction(onSkills) },
                    text = { FluentText("Skills", maxLines = 1) },
                    icon = { FluentIcon(Icons.Default.PuzzlePiece, contentDescription = null) },
                )
                MenuItem(
                    selected = settingsSelected,
                    onClick = { runDestinationAction(onSettings) },
                    text = { FluentText("设置", maxLines = 1) },
                    icon = { FluentIcon(Icons.Default.Settings, contentDescription = null) },
                )
            },
            pane = pane,
        )
    }
}

@OptIn(ExperimentalFluentApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ProjectNavigationHeader(
    project: Project,
    expanded: Boolean,
    navigationExpanded: Boolean,
    empty: Boolean,
    onToggle: () -> Unit,
    onExpandNavigation: () -> Unit,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val identity = "项目：${project.name}\n${project.workspace}\nID：${project.id}"
    TooltipBox(tooltip = { FluentText(identity) }) {
        if (navigationExpanded) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Only the title toggles the group. Independent action buttons never toggle it.
                SubtleButton(onClick = onToggle, modifier = Modifier.weight(1f).semantics {
                    contentDescription = "$identity\n${if (expanded) "收起项目" else "展开项目"}"
                }) {
                    FluentIcon(if (expanded) Icons.Default.ArrowUp else Icons.Default.ArrowDown,
                        contentDescription = null, modifier = Modifier.size(12.dp))
                    FluentText(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
                ProjectAction("在项目 ${project.name} 中新建会话", Icons.Default.Add, onClick = onAdd)
                ProjectAction("重命名项目 ${project.name}", Icons.Default.Rename, onClick = onRename)
                ProjectAction(if (empty) "删除项目 ${project.name}" else "请先删除项目内的会话",
                    Icons.Default.Delete, disabled = !empty, onClick = onDelete)
            }
        } else {
            // Compact navigation keeps a keyboard-accessible route to all project actions.
            SubtleButton(onClick = onExpandNavigation, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "$identity\n展开导航以管理项目"
            }) { FluentText(sessionNavigationShortLabel(project.name), maxLines = 1,
                style = FluentTheme.typography.caption) }
        }
    }
}

@OptIn(ExperimentalFluentApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ProjectAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    disabled: Boolean = false,
    onClick: () -> Unit,
) {
    TooltipBox(tooltip = { FluentText(label) }) {
        SubtleButton(onClick = onClick, disabled = disabled, iconOnly = true, modifier = Modifier.size(28.dp)) {
            FluentIcon(icon, contentDescription = label, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalFluentApi::class)
private fun SessionNavigationLayout(
    displayMode: NavigationDisplayMode,
    state: NavigationState,
    title: @Composable () -> Unit,
    fixedHeader: @Composable () -> Unit,
    menuItems: @Composable NavigationMenuItemScope.() -> Unit,
    footerItems: @Composable NavigationMenuItemScope.() -> Unit,
    modifier: Modifier = Modifier,
    pane: @Composable () -> Unit,
) {
    val compact = displayMode == NavigationDisplayMode.LeftCompact
    val itemScope = remember(displayMode) {
        object : NavigationMenuItemScope {
            override val displayMode = displayMode
            override val isFooter = false
        }
    }
    val footerScope = remember(displayMode) {
        object : NavigationMenuItemScope {
            override val displayMode = displayMode
            override val isFooter = true
        }
    }
    val navigation: @Composable () -> Unit = {
        SideNav(
            modifier = Modifier.fillMaxHeight(),
            indicatorState = state.indicatorState,
            expanded = state.expanded,
            onExpandStateChange = { state.expanded = it },
            header = {
                SideNavHeaderArea(
                    title = title,
                    backButton = {},
                    expandButton = {
                        NavigationDefaults.ExpandedButton(onClick = { state.expanded = !state.expanded })
                    },
                )
            },
            // flex 位于标题下方、滚动容器之外，固定新建入口和列表标题。
            flex = fixedHeader,
            footer = { footerItems(footerScope) },
        ) { menuItems(itemScope) }
    }
    Box(modifier) {
        Row(Modifier.fillMaxSize()) {
            if (compact) Spacer(Modifier.width(48.dp)) else navigation()
            Layer(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(topStart = FluentTheme.cornerRadius.overlay),
                color = FluentTheme.colors.background.layer.default,
                border = BorderStroke(1.dp, FluentTheme.colors.stroke.card.default),
                content = pane,
            )
        }
        if (compact) {
            // 窄窗口展开时覆盖正文；点击正文收起，避免挤压会话内容。
            Row(Modifier.fillMaxSize()) {
                Mica(Modifier) { navigation() }
                if (state.expanded) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { state.expanded = false })
                }
            }
        }
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
