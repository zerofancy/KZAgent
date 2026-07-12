package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SessionWriter(private val sessionPath: Path) {

    fun append(message: AgentMessage, tokens: Int = 0) {
        writeLine(messageJson(message, tokens))
    }

    fun appendContextSnapshot(history: List<AgentMessage>, tokens: Int) {
        writeLine(buildJsonObject {
            put("time", Instant.now().toString())
            put("role", "context_snapshot")
            put("context_tokens", tokens)
            put("messages", JsonArray(history.map { messageJson(it) }))
        })
    }

    private fun messageJson(message: AgentMessage, tokens: Int = 0): JsonObject =
        buildJsonObject {
            put("time", Instant.now().toString())
            put("role", message.role)
            if (tokens > 0) {
                put("context_tokens", tokens)
            }
            when (message) {
                is AgentMessage.System -> put("content", message.content)
                is AgentMessage.Summary -> put("content", message.content)
                is AgentMessage.User -> put("content", message.content)
                is AgentMessage.Assistant -> {
                    put("content", message.content)
                    put("tool_calls", JsonArray(message.toolCalls.map {
                        buildJsonObject {
                            put("id", it.id)
                            put("name", it.name)
                            put("arguments", it.argumentsJson)
                        }
                    }))
                }
                is AgentMessage.Tool -> {
                    put("tool_call_id", message.toolCallId)
                    put("name", message.name)
                    put("content", message.content)
                    put("is_error", message.isError)
                }
            }
        }

    private fun writeLine(value: JsonObject) {
        Files.writeString(
            sessionPath,
            value.toString() + "\n",
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    companion object {
        fun createNew(sessionsDir: Path): SessionWriter {
            Files.createDirectories(sessionsDir)
            val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                .replace(":", "")
                .replace(".", "")
            return SessionWriter(sessionsDir.resolve("session-$timestamp.jsonl"))
        }
    }
}

