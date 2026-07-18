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

class SessionReader(private val sessionsDir: Path) {
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
        val history = mutableListOf<AgentMessage>()
        for (line in lines.filter { it.isNotBlank() }) {
            val obj = json.parseToJsonElement(line).jsonObject
            if (obj["role"]?.jsonPrimitive?.content == "context_snapshot") {
                history.clear()
                history += obj["messages"]?.jsonArray.orEmpty().map { parseObject(it.jsonObject) }
            } else {
                history += parseObject(obj)
            }
        }
        return history
    }

    private fun parseObject(obj: JsonObject): AgentMessage {
        val role = obj["role"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing role in session line.")
        return when (role) {
            "system" -> AgentMessage.System(content = obj["content"]?.jsonPrimitive?.content.orEmpty())
            "summary" -> AgentMessage.Summary(content = obj["content"]?.jsonPrimitive?.content.orEmpty())
            "project_instruction" -> AgentMessage.ScopedInstruction(
                sourcePath = obj["source_path"]?.jsonPrimitive?.content.orEmpty(),
                scopePath = obj["scope_path"]?.jsonPrimitive?.content.orEmpty(),
                content = obj["content"]?.jsonPrimitive?.content.orEmpty(),
            )
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

    fun loadLatestTokenCount(): Int {
        if (!Files.isDirectory(sessionsDir)) return 0
        val files = Files.list(sessionsDir).use { stream ->
            stream
                .filter { it.toString().endsWith(".jsonl") }
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList())
        }
        val latest = files.firstOrNull() ?: return 0
        val lines = Files.readAllLines(latest, StandardCharsets.UTF_8)
        // 从后往前找最后一条有 context_tokens（或旧的 cumulative_tokens）的行
        var lastTokens = 0
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val role = obj["role"]?.jsonPrimitive?.content.orEmpty()
                if (role != "assistant" && role != "context_snapshot") continue
                val tokens = obj["context_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: obj["cumulative_tokens"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: 0
                if (tokens > 0) return tokens
                if (lastTokens == 0) lastTokens = tokens
            } catch (_: Exception) { continue }
        }
        return lastTokens
    }
}
