package com.kzagent.kagent.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.IndicatorState
import io.github.composefluent.component.SideNavItem
import io.github.composefluent.component.TooltipBox
import io.github.composefluent.component.rememberTooltipState
import io.github.composefluent.component.Text
import io.github.composefluent.component.Icon
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Document

internal fun sessionNavigationShortLabel(name: String): String {
    val compact = name.trim().replace(Regex("\\s+"), "")
    if (compact.isEmpty()) return "会话"
    Regex("新会话(\\d+)").matchEntire(compact)?.let {
        return "会" + it.groupValues[1].takeLast(2)
    }
    // 按字素截取，避免截断 emoji、代理对或附加音标。
    val graphemes = Regex("\\X").findAll(compact).map { it.value }.toList()
    val count = if (graphemes.take(3).all { it.length == 1 && it[0].code < 128 }) 3 else 2
    return graphemes.take(count).joinToString("")
}

@OptIn(ExperimentalFluentApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun SessionNavigationItem(
    session: SessionData,
    expanded: Boolean,
    indicatorState: IndicatorState?,
    selected: Boolean,
    onClick: () -> Unit,
    badge: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusTooltip = rememberTooltipState()
    val description = "${session.name}\n项目：${workspaceProjectName(session.workspace)}\n${session.workspace}\n" +
        if (session.isBusy) "运行中" else "空闲"
    val preview: @Composable () -> Unit = {
        Column(Modifier.widthIn(max = 360.dp)) { Text(description) }
    }
    // 当前 Fluent 版本只为 SideNavItem 提供鼠标提示；复用点击控件的焦点，
    // 不额外插入 Tab 停靠点，也不把焦点移到提示窗口。
    LaunchedEffect(focused, expanded) {
        if (focused && !expanded) focusTooltip.show() else focusTooltip.dismiss()
    }
    TooltipBox(tooltip = preview, state = focusTooltip, enabled = false) {
        SideNavItem(
            selected = selected,
            onSelectedChanged = { onClick() },
            indicatorState = indicatorState,
            interactionSource = interaction,
            modifier = Modifier.semantics {
                contentDescription = description
                this.selected = selected
            },
            icon = {
                if (expanded) {
                    Icon(Icons.Default.Document, contentDescription = null)
                } else {
                    // 槽位默认仅 16dp；36dp 行高内保留标签和独立状态点的空间。
                    Box(Modifier.requiredSize(32.dp, 32.dp)) {
                        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                            Text(sessionNavigationShortLabel(session.name), style = FluentTheme.typography.caption,
                                maxLines = 1, softWrap = false)
                        }
                        if (session.isBusy) {
                            Box(Modifier.align(Alignment.BottomEnd).size(5.dp)
                                .background(FluentTheme.colors.fillAccent.default, CircleShape)
                                .semantics { contentDescription = "运行中" })
                        }
                    }
                }
            },
            badge = badge,
            text = { if (expanded) Text(session.name, maxLines = 1) else preview() },
        )
    }
}
