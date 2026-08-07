package com.kzagent.kagent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

sealed class AgentMessage {
    abstract val role: String

    data class System(val content: String) : AgentMessage() {
        override val role: String = "system"
    }

    /** Durable memory produced by context compression. Sent to the model as a system message. */
    data class Summary(val content: String) : AgentMessage() {
        override val role: String = "summary"
    }

    /**
     * Directory-scoped project guidance discovered after reading a file.
     * It is persisted in conversation history but may be removed by context compression.
     */
    data class ScopedInstruction(
        val sourcePath: String,
        val scopePath: String,
        val content: String,
    ) : AgentMessage() {
        override val role: String = "project_instruction"
    }

    data class User(val content: String) : AgentMessage() {
        override val role: String = "user"
    }

    data class Assistant(
        val content: String?,
        val toolCalls: List<ModelToolCall> = emptyList(),
    ) : AgentMessage() {
        override val role: String = "assistant"
    }

    data class Tool(
        val toolCallId: String,
        val name: String,
        val content: String,
        val isError: Boolean,
    ) : AgentMessage() {
        override val role: String = "tool"
    }
}

data class ModelToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class AssistantReply(
    val content: String?,
    val toolCalls: List<ModelToolCall> = emptyList(),
    val totalTokens: Int? = null,
    val promptTokens: Int? = null,
)

interface ChatModel {
    suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply

    suspend fun chatStreaming(
        messages: List<AgentMessage>,
        tools: List<JsonObject>,
        onPartialContent: (String) -> Unit,
    ): AssistantReply = chat(messages, tools)
}

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val temperature: Double,
    val messages: List<JsonObject>,
    val tools: List<JsonObject>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    val stream: Boolean = false,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
    val usage: ResponseUsage? = null,
)

@Serializable
data class ResponseUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
)

@Serializable
data class Choice(
    val message: ResponseMessage,
)

@Serializable
data class ResponseMessage(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ResponseToolCall>? = null,
)

@Serializable
data class ResponseToolCall(
    val id: String,
    val type: String,
    val function: ResponseToolFunction,
)

@Serializable
data class ResponseToolFunction(
    val name: String,
    val arguments: String = "{}",
)

@Serializable
internal data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ResponseUsage? = null,
)

@Serializable
internal data class ChunkChoice(
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class ChunkDelta(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChunkToolCallDelta>? = null,
)

@Serializable
internal data class ChunkToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ChunkToolFunctionDelta? = null,
)

@Serializable
internal data class ChunkToolFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

