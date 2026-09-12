package com.kzagent.kagent.desktop.app

import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.agent.estimateContextTokens
import com.kzagent.kagent.tools.ApprovalMode
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.DisplayMessage
import com.kzagent.kagent.desktop.SessionData
import com.kzagent.kagent.desktop.formatToolCallSummary
import com.kzagent.kagent.tools.ApprovalSource
import com.kzagent.kagent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Create an [AgentObserver] that forwards agent lifecycle events to [session]'s
 * message list and status bar. The observer references [approvalMode] to produce
 * human-readable status labels for tool calls.
 */
internal fun createAgentObserver(
    session: SessionData,
    approvalMode: ApprovalMode?,
): AgentObserver {
    return object : AgentObserver {
        override suspend fun onContextCompressionStarted(usagePercent: Int) {
            session.status = "上下文超 80%，自动压缩..."
            session.messages.add(
                DisplayMessage(
                    "tool_result",
                    "⚠️ 上下文使用率达 $usagePercent%，自动触发压缩...",
                    timestampMillis = Instant.now().toEpochMilli(),
                ),
            )
        }
        override suspend fun onContextCompressionCompleted(estimatedTokens: Int) {
            session.usedTokens = estimatedTokens
            session.status = "上下文压缩完成"
            session.messages.add(
                DisplayMessage(
                    "tool_result",
                    "✅ 上下文已自动压缩，保留最近消息并生成了历史摘要。",
                    timestampMillis = Instant.now().toEpochMilli(),
                ),
            )
        }
        override suspend fun onModelRequest(turn: Int) {
            session.status = "请求模型（第 ${turn} 轮）..."
        }
        override suspend fun onAssistantMessage(content: String) {
            session.messages.add(
                DisplayMessage(
                    "assistant",
                    content,
                    timestampMillis = Instant.now().toEpochMilli()
                )
            )
        }
        override suspend fun onToolCallStarted(name: String, argsJson: String) {
            val summary = formatToolCallSummary(name, argsJson)
            session.messages.add(
                DisplayMessage(
                    "tool_call",
                    summary,
                    collapsible = true,
                    collapsed = false,
                    timestampMillis = Instant.now().toEpochMilli(),
                ),
            )
            session.status = when (name) {
                "run_command" -> when (approvalMode) {
                    ApprovalMode.MANUAL -> "等待命令审批..."
                    ApprovalMode.AUTO -> "正在自动审批命令..."
                    ApprovalMode.FULL -> "执行命令..."
                    null -> "正在自动审批命令..."
                }
                "read_file" -> when (approvalMode) {
                    ApprovalMode.FULL -> "读取文件..."
                    else -> "检查文件读取权限..."
                }
                "fetch_web_page" -> "正在获取并解析网页..."
                "todo_read" -> "正在查看 Todo..."
                "todo_write" -> "正在更新 Todo..."
                "ask_user" -> "等待用户回答..."
                else -> "执行工具：$name"
            }
        }
        override suspend fun onToolResult(name: String, result: ToolResult) {
            session.messages.add(
                DisplayMessage(
                    "tool_result",
                    result.content,
                    collapsible = true,
                    collapsed = false,
                    timestampMillis = Instant.now().toEpochMilli(),
                ),
            )
            session.status = when (result.approvalSource) {
                ApprovalSource.STATIC_RULE -> "静态规则已放行：$name"
                ApprovalSource.APPROVAL_AGENT ->
                    if (result.isError) "审批 Agent 已拒绝：$name" else "审批 Agent 已放行：$name"
                ApprovalSource.HUMAN ->
                    if (result.isError) "人工已拒绝：$name" else "人工已批准：$name"
                ApprovalSource.FULL_MODE -> "全部放行：$name"
                null -> if (result.isError) "工具返回错误：$name" else "工具完成：$name"
            }
        }
    }
}

/**
 * Compress [session]'s conversation history in-place and update the message list.
 * Returns `true` on success, `false` on failure. When [manageBusyState] is `true`
 * the function toggles [SessionData.isBusy] automatically.
 */
internal suspend fun performCompression(session: SessionData, manageBusyState: Boolean = true): Boolean {
    if ((manageBusyState && session.isBusy) || session.runtime == null) return false
    if (manageBusyState) session.isBusy = true
    session.status = "正在压缩上下文..."
    return try {
        val compressed = session.runtime!!.agent.compressHistory(session.conversationHistory)
        session.conversationHistory = compressed
        session.usedTokens = estimateContextTokens(compressed)
        session.messages.add(
            DisplayMessage(
                "tool_result",
                "✅ 上下文已压缩。之前的对话已总结为摘要，保留最近几条消息。",
                timestampMillis = Instant.now().toEpochMilli(),
            ),
        )
        session.status = "就绪"
        true
    } catch (error: CancellationException) {
        throw error
    } catch (e: Exception) {
        session.error = "压缩失败: ${SecretRedactor.redact(e.message ?: e.toString())}"
        session.status = "压缩失败"
        false
    } finally {
        if (manageBusyState) session.isBusy = false
    }
}
