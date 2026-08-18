package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import com.kzagent.kagent.tools.ToolQuota
import com.kzagent.kagent.tools.ToolRegistry
import com.kzagent.kagent.tools.ToolResult
import com.kzagent.kagent.todo.TodoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class CodingAgent(
    private val model: ChatModel,
    private val tools: ToolRegistry,
    private val promptBuilder: PromptBuilder,
    private val sessionWriter: SessionWriter,
    private val quota: ToolQuota = ToolQuota(),
    private val observer: AgentObserver = NoOpAgentObserver,
    private val instructionsLoader: AgentsInstructionsLoader? = null,
    private val contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW_SIZE,
    private val autoCompressionThresholdPercent: Int = DEFAULT_COMPRESSION_THRESHOLD_PERCENT,
    private val autoCompressionKeepLastN: Int = DEFAULT_COMPRESSION_KEEP_LAST_N,
    private val todoStore: TodoStore? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastKnownContextTokens = 0
    // Deduplicates scoped instructions between two successful compressions.
    // This is runtime state rather than conversation state so a compression can
    // deliberately start a fresh loading cycle even when recent copies survive.
    private val loadedScopedInstructionSources = mutableSetOf<java.nio.file.Path>()

    init {
        require(contextWindowSize > 0) { "contextWindowSize must be positive." }
        require(autoCompressionThresholdPercent in 1..99) {
            "autoCompressionThresholdPercent must be between 1 and 99."
        }
        require(autoCompressionKeepLastN >= 1) { "autoCompressionKeepLastN must be positive." }
    }

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
        var messages = history.filter { it !is AgentMessage.System }.toMutableList()
        val system = AgentMessage.System(promptBuilder.build())
        val userMessage = AgentMessage.User(userPrompt)
        val prospectiveTokens = estimateContextTokens(
            buildList {
                add(system)
                addAll(messages)
                add(userMessage)
            },
        )
        val usageBeforeRun = maxOf(lastKnownContextTokens, prospectiveTokens)
        if (
            isAboveAutoCompressionThreshold(usageBeforeRun) &&
            compressionSplitIndex(messages, autoCompressionKeepLastN) != null
        ) {
            observer.onContextCompressionStarted(
                usagePercent = contextUsagePercent(usageBeforeRun, contextWindowSize),
            )
            messages = compressHistory(messages, autoCompressionKeepLastN).toMutableList()
            observer.onContextCompressionCompleted(
                estimatedTokens = estimateContextTokens(
                    buildList {
                        add(system)
                        addAll(messages)
                        add(userMessage)
                    },
                ),
            )
        }
        messages += userMessage
        if (history.isEmpty()) {
            sessionWriter.append(system)
        }
        sessionWriter.append(messages.last())
        var contextTokens = 0
        val answer = runInternal(system, messages) { contextTokens = it }
        lastKnownContextTokens = contextTokens
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

            val todoReminderInjected = todoStore?.shouldInjectReminder() == true
            val contextMessages = buildModelContextMessages(
                system = system,
                messages = messages,
                quotaWarning = buildQuotaWarning().takeIf { quota.isLow },
                todoReminder = buildTodoReminder().takeIf { todoReminderInjected },
            )
            val reply = model.chatStreaming(contextMessages, tools.toolSchemas()) {
                // Streaming keeps the HTTP connection alive; content is accumulated in the reply.
            }
            val todoToolCalled = reply.toolCalls.any { it.name == "todo_read" || it.name == "todo_write" }
            todoStore?.recordAssistantTurn(
                todoToolCalled = todoToolCalled,
                reminderInjected = todoReminderInjected,
            )
            val assistant = AgentMessage.Assistant(reply.content, reply.toolCalls)
            val reportedTotalTokens = reply.totalTokens?.takeIf { it > 0 }
            val reportedPromptTokens = reply.promptTokens?.takeIf { it > 0 }
            val replyContextTokens = when {
                reportedTotalTokens != null -> reportedTotalTokens
                reportedPromptTokens != null ->
                    reportedPromptTokens + estimateContextTokens(listOf(assistant))
                else -> estimateContextTokens(contextMessages + assistant)
            }
            contextTokens = maxOf(contextTokens, replyContextTokens)
            onTokens(contextTokens)
            messages += assistant
            sessionWriter.append(assistant, tokens = contextTokens)

            reply.content?.takeIf { it.isNotBlank() }?.let { observer.onAssistantMessage(it) }

            if (reply.toolCalls.isEmpty()) {
                todoStore?.clearIfAllCompleted()
                return reply.content.orEmpty().ifBlank { "(model returned an empty final response)" }
            }

            // Hold newly discovered guidance until every tool result has been
            // appended. Inserting a system message between parallel tool results
            // would break the assistant -> tool-results message sequence.
            val pendingInstructions = mutableListOf<LoadedAgentsInstruction>()
            for (toolCall in reply.toolCalls) {
                val tool = tools.get(toolCall.name)
                observer.onToolCallStarted(toolCall.name, toolCall.argumentsJson)

                val args = if (tool != null) {
                    try {
                        json.parseToJsonElement(toolCall.argumentsJson) as? JsonObject
                            ?: throw IllegalArgumentException("Tool arguments must be a JSON object.")
                    } catch (error: Exception) {
                        val result = ToolResult.error(error.message ?: error.toString())
                        observer.onToolResult(toolCall.name, result)
                        val toolMessage = AgentMessage.Tool(toolCall.id, toolCall.name, result.content, true)
                        messages += toolMessage
                        sessionWriter.append(toolMessage)
                        continue
                    }
                } else null
                val cost = if (tool != null) {
                    try {
                        tool.costForArguments(requireNotNull(args)).also { require(it >= 0) { "Tool cost must not be negative." } }
                    } catch (error: Exception) {
                        val result = ToolResult.error(error.message ?: error.toString())
                        observer.onToolResult(toolCall.name, result)
                        val toolMessage = AgentMessage.Tool(toolCall.id, toolCall.name, result.content, true)
                        messages += toolMessage
                        sessionWriter.append(toolMessage)
                        continue
                    }
                } else 0
                if (tool != null && !quota.canAfford(cost)) {
                    if (quota.isLow) {
                        val extended = quota.extend()
                        if (extended) {
                            val extMsg = AgentMessage.System(
                                "配额已自动扩容至 ${quota.remaining} 积分（第 ${quota.extensionsUsed} 次扩容），请继续工作。"
                            )
                            messages += extMsg
                        }
                    }
                    if (!quota.canAfford(cost)) {
                        val errorMsg = "配额不足：${toolCall.name} 需要 $cost 积分，剩余 ${quota.remaining} 积分。任务终止。"
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
                    quota.deduct(cost)
                }

                val rawResult = if (tool == null) {
                    ToolResult.error("Unknown tool: ${toolCall.name}")
                } else {
                    try {
                        tool.handler(requireNotNull(args))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ToolResult.error(error.message ?: error.toString())
                    }
                }
                val loader = instructionsLoader
                val result = if (
                    rawResult.isError ||
                    rawResult.readPaths.isEmpty() ||
                    loader == null
                ) {
                    rawResult
                } else {
                    try {
                        val discovered = mutableListOf<LoadedAgentsInstruction>()
                        for (readPath in rawResult.readPaths) {
                            // Include instructions discovered by earlier tool calls
                            // in this same model turn, not only previous turns.
                            val excluded = buildSet {
                                addAll(loadedScopedInstructionSources)
                                addAll(pendingInstructions.map { it.source })
                                addAll(discovered.map { it.source })
                            }
                            discovered += withContext(Dispatchers.IO) {
                                loader.loadForRead(readPath, excluded)
                            }
                        }
                        pendingInstructions += discovered
                        loadedScopedInstructionSources += discovered.map { it.source }
                        rawResult
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ToolResult.error(
                            "File read completed, but applicable AGENTS.md instructions could not be loaded: " +
                                (error.message ?: error.toString()),
                        )
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

            for (instruction in pendingInstructions) {
                val instructionMessage = AgentMessage.ScopedInstruction(
                    sourcePath = instruction.sourcePath,
                    scopePath = instruction.scopePath,
                    content = instruction.content,
                )
                messages += instructionMessage
                sessionWriter.append(instructionMessage)
            }
        }
    }

    /**
     * Compress conversation history by asking the LLM to summarise older messages
     * while preserving the most recent [keepLastN] messages for immediate context.
     */
    suspend fun compressHistory(
        history: List<AgentMessage>,
        keepLastN: Int = 6,
    ): List<AgentMessage> {
        require(keepLastN >= 1) { "keepLastN must be positive." }
        val splitIndex = compressionSplitIndex(history, keepLastN) ?: return history
        val toSummarize = history.take(splitIndex)
        val recentMessages = history.drop(splitIndex)

        val conversationText = buildString {
            for (msg in toSummarize) {
                when (msg) {
                    is AgentMessage.User -> {
                        appendLine("--- User ---")
                        appendLine(msg.content.take(2000))
                    }
                    is AgentMessage.Assistant -> {
                        if (!msg.content.isNullOrBlank()) {
                            appendLine("--- Assistant ---")
                            appendLine(msg.content.take(2000))
                        }
                        if (msg.toolCalls.isNotEmpty()) {
                            appendLine("[Tool calls: ${msg.toolCalls.joinToString(", ") { it.name }}]")
                        }
                    }
                    is AgentMessage.Tool -> {
                        val short = if (msg.content.length > 300) {
                            msg.content.take(300) + "..."
                        } else {
                            msg.content
                        }
                        appendLine("--- Tool[${msg.name}] ---")
                        appendLine(short)
                    }
                    is AgentMessage.System -> { /* skip system prompts in summary */ }
                    is AgentMessage.ScopedInstruction -> {
                        /* Directory instructions in the summarized region are intentionally discarded. */
                    }
                    is AgentMessage.Summary -> {
                        appendLine("--- Previous summary ---")
                        appendLine(msg.content)
                    }
                }
            }
        }

        if (conversationText.isBlank()) return history

        val summaryMessages = listOf(
            AgentMessage.System("You are a helpful assistant that creates concise conversation summaries. Capture key decisions, file changes, findings, and current task state. Keep the summary under 400 words."),
            AgentMessage.User("Please summarise this AI coding session:\n\n$conversationText"),
        )

        val reply = model.chatStreaming(summaryMessages, emptyList()) { }
        val summary = reply.content?.trim().orEmpty()
        check(summary.isNotBlank()) { "The model returned an empty context summary." }

        val compressed = listOf(
            AgentMessage.Summary(summary),
        ) + recentMessages

        val compressedTokens = estimateContextTokens(compressed)
        sessionWriter.appendContextSnapshot(compressed, compressedTokens)
        lastKnownContextTokens = compressedTokens
        // A successful compression starts a new loading epoch by design. Scoped
        // instructions may therefore be loaded again on the next matching read.
        loadedScopedInstructionSources.clear()

        return compressed
    }

    private fun isAboveAutoCompressionThreshold(tokens: Int): Boolean =
        tokens.toLong() * 100 >
            contextWindowSize.toLong() * autoCompressionThresholdPercent

    companion object {
        private const val DEFAULT_CONTEXT_WINDOW_SIZE = 1_000_000
        private const val DEFAULT_COMPRESSION_THRESHOLD_PERCENT = 80
        private const val DEFAULT_COMPRESSION_KEEP_LAST_N = 6
    }

    /** Generate a concise session title from a user message or summary. */
    suspend fun generateTitle(sourceText: String): String {
        val messages = listOf(
            AgentMessage.System(
                "You are a helpful assistant that creates concise session titles. " +
                "Given a user query or conversation summary, produce a short title (under 20 characters if possible, max 30) " +
                "that captures the main topic. Reply with ONLY the title, no quotes, no punctuation, no extra text."
            ),
            AgentMessage.User("Please create a short title for: ${sourceText.take(800)}"),
        )
        val reply = model.chatStreaming(messages, emptyList()) { }
        val generated = reply.content.orEmpty()
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .trim('"', '\'', '“', '”')
            .trim()
        return generated.take(30).ifBlank {
            sourceText.lineSequence().firstOrNull().orEmpty().trim().take(30)
        }
    }
}

fun estimateContextTokens(messages: List<AgentMessage>): Int =
    messages.sumOf { message ->
        val characters = when (message) {
            is AgentMessage.System -> message.content.length
            is AgentMessage.Summary -> message.content.length
            is AgentMessage.ScopedInstruction ->
                message.sourcePath.length + message.scopePath.length + message.content.length
            is AgentMessage.User -> message.content.length
            is AgentMessage.Assistant -> message.content.orEmpty().length +
                message.toolCalls.sumOf { it.name.length + it.argumentsJson.length }
            is AgentMessage.Tool -> message.name.length + message.content.length
        }
        // Conservative language-agnostic approximation plus per-message framing.
        (characters + 2) / 3 + 8
    }

internal fun contextUsagePercent(tokens: Int, contextWindowSize: Int): Int {
    require(contextWindowSize > 0) { "contextWindowSize must be positive." }
    return ((tokens.coerceAtLeast(0).toLong() * 100) / contextWindowSize)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

private fun compressionSplitIndex(history: List<AgentMessage>, keepLastN: Int): Int? {
    if (history.size <= keepLastN) return null

    // Never start the retained window with an orphaned tool result. Move the
    // boundary back to the assistant message that issued the tool call(s).
    var splitIndex = history.size - keepLastN
    while (splitIndex > 0 && history[splitIndex] is AgentMessage.Tool) splitIndex--
    val candidate = history[splitIndex]
    if (candidate !is AgentMessage.Assistant || candidate.toolCalls.isEmpty()) {
        while (splitIndex > 0 && history[splitIndex - 1] is AgentMessage.Tool) splitIndex--
        if (splitIndex > 0) {
            val previous = history[splitIndex - 1]
            if (previous is AgentMessage.Assistant && previous.toolCalls.isNotEmpty()) splitIndex--
        }
    }
    return splitIndex.takeIf { it > 0 }
}

data class AgentRunResult(
    val answer: String,
    val history: List<AgentMessage>,
    val totalTokens: Int = 0,
    val quotaConsumed: Int = 0,
)

interface AgentObserver {
    suspend fun onContextCompressionStarted(usagePercent: Int) = Unit
    suspend fun onContextCompressionCompleted(estimatedTokens: Int) = Unit
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
    appendLine("- 静态网页获取（fetch_web_page）: 5 积分")
    appendLine("- Todo 规划（todo_read, todo_write）: 0 积分")
    appendLine()
    append("剩余积分较少，请评估任务进度：")
    append("如果接近完成请尽快收尾；如果还需要多次操作请继续，系统将自动扩容。")
}

private fun buildTodoReminder(): String =
    """
    === TODO REMINDER ===
    This session still has unfinished Todo items, and several assistant turns have passed without using the Todo tools.
    Before making more non-Todo tool calls, review the known Todo state and use todo_write to mark every stage that has genuinely finished.
    Do not rewrite an already-pending item merely to signal work in progress. Call todo_read first only if the current state is uncertain.
    """.trimIndent()

/**
 * Dynamic runtime notices belong to the current user turn, not the durable
 * conversation prefix. Keeping them immediately before the latest user message
 * preserves cache reuse for the base system prompt and all prior turns while
 * also avoiding insertion inside an assistant tool-call/result sequence.
 */
internal fun buildModelContextMessages(
    system: AgentMessage.System,
    messages: List<AgentMessage>,
    quotaWarning: String? = null,
    todoReminder: String? = null,
): List<AgentMessage> {
    val notices = buildList {
        quotaWarning?.let { add(AgentMessage.System(it)) }
        todoReminder?.let { add(AgentMessage.System(it)) }
    }
    if (notices.isEmpty()) return listOf(system) + messages

    val currentUserIndex = messages.indexOfLast { it is AgentMessage.User }
    if (currentUserIndex < 0) {
        return buildList {
            add(system)
            addAll(messages)
            addAll(notices)
        }
    }
    return buildList {
        add(system)
        addAll(messages.subList(0, currentUserIndex))
        addAll(notices)
        addAll(messages.subList(currentUserIndex, messages.size))
    }
}
