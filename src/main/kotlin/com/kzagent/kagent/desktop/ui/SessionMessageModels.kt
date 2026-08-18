package com.kzagent.kagent.desktop

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.agent.SessionEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DisplayMessage(val role: String, val content: String, val collapsible: Boolean = false,
    val collapsed: Boolean = true, val timestampMillis: Long? = null)

fun List<AgentMessage>.toDisplayMessages(): List<DisplayMessage> = mapNotNull { it.toDisplayMessage(null) }

fun List<SessionEntry>.toDisplayMessagesWithTimestamps(): List<DisplayMessage> =
    mapNotNull { it.message.toDisplayMessage(it.timestampMillis) }

internal fun AgentMessage.toDisplayMessage(timestampMillis: Long?): DisplayMessage? = when (this) {
    is AgentMessage.User -> DisplayMessage("user", content, timestampMillis = timestampMillis)
    is AgentMessage.Assistant -> when {
        !content.isNullOrBlank() -> DisplayMessage("assistant", content, timestampMillis = timestampMillis)
        toolCalls.isNotEmpty() -> DisplayMessage(
            "tool_call",
            toolCalls.joinToString("\n") { formatToolCallSummary(it.name, it.argumentsJson) },
            collapsible = true,
            timestampMillis = timestampMillis,
        )
        else -> null
    }
    is AgentMessage.Tool -> DisplayMessage(
        "tool_result",
        if (isError) "错误: $content" else content,
        collapsible = true,
        timestampMillis = timestampMillis,
    )
    is AgentMessage.System, is AgentMessage.ScopedInstruction, is AgentMessage.Summary -> null
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
    "ask_user" -> "向用户提问"
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
