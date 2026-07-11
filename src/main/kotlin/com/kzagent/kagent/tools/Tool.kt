package com.kzagent.kagent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val requiresApproval: Boolean,
    val cost: Int = 1,
    val handler: suspend (JsonObject) -> ToolResult,
)

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
) {
    companion object {
        fun ok(content: String) = ToolResult(content = content, isError = false)
        fun error(content: String) = ToolResult(content = content, isError = true)
    }
}

class ToolRegistry(tools: List<ToolDefinition>) {
    private val byName = tools.associateBy { it.name }

    fun get(name: String): ToolDefinition? = byName[name]

    fun toolSchemas(): List<JsonObject> = byName.values.map { tool ->
        buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                },
            )
        }
    }
}
