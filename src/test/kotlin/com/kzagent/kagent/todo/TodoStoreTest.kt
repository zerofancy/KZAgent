package com.kzagent.kagent.todo

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TodoStoreTest {
    @Test
    fun hierarchyCascadesRollsUpAndPersists() = runBlocking {
        val path = Files.createTempDirectory("kagent-todo-store").resolve("session.jsonl.todos.json")
        val store = TodoStore(path)

        store.applyOperations(
            listOf(
                create("root", "Root"),
                create("child-1", "First", "root"),
                create("child-2", "Second", "root"),
            ),
        )
        assertEquals(TodoStatus.PENDING, store.current().items.single { it.id == "root" }.status)

        store.applyOperations(listOf(setStatus("child-1", TodoStatus.COMPLETED)))
        assertEquals(TodoStatus.PENDING, store.current().items.single { it.id == "root" }.status)
        store.applyOperations(listOf(setStatus("child-2", TodoStatus.COMPLETED)))
        assertTrue(store.current().items.all { it.status == TodoStatus.COMPLETED })

        store.applyOperations(listOf(setStatus("root", TodoStatus.PENDING)))
        assertTrue(store.current().items.all { it.status == TodoStatus.PENDING })

        val reloaded = TodoStore(path)
        assertEquals(store.current(), reloaded.current())
        assertEquals(2, reloaded.current().totalLeafCount)
    }

    @Test
    fun operationsAreAtomicAndCyclesAreRejected() = runBlocking {
        val path = Files.createTempDirectory("kagent-todo-atomic").resolve("session.jsonl.todos.json")
        val store = TodoStore(path)
        val original = store.applyOperations(
            listOf(
                create("root", "Root"),
                create("child", "Child", "root"),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            store.applyOperations(
                listOf(
                    TodoOperation(TodoOperation.Type.UPDATE, "root", content = "Changed"),
                    TodoOperation(
                        TodoOperation.Type.UPDATE,
                        "root",
                        parentId = "child",
                        parentSpecified = true,
                    ),
                ),
            )
        }

        assertEquals(original, store.current())
        assertEquals(original, TodoStore(path).current())
    }

    @Test
    fun deletingAParentDeletesItsSubtree() = runBlocking {
        val path = Files.createTempDirectory("kagent-todo-delete").resolve("session.jsonl.todos.json")
        val store = TodoStore(path)
        store.applyOperations(
            listOf(
                create("root", "Root"),
                create("child", "Child", "root"),
                create("grandchild", "Grandchild", "child"),
            ),
        )

        val result = store.applyOperations(
            listOf(TodoOperation(TodoOperation.Type.DELETE, "child")),
        )

        assertEquals(listOf("root"), result.items.map { it.id })
    }

    @Test
    fun addingOrMovingPendingChildrenReopensAncestorsAndPreservesOrder() = runBlocking {
        val path = Files.createTempDirectory("kagent-todo-move").resolve("session.jsonl.todos.json")
        val store = TodoStore(path)
        store.applyOperations(
            listOf(
                create("first", "First"),
                create("first-child", "First child", "first"),
                create("second", "Second"),
                create("second-child", "Second child", "second"),
                setStatus("first", TodoStatus.COMPLETED),
                setStatus("second", TodoStatus.COMPLETED),
            ),
        )

        val added = store.applyOperations(
            listOf(create("new-child", "New child", "first")),
        )
        assertEquals(TodoStatus.PENDING, added.items.single { it.id == "first" }.status)

        val moved = store.applyOperations(
            listOf(
                TodoOperation(
                    type = TodoOperation.Type.UPDATE,
                    id = "new-child",
                    parentId = "second",
                    parentSpecified = true,
                ),
            ),
        )
        assertEquals(TodoStatus.COMPLETED, moved.items.single { it.id == "first" }.status)
        assertEquals(TodoStatus.PENDING, moved.items.single { it.id == "second" }.status)
        assertEquals("new-child", moved.items.last().id)
    }

    @Test
    fun reminderUsesSevenTurnThresholdAndFourTurnCooldown() = runBlocking {
        val path = Files.createTempDirectory("kagent-todo-reminder").resolve("session.jsonl.todos.json")
        val store = TodoStore(path)
        store.applyOperations(listOf(create("task", "Task")))

        repeat(6) {
            store.recordAssistantTurn(todoToolCalled = false, reminderInjected = false)
            assertFalse(store.shouldInjectReminder())
        }
        store.recordAssistantTurn(todoToolCalled = false, reminderInjected = false)
        assertTrue(store.shouldInjectReminder())

        store.recordAssistantTurn(todoToolCalled = false, reminderInjected = true)
        repeat(2) {
            assertFalse(store.shouldInjectReminder())
            store.recordAssistantTurn(todoToolCalled = false, reminderInjected = false)
        }
        assertFalse(store.shouldInjectReminder())
        store.recordAssistantTurn(todoToolCalled = false, reminderInjected = false)
        assertTrue(store.shouldInjectReminder())
        assertTrue(TodoStore(path).shouldInjectReminder())

        store.recordAssistantTurn(todoToolCalled = true, reminderInjected = false)
        assertFalse(store.shouldInjectReminder())
    }

    @Test
    fun corruptSidecarIsPreservedAndTodoBecomesUnavailable() = runBlocking {
        val path = Files.createTempDirectory("kagent-todo-corrupt").resolve("session.jsonl.todos.json")
        Files.writeString(path, "{not-json")

        val store = TodoStore(path)

        assertNotNull(store.current().error)
        assertFailsWith<IllegalStateException> {
            store.applyOperations(listOf(create("task", "Task")))
        }
        assertEquals("{not-json", Files.readString(path))
    }

    @Test
    fun unknownSchemaVersionIsNotOverwritten() {
        val path = Files.createTempDirectory("kagent-todo-version").resolve("session.jsonl.todos.json")
        val original = """{"schema_version":999,"revision":0,"items":[]}"""
        Files.writeString(path, original)

        val store = TodoStore(path)

        assertNotNull(store.current().error)
        assertEquals(original, Files.readString(path))
    }

    @Test
    fun completedListCanBeClearedButPendingListIsPreserved() = runBlocking {
        val path = Files.createTempDirectory("kagent-todo-clear").resolve("session.jsonl.todos.json")
        val store = TodoStore(path)
        store.applyOperations(listOf(create("task", "Task")))

        assertFalse(store.clearIfAllCompleted())
        assertEquals(listOf("task"), store.current().items.map { it.id })

        store.applyOperations(listOf(setStatus("task", TodoStatus.COMPLETED)))
        assertTrue(store.clearIfAllCompleted())
        assertTrue(store.current().items.isEmpty())
        assertTrue(TodoStore(path).current().items.isEmpty())
    }

    @Test
    fun semanticNoOpDoesNotCreateANewRevision() = runBlocking {
        val path = Files.createTempDirectory("kagent-todo-no-op").resolve("session.jsonl.todos.json")
        val store = TodoStore(path)
        val created = store.applyOperations(listOf(create("task", "Task")))

        val unchanged = store.applyOperations(
            listOf(setStatus("task", TodoStatus.PENDING)),
        )

        assertEquals(created.revision, unchanged.revision)
        assertEquals(created, unchanged)
        assertEquals(created, TodoStore(path).current())
    }

    private fun create(id: String, content: String, parentId: String? = null) =
        TodoOperation(
            type = TodoOperation.Type.CREATE,
            id = id,
            content = content,
            parentId = parentId,
            parentSpecified = parentId != null,
        )

    private fun setStatus(id: String, status: TodoStatus) =
        TodoOperation(
            type = TodoOperation.Type.SET_STATUS,
            id = id,
            status = status,
        )
}
