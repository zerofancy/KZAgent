package com.kzagent.kagent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

sealed class AgentMessage {
    abstract val role: String

    data class System(val content: String) : AgentMessage() {
        override val role: String = "system"
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
}

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

