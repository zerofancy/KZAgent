package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.ModelToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.stream.Collectors

class SessionReader(workspace: Path) {
    private val sessionsDir: Path = workspace.resolve(".kagent").resolve("sessions")
    private val json = Json { ignoreUnknownKeys = true }

    fun loadLatest(): List<AgentMessage>? {
        if (!Files.isDirectory(sessionsDir)) return null
        val files = Files.list(sessionsDir).use { stream ->
            stream
                .filter { it.toString().endsWith(".jsonl") }
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList())
        }
        val latest = files.firstOrNull() ?: return null
        return loadFile(latest)
    }

    fun loadFile(path: Path): List<AgentMessage> {
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        return lines.filter { it.isNotBlank() }.map { parseLine(it) }
    }

    private fun parseLine(line: String): AgentMessage {
        val obj = json.parseToJsonElement(line).jsonObject
        val role = obj["role"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing role in session line.")
        return when (role) {
            "system" -> AgentMessage.System(content = obj["content"]?.jsonPrimitive?.content.orEmpty())
            "user" -> AgentMessage.User(content = obj["content"]?.jsonPrimitive?.content.orEmpty())
            "assistant" -> {
                val content = obj["content"]?.jsonPrimitive?.contentOrNull
                val toolCalls = obj["tool_calls"]?.jsonArray?.map { tc ->
                    val tcObj = tc.jsonObject
                    ModelToolCall(
                        id = tcObj["id"]?.jsonPrimitive?.content.orEmpty(),
                        name = tcObj["name"]?.jsonPrimitive?.content.orEmpty(),
                        argumentsJson = tcObj["arguments"]?.jsonPrimitive?.content.orEmpty(),
                    )
                } ?: emptyList()
                AgentMessage.Assistant(content = content, toolCalls = toolCalls)
            }
            "tool" -> AgentMessage.Tool(
                toolCallId = obj["tool_call_id"]?.jsonPrimitive?.content.orEmpty(),
                name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                content = obj["content"]?.jsonPrimitive?.content.orEmpty(),
                isError = obj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            else -> throw IllegalArgumentException("Unknown role in session: $role")
        }
    }

    fun loadLatestHistory(): List<AgentMessage>? {
        return loadLatest()?.filter { it !is AgentMessage.System }
    }
}
