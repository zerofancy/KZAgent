package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import com.kzagent.kagent.tools.ToolRegistry
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CodingAgentOutputTest {
    @Test
    fun defaultObserverDoesNotPrintProgressToStdout() = runBlocking {
        val dir = Files.createTempDirectory("kagent-silent-agent-test")
        val agent = CodingAgent(
            model = FinalAnswerModel,
            tools = ToolRegistry(emptyList()),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(dir.resolve("session.jsonl")),
        )
        val originalOut = System.out
        val captured = ByteArrayOutputStream()

        try {
            System.setOut(PrintStream(captured, true, Charsets.UTF_8.name()))
            assertEquals("done", agent.run("hello"))
        } finally {
            System.setOut(originalOut)
        }

        assertEquals("", captured.toString(Charsets.UTF_8.name()))
    }

    private object FinalAnswerModel : ChatModel {
        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
            return AssistantReply(content = "done")
        }
    }
}
