package com.kzagent.kagent.desktop

import com.kzagent.kagent.llm.AgentMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DisplayMessage(val role: String, val content: String, val collapsible: Boolean = false,
    val collapsed: Boolean = true)

fun List<AgentMessage>.toDisplayMessages(): List<DisplayMessage> = mapNotNull { message ->
    when (message) {
        is AgentMessage.User -> DisplayMessage("user", message.content)
        is AgentMessage.Assistant -> when {
            !message.content.isNullOrBlank() -> DisplayMessage("assistant", message.content)
            message.toolCalls.isNotEmpty() -> DisplayMessage("tool_call",
                message.toolCalls.joinToString("\n") { formatToolCallSummary(it.name, it.argumentsJson) }, collapsible = true)
            else -> null
        }
        is AgentMessage.Tool -> DisplayMessage("tool_result",
            if (message.isError) "错误: ${message.content}" else message.content, collapsible = true)
        is AgentMessage.System, is AgentMessage.ScopedInstruction, is AgentMessage.Summary -> null
    }
}

internal fun formatToolCallSummary(name: String, argsJson: String): String = when (name) {
    "run_command" -> "运行命令: ${extractArg(argsJson, "command")}"
    "read_file" -> "读取文件: ${extractArg(argsJson, "path")}"
    "list_files" -> "查看目录: ${extractArg(argsJson, "path") ?: "."}"
    "search_text" -> "搜索: ${extractArg(argsJson, "query")}"
    "apply_patch" -> "应用文件补丁 " + extractPatchedFiles(argsJson).joinToString(", ")
    "fetch_web_page" -> "获取网页: ${extractArg(argsJson, "url")}"
    "todo_read" -> "查看 Todo"
    "todo_write" -> {
        val count = runCatching {
            (Json.parseToJsonElement(argsJson).jsonObject["operations"] as? kotlinx.serialization.json.JsonArray)?.size
        }.getOrNull()
        if (count == null) "更新 Todo" else "更新 Todo（$count 项操作）"
    }
    else -> "$name($argsJson)"
}

internal fun extractArg(json: String, key: String): String? = runCatching {
    Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.contentOrNull
}.getOrNull()

internal fun extractPatchedFiles(argsJson: String): List<String> {
    val patch = extractArg(argsJson, "patch") ?: return listOf("(patch)")
    return patch.lineSequence().mapNotNull { line ->
        if (!line.startsWith("diff --git a/")) return@mapNotNull null
        val paths = line.removePrefix("diff --git a/")
        val separator = paths.indexOf(" b/")
        paths.takeIf { separator > 0 }?.substring(0, separator)
    }.distinct().take(20).toList().ifEmpty { listOf("(patch)") }
}
