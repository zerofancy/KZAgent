package com.kzagent.kagent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.todo.TodoItem
import com.kzagent.kagent.todo.TodoSnapshot
import com.kzagent.kagent.todo.TodoStatus
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.DialogSize

internal val TodoPanelBreakpoint = 900.dp
internal val TodoPanelWidth = 300.dp

internal data class TodoDisplayItem(val item: TodoItem, val depth: Int)

internal fun shouldShowPersistentTodoPanel(width: androidx.compose.ui.unit.Dp): Boolean = width >= TodoPanelBreakpoint

internal fun flattenTodoItems(items: List<TodoItem>): List<TodoDisplayItem> {
    val children = items.groupBy { it.parentId }
    return buildList {
        fun append(parentId: String?, depth: Int) {
            children[parentId].orEmpty().forEach { item ->
                add(TodoDisplayItem(item, depth))
                append(item.id, depth + 1)
            }
        }
        append(parentId = null, depth = 0)
    }
}

internal fun todoProgressFraction(snapshot: TodoSnapshot): Float =
    if (snapshot.totalLeafCount == 0) 0f else snapshot.completedLeafCount.toFloat() / snapshot.totalLeafCount

internal fun todoButtonLabel(snapshot: TodoSnapshot): String =
    if (snapshot.error != null) "Todo !" else "Todo ${snapshot.completedLeafCount}/${snapshot.totalLeafCount}"

@Composable
internal fun TodoPanel(snapshot: TodoSnapshot, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    Box(modifier.background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Todo 进度", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("${snapshot.completedLeafCount}/${snapshot.totalLeafCount} 个叶子任务已完成",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { todoProgressFraction(snapshot) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(end = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        snapshot.error != null -> Text(snapshot.error, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        snapshot.items.isEmpty() -> Text("当前会话还没有 Todo。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> flattenTodoItems(snapshot.items).forEach { TodoItemRow(it) }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scrollState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
internal fun TodoItemRow(display: TodoDisplayItem) {
    val completed = display.item.status == TodoStatus.COMPLETED
    Row(Modifier.fillMaxWidth().padding(start = (display.depth * 14).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(if (completed) "✓" else "○",
            color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(display.item.content, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None)
        }
    }
}

@Composable
internal fun TodoDialog(snapshot: TodoSnapshot, onDismiss: () -> Unit) {
    ContentDialog(
        title = "Todo", visible = true,
        content = { TodoPanel(snapshot, Modifier.fillMaxWidth().height(460.dp)) },
        primaryButtonText = "关闭", onButtonClick = { onDismiss() }, size = DialogSize.Max,
    )
}
