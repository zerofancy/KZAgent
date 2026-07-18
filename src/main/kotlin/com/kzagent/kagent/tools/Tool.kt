package com.kzagent.kagent.tools

import java.nio.file.Path
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
    /** Internal metadata consumed by CodingAgent; never included in model-visible tool output. */
    val readPaths: List<Path> = emptyList(),
) {
    companion object {
        fun ok(content: String, readPaths: List<Path> = emptyList()) =
            ToolResult(content = content, isError = false, readPaths = readPaths)

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
