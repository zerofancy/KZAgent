package com.kzagent.kagent.tools

import com.kzagent.kagent.todo.TodoItem
import com.kzagent.kagent.todo.TodoOperation
import com.kzagent.kagent.todo.TodoSnapshot
import com.kzagent.kagent.todo.TodoStatus
import com.kzagent.kagent.todo.TodoStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class TodoTools(private val store: TodoStore) {
    fun registry(): ToolRegistry = ToolRegistry(listOf(todoReadTool(), todoWriteTool()))

    private fun todoReadTool(): ToolDefinition = ToolDefinition(
        name = "todo_read",
        description = "Read the complete session-scoped hierarchical Todo list and leaf-task progress. Use it when resuming a complex task or after a Todo reminder.",
        parameters = objectSchema(emptyMap(), emptyList()),
        requiresApproval = false,
        cost = 0,
    ) {
        val snapshot = store.current()
        snapshot.error?.let { ToolResult.error(it) }
            ?: ToolResult.ok(snapshot.toToolJson().toString())
    }

    private fun todoWriteTool(): ToolDefinition = ToolDefinition(
        name = "todo_write",
        description = "Atomically update the session Todo list. Mark each item completed as soon as its stage finishes instead of waiting until the final response. Do not set an already-pending item to pending to signal work started: pending already includes work in progress. Operations run in order and may create, update, complete/reopen, or delete hierarchical items. Use short stable unique ids; a parent created earlier in the same call may be referenced by later operations.",
        parameters = objectSchema(
            properties = mapOf(
                "operations" to buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put(
                        "items",
                        objectSchema(
                            properties = mapOf(
                                "operation" to enumStringSchema(
                                    "Operation to apply.",
                                    listOf("create", "update", "set_status", "delete"),
                                ),
                                "id" to stringSchema("Stable Todo id, unique within the session."),
                                "content" to stringSchema("Todo text. Required for create; optional for update."),
                                "parent_id" to buildJsonObject {
                                    put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                                    put("description", "Parent Todo id. For update, null moves the item to the root.")
                                },
                                "status" to enumStringSchema(
                                    "Required for set_status. Only pending and completed are stored; use pending for work currently in progress.",
                                    listOf("pending", "completed"),
                                ),
                            ),
                            required = listOf("operation", "id"),
                        ),
                    )
                },
            ),
            required = listOf("operations"),
        ),
        requiresApproval = false,
        cost = 0,
    ) { args ->
        try {
            val operations = args["operations"] as? JsonArray
                ?: throw IllegalArgumentException("operations must be an array.")
            val parsed = operations.mapIndexed { index, element ->
                parseOperation(index, element.jsonObject)
            }
            val previousRevision = store.current().revision
            val snapshot = store.applyOperations(parsed)
            ToolResult.ok(
                snapshot.toToolJson(changed = snapshot.revision != previousRevision).toString(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolResult.error(error.message ?: error.toString())
        }
    }

    private fun parseOperation(index: Int, value: JsonObject): TodoOperation {
        fun string(name: String): String? =
            (value[name] as? JsonPrimitive)?.contentOrNull

        val operation = when (string("operation")) {
            "create" -> TodoOperation.Type.CREATE
            "update" -> TodoOperation.Type.UPDATE
            "set_status" -> TodoOperation.Type.SET_STATUS
            "delete" -> TodoOperation.Type.DELETE
            else -> throw IllegalArgumentException("operations[$index].operation is invalid.")
        }
        val id = string("id")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("operations[$index].id is required.")
        val status = when (val raw = string("status")) {
            null -> null
            "pending" -> TodoStatus.PENDING
            // Models commonly emit this conventional planning status even when
            // the schema advertises a binary state. Preserve the binary Todo
            // contract by treating active work as not yet completed.
            "in_progress" -> TodoStatus.PENDING
            "completed" -> TodoStatus.COMPLETED
            else -> throw IllegalArgumentException(
                "operations[$index].status is invalid: $raw. " +
                    "Supported values are pending and completed; use pending for work in progress.",
            )
        }
        val parentSpecified = value.containsKey("parent_id")
        val parentId = when (val parent = value["parent_id"]) {
            null, JsonNull -> null
            is JsonPrimitive -> parent.contentOrNull?.takeIf { it.isNotBlank() }
            else -> throw IllegalArgumentException("operations[$index].parent_id must be a string or null.")
        }
        return TodoOperation(
            type = operation,
            id = id,
            content = string("content"),
            parentId = parentId,
            parentSpecified = parentSpecified,
            status = status,
        )
    }
}

private fun TodoSnapshot.toToolJson(changed: Boolean? = null): JsonObject {
    val children = items.groupBy { it.parentId }
    fun node(item: TodoItem): JsonObject = buildJsonObject {
        put("id", item.id)
        put("content", item.content)
        put("status", item.status.name.lowercase())
        put("children", buildJsonArray {
            children[item.id].orEmpty().forEach { add(node(it)) }
        })
    }
    return buildJsonObject {
        put("revision", revision)
        changed?.let { put("changed", it) }
        put("completed_leaf_count", completedLeafCount)
        put("total_leaf_count", totalLeafCount)
        put("incomplete_count", items.count { it.status == TodoStatus.PENDING })
        put("todos", buildJsonArray {
            children[null].orEmpty().forEach { add(node(it)) }
        })
    }
}

private fun enumStringSchema(description: String, values: List<String>): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
