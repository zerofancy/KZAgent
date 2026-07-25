package com.kzagent.kagent.llm

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min

class DeepSeekClient : ChatModel {
    private val config: AppConfig
    private val api: DeepSeekApi

    constructor(config: AppConfig) {
        this.config = config
        this.api = DeepSeekApiFactory.create(config)
    }

    internal constructor(config: AppConfig, api: DeepSeekApi) {
        this.config = config
        this.api = api
    }

    override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
        val response = api.createChatCompletion(
            ChatCompletionRequest(
                model = config.model,
                temperature = 0.2,
                messages = messages.map { it.toDeepSeekJson() },
                tools = tools.takeIf { it.isNotEmpty() },
                toolChoice = "auto".takeIf { tools.isNotEmpty() },
            ),
        )
        if (!response.isSuccessful) {
            val errorBody = withContext(Dispatchers.IO) {
                response.errorBody()?.use(::readBoundedErrorBody).orEmpty()
            }
            val suffix = errorBody.takeIf(String::isNotBlank)?.let { ": ${SecretRedactor.redact(it)}" }.orEmpty()
            throw DeepSeekException(
                message = "DeepSeek API HTTP ${response.code()}$suffix",
                statusCode = response.code(),
            )
        }

        val parsed = response.body()
            ?: throw DeepSeekException("DeepSeek API returned an empty response body.")
        val message = parsed.choices.firstOrNull()?.message
            ?: throw DeepSeekException("DeepSeek API returned no choices.")

        return AssistantReply(
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

    private fun readBoundedErrorBody(body: okhttp3.ResponseBody): String {
        val source = body.source()
        source.request(MAX_ERROR_BODY_BYTES + 1L)
        val truncated = source.buffer.size > MAX_ERROR_BODY_BYTES
        val text = source.readUtf8(min(source.buffer.size, MAX_ERROR_BODY_BYTES.toLong()))
        return if (truncated) "$text\n...[truncated]" else text
    }

    private companion object {
        const val MAX_ERROR_BODY_BYTES = 16 * 1024
    }
}

internal fun AgentMessage.toDeepSeekJson(): JsonObject = buildJsonObject {
    put(
        "role",
        if (
            this@toDeepSeekJson is AgentMessage.Summary ||
            this@toDeepSeekJson is AgentMessage.ScopedInstruction
        ) {
            "system"
        } else {
            role
        },
    )
    when (this@toDeepSeekJson) {
        is AgentMessage.System -> put("content", content)
        is AgentMessage.Summary -> put("content", "## Conversation summary\n$content")
        is AgentMessage.ScopedInstruction -> put(
            "content",
            buildString {
                appendLine("## Directory-scoped project instructions")
                appendLine("Source: $sourcePath")
                appendLine("Applies to: $scopePath")
                appendLine()
                append(content)
            },
        )
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

class DeepSeekException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
