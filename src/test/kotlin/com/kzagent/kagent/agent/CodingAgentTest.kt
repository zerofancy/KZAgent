package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import com.kzagent.kagent.llm.ModelToolCall
import com.kzagent.kagent.tools.AlwaysApprovePolicy
import com.kzagent.kagent.tools.LocalTools
import com.kzagent.kagent.tools.PathGuard
import com.kzagent.kagent.tools.ToolQuota
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodingAgentTest {
    @Test
    fun loopExecutesToolCallThenReturnsFinalAnswer() = runBlocking {
        val dir = Files.createTempDirectory("kagent-agent-test")
        Files.writeString(dir.resolve("sample.txt"), "hello")
        val model = FakeModel()
        val agent = CodingAgent(
            model = model,
            tools = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(dir.resolve("session.jsonl")),
            quota = ToolQuota(baseCredits = 10),
        )

        val answer = agent.run("list files")

        assertContains(answer, "saw sample.txt")
        assertTrue(model.calls >= 2)
    }

    @Test
    fun compressedSummaryIsRetainedAndSentOnNextTurn() = runBlocking {
        val dir = Files.createTempDirectory("kagent-compression-test")
        val sessionFile = dir.resolve("session.jsonl")
        val model = CompressionModel()
        val agent = CodingAgent(
            model = model,
            tools = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(sessionFile),
        )
        val history = (1..8).map { AgentMessage.User("old message $it") }

        val compressed = agent.compressHistory(history, keepLastN = 2)
        assertIs<AgentMessage.Summary>(compressed.first())

        val reloaded = SessionReader(dir).loadFile(sessionFile)
        assertEquals(compressed, reloaded)

        agent.runConversation("continue", compressed)
        assertTrue(model.lastMessages.any { it is AgentMessage.Summary && it.content == "durable summary" })
    }

    @Test
    fun compressionDoesNotOrphanToolResults() = runBlocking {
        val dir = Files.createTempDirectory("kagent-tool-boundary-test")
        val agent = CodingAgent(
            model = CompressionModel(),
            tools = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(dir.resolve("session.jsonl")),
        )
        val call = ModelToolCall("call-1", "read_file", "{}")
        val history = listOf(
            AgentMessage.User("old"),
            AgentMessage.Assistant(null, listOf(call)),
            AgentMessage.Tool("call-1", "read_file", "result", false),
            AgentMessage.User("recent one"),
            AgentMessage.Assistant("recent two"),
        )

        val compressed = agent.compressHistory(history, keepLastN = 3)
        val firstNonSummary = compressed.dropWhile { it is AgentMessage.Summary }.first()
        assertIs<AgentMessage.Assistant>(firstNonSummary)
        assertEquals("call-1", firstNonSummary.toolCalls.single().id)
    }

    private class CompressionModel : ChatModel {
        var lastMessages: List<AgentMessage> = emptyList()

        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
            lastMessages = messages
            return if (tools.isEmpty()) AssistantReply(content = "durable summary")
            else AssistantReply(content = "continued")
        }
    }

    private class FakeModel : ChatModel {
        var calls = 0

        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
            calls += 1
            return if (messages.none { it is AgentMessage.Tool }) {
                AssistantReply(
                    content = null,
                    toolCalls = listOf(
                        ModelToolCall(
                            id = "call-1",
                            name = "list_files",
                            argumentsJson = """{"path":".","max_depth":1}""",
                        ),
                    ),
                )
            } else {
                val toolOutput = messages.filterIsInstance<AgentMessage.Tool>().last().content
                AssistantReply(content = "saw sample.txt in tool output: ${toolOutput.contains("sample.txt")}")
            }
        }
    }
}

