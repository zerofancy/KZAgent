package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import com.kzagent.kagent.tools.ToolQuota
import com.kzagent.kagent.tools.ToolRegistry
import com.kzagent.kagent.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class CodingAgent(
    private val model: ChatModel,
    private val tools: ToolRegistry,
    private val promptBuilder: PromptBuilder,
    private val sessionWriter: SessionWriter,
    private val quota: ToolQuota = ToolQuota(),
    private val observer: AgentObserver = NoOpAgentObserver,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(userPrompt: String): String {
        return runConversation(userPrompt).answer
    }

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
        return AgentRunResult(
            answer = answer,
            history = messages.toList(),
            totalTokens = contextTokens,
            quotaConsumed = quota.totalConsumed,
        )
    }

    private suspend fun runInternal(
        system: AgentMessage.System,
        messages: MutableList<AgentMessage>,
        onTokens: (Int) -> Unit = {},
    ): String {
        var contextTokens = 0
        var loopCount = 0
        while (true) {
            loopCount++
            observer.onModelRequest(loopCount)

            val contextMessages = buildList {
                add(system)
                if (quota.isLow) {
                    add(AgentMessage.System(buildQuotaWarning()))
                }
                addAll(messages)
            }
            val reply = model.chat(contextMessages, tools.toolSchemas())
            contextTokens = maxOf(contextTokens, reply.promptTokens ?: contextTokens)
            onTokens(contextTokens)
            val assistant = AgentMessage.Assistant(reply.content, reply.toolCalls)
            messages += assistant
            sessionWriter.append(assistant, tokens = contextTokens)

            reply.content?.takeIf { it.isNotBlank() }?.let { observer.onAssistantMessage(it) }

            if (reply.toolCalls.isEmpty()) {
                return reply.content.orEmpty().ifBlank { "(model returned an empty final response)" }
            }

            for (toolCall in reply.toolCalls) {
                val tool = tools.get(toolCall.name)
                observer.onToolCallStarted(toolCall.name, toolCall.argumentsJson)

                if (tool != null && !quota.canAfford(tool.cost)) {
                    if (quota.isLow) {
                        val extended = quota.extend()
                        if (extended) {
                            val extMsg = AgentMessage.System(
                                "配额已自动扩容至 ${quota.remaining} 积分（第 ${quota.extensionsUsed} 次扩容），请继续工作。"
                            )
                            messages += extMsg
                        }
                    }
                    if (!quota.canAfford(tool.cost)) {
                        val errorMsg = "配额不足：${toolCall.name} 需要 ${tool.cost} 积分，剩余 ${quota.remaining} 积分。任务终止。"
                        val toolMessage = AgentMessage.Tool(
                            toolCallId = toolCall.id,
                            name = toolCall.name,
                            content = errorMsg,
                            isError = true,
                        )
                        messages += toolMessage
                        sessionWriter.append(toolMessage)
                        observer.onToolResult(toolCall.name, ToolResult.error(errorMsg))
                        return reply.content.orEmpty().ifBlank { "Stopped: quota exhausted." }
                    }
                }

                if (tool != null) {
                    quota.deduct(tool.cost)
                }

                val result = if (tool == null) {
                    ToolResult.error("Unknown tool: ${toolCall.name}")
                } else {
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
    }
}

data class AgentRunResult(
    val answer: String,
    val history: List<AgentMessage>,
    val totalTokens: Int = 0,
    val quotaConsumed: Int = 0,
)

interface AgentObserver {
    suspend fun onModelRequest(turn: Int) = Unit
    suspend fun onAssistantMessage(content: String) = Unit
    suspend fun onToolCallStarted(name: String, argsJson: String) = Unit
    suspend fun onToolResult(name: String, result: ToolResult) = Unit
}

object NoOpAgentObserver : AgentObserver

private fun buildQuotaWarning(): String = buildString {
    appendLine("=== 配额提醒 ===")
    appendLine("工具调用消耗积分，不同操作成本不同：")
    appendLine("- 读操作（list_files, read_file, search_text）: 1 积分")
    appendLine("- 写操作（apply_patch）: 2 积分")
    appendLine("- 终端操作（run_command）: 5 积分")
    appendLine()
    append("剩余积分较少，请评估任务进度：")
    append("如果接近完成请尽快收尾；如果还需要多次操作请继续，系统将自动扩容。")
}
