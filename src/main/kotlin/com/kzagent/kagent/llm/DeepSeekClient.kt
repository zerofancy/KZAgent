package com.kzagent.kagent.llm

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.SecretRedactor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DeepSeekClient(
    private val config: AppConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) : ChatModel {
    override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", config.model)
                put("temperature", 0.2)
                put("messages", JsonArray(messages.map { it.toJson() }))
                if (tools.isNotEmpty()) {
                    put("tools", JsonArray(tools))
                    put("tool_choice", "auto")
                }
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.baseUrl}/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.apiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw DeepSeekException(
                    "DeepSeek API HTTP ${response.statusCode()}: ${SecretRedactor.redact(response.body())}"
                )
            }

            val parsed = json.decodeFromString<ChatCompletionResponse>(response.body())
            val message = parsed.choices.firstOrNull()?.message
                ?: throw DeepSeekException("DeepSeek API returned no choices.")

            AssistantReply(
                content = message.content,
                toolCalls = message.toolCalls.orEmpty().map {
                    ModelToolCall(
                        id = it.id,
                        name = it.function.name,
                        argumentsJson = it.function.arguments,
                    )
                },
                totalTokens = parsed.usage?.totalTokens,
                promptTokens = parsed.usage?.promptTokens,
            )
        }

    private fun AgentMessage.toJson(): JsonObject = buildJsonObject {
        put("role", role)
        when (this@toJson) {
            is AgentMessage.System -> put("content", content)
            is AgentMessage.User -> put("content", content)
            is AgentMessage.Assistant -> {
                put("content", content)
                if (toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        buildJsonArray {
                            for (toolCall in toolCalls) {
                                add(
                                    buildJsonObject {
                                        put("id", toolCall.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", toolCall.name)
                                                put("arguments", toolCall.argumentsJson)
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
            is AgentMessage.Tool -> {
                put("tool_call_id", toolCallId)
                put("name", name)
                put("content", content)
            }
        }
    }
}

class DeepSeekException(message: String) : RuntimeException(message)
