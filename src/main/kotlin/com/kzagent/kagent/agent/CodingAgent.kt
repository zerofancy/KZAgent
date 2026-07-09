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
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Start a fresh conversation with a single user prompt.
     */
    suspend fun run(userPrompt: String): String {
        val messages = mutableListOf<AgentMessage>()
        val system = AgentMessage.System(promptBuilder.build())
        messages += AgentMessage.User(userPrompt)
        sessionWriter.append(system)
        sessionWriter.append(messages.last())
        return runInternal(system, messages)
    }

    /**
     * Continue a conversation from previous history.
     * [history] should be previous messages (system messages will be filtered out
     * since a fresh system prompt is provided).
     */
    suspend fun run(userPrompt: String, history: List<AgentMessage>): String {
        val messages = history.filter { it !is AgentMessage.System }.toMutableList()
        val system = AgentMessage.System(promptBuilder.build())
        messages += AgentMessage.User(userPrompt)
        sessionWriter.append(messages.last())
        return runInternal(system, messages)
    }

    private suspend fun runInternal(
        system: AgentMessage.System,
        messages: MutableList<AgentMessage>,
    ): String {
        repeat(maxTurns) { turn ->
            println("Turn ${turn + 1}: asking model...")
            val reply = model.chat(listOf(system) + messages, tools.toolSchemas())
            val assistant = AgentMessage.Assistant(reply.content, reply.toolCalls)
            messages += assistant
            sessionWriter.append(assistant)

            if (reply.toolCalls.isEmpty()) {
                return reply.content.orEmpty().ifBlank { "(model returned an empty final response)" }
            }

            for (toolCall in reply.toolCalls) {
                val tool = tools.get(toolCall.name)
                val result = if (tool == null) {
                    ToolResult.error("Unknown tool: ${toolCall.name}")
                } else {
                    println("Tool call: ${toolCall.name}")
                    runCatching {
                        val args = json.parseToJsonElement(toolCall.argumentsJson) as? JsonObject
                            ?: throw IllegalArgumentException("Tool arguments must be a JSON object.")
                        tool.handler(args)
                    }.getOrElse {
                        ToolResult.error(it.message ?: it.toString())
                    }
                }

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

