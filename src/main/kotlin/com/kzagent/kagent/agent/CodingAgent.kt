package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import com.kzagent.kagent.tools.ToolRegistry
import com.kzagent.kagent.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class CodingAgent(
    private val model: ChatModel,
    private val tools: ToolRegistry,
    private val promptBuilder: PromptBuilder,
    private val sessionWriter: SessionWriter,
    private val maxTurns: Int = 20,
    private val observer: AgentObserver = NoOpAgentObserver,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Start a fresh conversation with a single user prompt.
     */
    suspend fun run(userPrompt: String): String {
        return runConversation(userPrompt).answer
    }

    /**
     * Continue a conversation from previous history.
     * [history] should be previous messages (system messages will be filtered out
     * since a fresh system prompt is provided).
     */
    suspend fun run(userPrompt: String, history: List<AgentMessage>): String {
        return runConversation(userPrompt, history).answer
    }

    suspend fun runConversation(
        userPrompt: String,
        history: List<AgentMessage> = emptyList(),
    ): AgentRunResult {
        val messages = history.filter { it !is AgentMessage.System }.toMutableList()
        val system = AgentMessage.System(promptBuilder.build())
        messages += AgentMessage.User(userPrompt)
        if (history.isEmpty()) {
            sessionWriter.append(system)
        }
        sessionWriter.append(messages.last())
        var contextTokens = 0
        val answer = runInternal(system, messages) { contextTokens = it }
        return AgentRunResult(answer = answer, history = messages.toList(), totalTokens = contextTokens)
    }

    private suspend fun runInternal(
        system: AgentMessage.System,
        messages: MutableList<AgentMessage>,
        onTokens: (Int) -> Unit = {},
    ): String {
        repeat(maxTurns) { turn ->
            observer.onModelRequest(turn + 1)
            val reply = model.chat(listOf(system) + messages, tools.toolSchemas())
            reply.promptTokens?.let { onTokens(it) }
            val assistant = AgentMessage.Assistant(reply.content, reply.toolCalls)
            messages += assistant
            sessionWriter.append(assistant, tokens = reply.promptTokens ?: 0)

            if (reply.toolCalls.isEmpty()) {
                return reply.content.orEmpty().ifBlank { "(model returned an empty final response)" }
            }

            for (toolCall in reply.toolCalls) {
                val tool = tools.get(toolCall.name)
                val result = if (tool == null) {
                    ToolResult.error("Unknown tool: ${toolCall.name}")
                } else {
                    observer.onToolCall(toolCall.name)
                    runCatching {
                        val args = json.parseToJsonElement(toolCall.argumentsJson) as? JsonObject
                            ?: throw IllegalArgumentException("Tool arguments must be a JSON object.")
                        tool.handler(args)
                    }.getOrElse {
                        ToolResult.error(it.message ?: it.toString())
                    }
                }
                observer.onToolResult(toolCall.name, result)

                val toolMessage = AgentMessage.Tool(
                    toolCallId = toolCall.id,
                    name = toolCall.name,
                    content = result.content,
                    isError = result.isError,
                )
                messages += toolMessage
                sessionWriter.append(toolMessage)
            }
        }

        return "Stopped after reaching maxTurns=$maxTurns."
    }
}

data class AgentRunResult(
    val answer: String,
    val history: List<AgentMessage>,
    val totalTokens: Int = 0,
)

interface AgentObserver {
    suspend fun onModelRequest(turn: Int)
    suspend fun onToolCall(name: String)
    suspend fun onToolResult(name: String, result: ToolResult)
}

object NoOpAgentObserver : AgentObserver {
    override suspend fun onModelRequest(turn: Int) = Unit
    override suspend fun onToolCall(name: String) = Unit
    override suspend fun onToolResult(name: String, result: ToolResult) = Unit
}
