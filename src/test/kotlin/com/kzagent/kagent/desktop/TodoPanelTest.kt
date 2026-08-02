package com.kzagent.kagent.desktop

import androidx.compose.ui.unit.dp
import com.kzagent.kagent.todo.TodoItem
import com.kzagent.kagent.todo.TodoSnapshot
import com.kzagent.kagent.todo.TodoStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoPanelTest {
    @Test
    fun panelUsesPersistentLayoutAtBreakpoint() {
        assertFalse(shouldShowPersistentTodoPanel(TodoPanelBreakpoint - 1.dp))
        assertTrue(shouldShowPersistentTodoPanel(TodoPanelBreakpoint))
        assertTrue(shouldShowPersistentTodoPanel(TodoPanelBreakpoint + 1.dp))
    }

    @Test
    fun hierarchyIsFlattenedInDisplayOrderWithDepth() {
        val items = listOf(
            TodoItem("root", "Root"),
            TodoItem("child-1", "First", "root"),
            TodoItem("grandchild", "Grandchild", "child-1"),
            TodoItem("child-2", "Second", "root"),
            TodoItem("other", "Other"),
        )

        val flattened = flattenTodoItems(items)

        assertEquals(
            listOf("root", "child-1", "grandchild", "child-2", "other"),
            flattened.map { it.item.id },
        )
        assertEquals(listOf(0, 1, 2, 1, 0), flattened.map { it.depth })
    }

    @Test
    fun progressAndButtonUseLeafTasks() {
        val snapshot = TodoSnapshot(
            items = listOf(
                TodoItem("root", "Root"),
                TodoItem("done", "Done", "root", TodoStatus.COMPLETED),
                TodoItem("pending", "Pending", "root", TodoStatus.PENDING),
            ),
        )

        assertEquals(0.5f, todoProgressFraction(snapshot))
        assertEquals("Todo 1/2", todoButtonLabel(snapshot))
        assertEquals("Todo !", todoButtonLabel(TodoSnapshot(error = "broken")))
    }
}
