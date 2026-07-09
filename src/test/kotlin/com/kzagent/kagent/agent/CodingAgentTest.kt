package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import com.kzagent.kagent.llm.ModelToolCall
import com.kzagent.kagent.tools.AlwaysApprovePolicy
import com.kzagent.kagent.tools.LocalTools
import com.kzagent.kagent.tools.PathGuard
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
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
            sessionWriter = SessionWriter(dir),
            maxTurns = 3,
        )

        val answer = agent.run("list files")

        assertContains(answer, "saw sample.txt")
        assertTrue(model.calls >= 2)
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

