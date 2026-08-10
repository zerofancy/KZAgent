package com.kzagent.kagent.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.composefluent.component.Icon as FluentIcon
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowDown
import io.github.composefluent.icons.regular.ArrowUp
import kotlinx.coroutines.launch
import java.nio.file.Path

@Composable
internal fun MessageList(sessionId: String, messages: MutableList<DisplayMessage>, workspace: Path,
    modifier: Modifier = Modifier) {
    val scrollState = remember(sessionId) { ScrollState(initial = 0) }
    val scope = rememberCoroutineScope()
    var displayedSessionId by remember { mutableStateOf<String?>(null) }
    var displayedMessageCount by remember { mutableStateOf(0) }
    LaunchedEffect(sessionId, messages.size) {
        val behavior = messageListScrollBehavior(displayedSessionId, sessionId, displayedMessageCount, messages.size)
        displayedSessionId = sessionId
        displayedMessageCount = messages.size
        when (behavior) {
            MessageListScrollBehavior.JUMP_TO_BOTTOM -> {
                scrollState.scrollTo(Int.MAX_VALUE)
                scrollState.awaitStableMessageScrollRange()
                scrollState.scrollTo(scrollState.maxValue)
            }
            MessageListScrollBehavior.ANIMATE_TO_BOTTOM -> {
                withFrameNanos { }
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            MessageListScrollBehavior.NONE -> Unit
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)
            .padding(start = 22.dp, top = 22.dp, end = 70.dp, bottom = 70.dp)) {
            if (messages.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center) { Text("✦", color = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.height(12.dp))
                    Text("开始一个新会话", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("描述你想在当前工作区完成的任务", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                messages.forEachIndexed { index, message ->
                    MessageRow(index, message, messages, workspace)
                    if (index != messages.lastIndex) Spacer(Modifier.height(14.dp))
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scrollState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp))
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtleButton(onClick = { scope.launch { scrollState.scrollTo(0) } }, modifier = Modifier.size(34.dp), iconOnly = true) {
                FluentIcon(Icons.Default.ArrowUp, contentDescription = "跳转到顶部")
            }
            SubtleButton(onClick = { scope.launch { scrollState.scrollTo(scrollState.maxValue) } },
                modifier = Modifier.size(34.dp), iconOnly = true) {
                FluentIcon(Icons.Default.ArrowDown, contentDescription = "跳转到底部")
            }
        }
    }
}

internal suspend fun ScrollState.awaitStableMessageScrollRange() {
    var previousMaxValue = Int.MIN_VALUE
    var stableFrames = 0
    while (stableFrames < 2) {
        withFrameNanos { }
        val currentMaxValue = maxValue
        if (!isMeasuredMessageScrollRange(currentMaxValue)) continue
        if (currentMaxValue == previousMaxValue) stableFrames++ else {
            previousMaxValue = currentMaxValue
            stableFrames = 0
        }
    }
}

internal fun isMeasuredMessageScrollRange(maxValue: Int): Boolean = maxValue != Int.MAX_VALUE

internal enum class MessageListScrollBehavior { NONE, JUMP_TO_BOTTOM, ANIMATE_TO_BOTTOM }

internal fun messageListScrollBehavior(previousSessionId: String?, activeSessionId: String,
    previousMessageCount: Int, currentMessageCount: Int): MessageListScrollBehavior = when {
    previousSessionId != activeSessionId -> MessageListScrollBehavior.JUMP_TO_BOTTOM
    previousMessageCount != currentMessageCount -> MessageListScrollBehavior.ANIMATE_TO_BOTTOM
    else -> MessageListScrollBehavior.NONE
}

@Composable
internal fun MessageRow(index: Int, message: DisplayMessage, messages: MutableList<DisplayMessage>, workspace: Path) {
    val (title, avatar) = when (message.role) {
        "user" -> "你" to "你"
        "assistant" -> "KZAgent" to "K"
        "tool_call" -> "工具调用" to "⌘"
        "tool_result" -> "执行结果" to "✓"
        else -> message.role to "·"
    }
    val background = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
        "tool_call", "tool_result" -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = if (message.role == "user") MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        else MaterialTheme.colorScheme.outlineVariant
    val avatarBackground = when (message.role) {
        "assistant" -> MaterialTheme.colorScheme.primary
        "user" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val avatarForeground = when (message.role) {
        "assistant" -> MaterialTheme.colorScheme.onPrimary
        "user" -> MaterialTheme.colorScheme.onSecondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val collapsedLabel = when (message.role) {
        "tool_call" -> "（展开查看工具调用详情）"
        "tool_result" -> "（展开查看执行结果）"
        else -> ""
    }
    Row(Modifier.fillMaxWidth().background(background, MaterialTheme.shapes.medium)
        .border(1.dp, borderColor, MaterialTheme.shapes.medium).padding(14.dp)
        .let { if (message.collapsible) it.clickable { toggleCollapse(index, messages) } else it }) {
        Box(Modifier.size(28.dp).background(avatarBackground, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center) {
            Text(avatar, color = avatarForeground, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                if (message.collapsible) {
                    Spacer(Modifier.width(8.dp))
                    Text(if (message.collapsed) "›" else "⌄", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(7.dp))
            if (message.collapsible && message.collapsed) {
                Text(collapsedLabel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else MessageContent(message, workspace)
        }
    }
}

internal fun toggleCollapse(index: Int, messages: MutableList<DisplayMessage>) {
    messages[index] = messages[index].copy(collapsed = !messages[index].collapsed)
}
