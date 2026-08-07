package com.kzagent.kagent.llm

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.SecretRedactor
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.min

class DeepSeekClient : ChatModel {
    private val config: AppConfig
    private val api: DeepSeekApi
    private val streamClient: OkHttpClient
    private val json: Json

    constructor(config: AppConfig) {
        this.config = config
        this.api = DeepSeekApiFactory.create(config)
        this.json = deepSeekJson()
        this.streamClient = createStreamOkHttpClient()
    }

    internal constructor(config: AppConfig, api: DeepSeekApi) {
        this.config = config
        this.api = api
        this.json = deepSeekJson()
        this.streamClient = createStreamOkHttpClient()
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

    override suspend fun chatStreaming(
        messages: List<AgentMessage>,
        tools: List<JsonObject>,
        onPartialContent: (String) -> Unit,
    ): AssistantReply = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(
                model = config.model,
                temperature = 0.2,
                messages = messages.map { it.toDeepSeekJson() },
                tools = tools.takeIf { it.isNotEmpty() },
                toolChoice = "auto".takeIf { tools.isNotEmpty() },
                stream = true,
            ),
        )
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = streamClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.let { body ->
                val source = body.source()
                source.request(MAX_ERROR_BODY_BYTES + 1L)
                val truncated = source.buffer.size > MAX_ERROR_BODY_BYTES
                val text = source.readUtf8(min(source.buffer.size, MAX_ERROR_BODY_BYTES.toLong()))
                if (truncated) "$text\n...[truncated]" else text
            }.orEmpty()
            response.close()
            throw DeepSeekException(
                message = "DeepSeek API HTTP ${response.code}" +
                    errorBody.takeIf(String::isNotBlank)?.let { ": ${SecretRedactor.redact(it)}" }.orEmpty(),
                statusCode = response.code,
            )
        }

        response.use { resp ->
            val body = resp.body ?: throw DeepSeekException("DeepSeek API returned an empty streaming body.")
            val contentBuilder = StringBuilder()
            val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
            var totalTokens: Int? = null
            var promptTokens: Int? = null

            body.source().use { source ->
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    val chunk = try {
                        json.decodeFromString(ChatCompletionChunk.serializer(), data)
                    } catch (_: Exception) {
                        continue
                    }

                    chunk.usage?.let { usage ->
                        totalTokens = usage.totalTokens
                        promptTokens = usage.promptTokens
                    }

                    for (choice in chunk.choices) {
                        val delta = choice.delta
                        delta.content?.let { text ->
                            contentBuilder.append(text)
                            onPartialContent(text)
                        }
                        delta.toolCalls?.forEach { chunkTc ->
                            val builder = toolCallBuilders.getOrPut(chunkTc.index) { ToolCallBuilder() }
                            chunkTc.id?.let { builder.id = it }
                            chunkTc.type?.let { builder.type = it }
                            chunkTc.function?.name?.let { builder.name = it }
                            chunkTc.function?.arguments?.let { builder.argumentsBuilder.append(it) }
                        }
                    }
                }
            }

            val toolCalls = toolCallBuilders.values
                .sortedBy { toolCallBuilders.entries.first { entry -> entry.value === it }.key }
                .filter { it.id != null && it.name != null }
                .map { builder ->
                    ModelToolCall(
                        id = builder.id!!,
                        name = builder.name!!,
                        argumentsJson = builder.argumentsBuilder.toString(),
                    )
                }

            AssistantReply(
                content = contentBuilder.toString().takeIf { it.isNotBlank() },
                toolCalls = toolCalls,
                totalTokens = totalTokens,
                promptTokens = promptTokens,
            )
        }
    }

    private fun readBoundedErrorBody(body: okhttp3.ResponseBody): String {
        val source = body.source()
        source.request(MAX_ERROR_BODY_BYTES + 1L)
        val truncated = source.buffer.size > MAX_ERROR_BODY_BYTES
        val text = source.readUtf8(min(source.buffer.size, MAX_ERROR_BODY_BYTES.toLong()))
        return if (truncated) "$text\n...[truncated]" else text
    }

    private fun createStreamOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(60))
            .callTimeout(Duration.ofSeconds(600))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    private class ToolCallBuilder {
        var id: String? = null
        var type: String? = null
        var name: String? = null
        val argumentsBuilder = StringBuilder()
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
