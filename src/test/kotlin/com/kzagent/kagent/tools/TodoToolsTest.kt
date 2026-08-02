package com.kzagent.kagent.tools

import com.kzagent.kagent.todo.TodoFiles
import com.kzagent.kagent.todo.TodoStatus
import com.kzagent.kagent.todo.TodoStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoToolsTest {
    @Test
    fun writeAndReadExposeHierarchicalSnapshotAtZeroCost() = runBlocking {
        val sessionFile = Files.createTempFile("kagent-todo-tools", ".jsonl")
        val registry = TodoTools(TodoStore(TodoFiles.forSession(sessionFile))).registry()
        val writeTool = registry.get("todo_write")!!

        val write = writeTool.handler(buildJsonObject {
            put("operations", buildJsonArray {
                add(buildJsonObject {
                    put("operation", "create")
                    put("id", "root")
                    put("content", "Root")
                })
                add(buildJsonObject {
                    put("operation", "create")
                    put("id", "child")
                    put("content", "Child")
                    put("parent_id", "root")
                })
            })
        })
        val read = registry.get("todo_read")!!.handler(buildJsonObject {})

        assertFalse(write.isError)
        assertContains(write.content, "\"changed\":true")
        assertFalse(read.isError)
        assertContains(read.content, "\"children\"")
        assertContains(read.content, "\"child\"")
        assertEquals(0, writeTool.cost)
        assertFalse(writeTool.requiresApproval)
        assertEquals(0, registry.get("todo_read")!!.cost)
        assertContains(
            registry.toolSchemas().single { it.toString().contains("\"todo_write\"") }.toString(),
            "use pending for work currently in progress",
        )
        assertContains(
            registry.toolSchemas().single { it.toString().contains("\"todo_write\"") }.toString(),
            "Do not set an already-pending item to pending",
        )
    }

    @Test
    fun invalidBatchDoesNotPartiallyCommit() = runBlocking {
        val sessionFile = Files.createTempFile("kagent-todo-tools-atomic", ".jsonl")
        val store = TodoStore(TodoFiles.forSession(sessionFile))
        val writeTool = TodoTools(store).registry().get("todo_write")!!

        val result = writeTool.handler(buildJsonObject {
            put("operations", buildJsonArray {
                add(buildJsonObject {
                    put("operation", "create")
                    put("id", "valid")
                    put("content", "Valid")
                })
                add(buildJsonObject {
                    put("operation", "create")
                    put("id", "invalid")
                    put("content", "Invalid")
                    put("parent_id", "missing")
                })
            })
        })

        assertTrue(result.isError)
        assertTrue(store.current().items.isEmpty())
    }

    @Test
    fun inProgressInputIsNormalizedToPending() = runBlocking {
        val sessionFile = Files.createTempFile("kagent-todo-tools-progress", ".jsonl")
        val store = TodoStore(TodoFiles.forSession(sessionFile))
        val writeTool = TodoTools(store).registry().get("todo_write")!!
        writeTool.handler(buildJsonObject {
            put("operations", buildJsonArray {
                add(buildJsonObject {
                    put("operation", "create")
                    put("id", "task")
                    put("content", "Task")
                })
            })
        })

        val result = writeTool.handler(buildJsonObject {
            put("operations", buildJsonArray {
                add(buildJsonObject {
                    put("operation", "set_status")
                    put("id", "task")
                    put("status", "in_progress")
                })
            })
        })

        assertFalse(result.isError)
        assertEquals(TodoStatus.PENDING, store.current().items.single().status)
        assertContains(result.content, "\"status\":\"pending\"")
        assertContains(result.content, "\"changed\":false")
        assertEquals(1, store.current().revision)
    }

    @Test
    fun invalidStatusErrorListsSupportedValues() = runBlocking {
        val sessionFile = Files.createTempFile("kagent-todo-tools-status-error", ".jsonl")
        val store = TodoStore(TodoFiles.forSession(sessionFile))
        val writeTool = TodoTools(store).registry().get("todo_write")!!

        val result = writeTool.handler(buildJsonObject {
            put("operations", buildJsonArray {
                add(buildJsonObject {
                    put("operation", "set_status")
                    put("id", "task")
                    put("status", "blocked")
                })
            })
        })

        assertTrue(result.isError)
        assertContains(result.content, "Supported values are pending and completed")
        assertContains(result.content, "use pending for work in progress")
    }
}
