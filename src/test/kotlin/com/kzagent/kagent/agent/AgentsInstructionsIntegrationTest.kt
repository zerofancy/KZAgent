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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentsInstructionsIntegrationTest {
    @Test
    fun rootIsSnapshottedAndNestedInstructionsReloadAfterCompression() = runBlocking {
        val workspace = Files.createTempDirectory("kagent-agents-integration-test")
        val source = workspace.resolve("src").also(Files::createDirectories)
        val feature = source.resolve("feature").also(Files::createDirectories)
        val rootFile = workspace.resolve("AGENTS.md")
        Files.writeString(rootFile, "root version one")
        Files.writeString(source.resolve("AGENTS.md"), "source guidance")
        Files.writeString(feature.resolve("AGENTS.md"), "feature guidance")
        Files.writeString(feature.resolve("Target.kt"), "class Target")

        val loader = AgentsInstructionsLoader(workspace)
        val rootSnapshot = loader.loadRoot()!!.content
        Files.writeString(rootFile, "root version two")
        val model = RepeatedReadModel("src/feature/Target.kt")
        val agent = CodingAgent(
            model = model,
            tools = LocalTools(PathGuard(workspace), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(workspace, rootInstructions = rootSnapshot),
            sessionWriter = SessionWriter(workspace.resolve("session.jsonl")),
            instructionsLoader = loader,
        )

        val first = agent.runConversation("first")
        val firstFinalRequest = model.finalRequests.single()
        val firstInstructions = firstFinalRequest.filterIsInstance<AgentMessage.ScopedInstruction>()
        assertEquals(listOf("source guidance", "feature guidance"), firstInstructions.map { it.content })
        assertTrue(model.systemPrompts.all { "root version one" in it })
        assertTrue(model.systemPrompts.none { "root version two" in it })

        val compressed = agent.compressHistory(first.history, keepLastN = 6)
        assertEquals(2, compressed.count { it is AgentMessage.ScopedInstruction })

        agent.runConversation("second", compressed)
        val secondFinalRequest = model.finalRequests.last()
        val secondInstructions = secondFinalRequest.filterIsInstance<AgentMessage.ScopedInstruction>()
        assertEquals(4, secondInstructions.size)
        assertEquals(2, secondInstructions.count { it.content == "source guidance" })
        assertEquals(2, secondInstructions.count { it.content == "feature guidance" })
    }

    @Test
    fun failedReadDoesNotLoadNestedInstructions() = runBlocking {
        val workspace = Files.createTempDirectory("kagent-agents-failed-read-test")
        val source = workspace.resolve("src").also(Files::createDirectories)
        Files.writeString(source.resolve("AGENTS.md"), "must not load")
        val model = SingleReadModel("src/missing.kt")
        val agent = CodingAgent(
            model = model,
            tools = LocalTools(PathGuard(workspace), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(workspace),
            sessionWriter = SessionWriter(workspace.resolve("session.jsonl")),
            instructionsLoader = AgentsInstructionsLoader(workspace),
        )

        agent.run("read missing")

        assertFalse(model.lastMessages.any { it is AgentMessage.ScopedInstruction })
        assertTrue(model.lastMessages.filterIsInstance<AgentMessage.Tool>().single().isError)
    }

    @Test
    fun approvedExternalReadNeverLoadsExternalInstructions() = runBlocking {
        val workspace = Files.createTempDirectory("kagent-agents-external-read-workspace")
        val external = Files.createTempDirectory("kagent-agents-external-read-target")
        val target = external.resolve("Target.kt")
        Files.writeString(external.resolve("AGENTS.md"), "must never load")
        Files.writeString(target, "class ExternalTarget")
        val model = SingleReadModel(target.toString().replace("\\", "\\\\"))
        val agent = CodingAgent(
            model = model,
            tools = LocalTools(PathGuard(workspace), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(workspace),
            sessionWriter = SessionWriter(workspace.resolve("session.jsonl")),
            instructionsLoader = AgentsInstructionsLoader(workspace),
        )

        agent.run("read external")

        assertFalse(model.lastMessages.any { it is AgentMessage.ScopedInstruction })
        val toolResult = model.lastMessages.filterIsInstance<AgentMessage.Tool>().single()
        assertFalse(toolResult.isError)
        assertTrue("ExternalTarget" in toolResult.content)
    }

    private class RepeatedReadModel(private val path: String) : ChatModel {
        val finalRequests = mutableListOf<List<AgentMessage>>()
        val systemPrompts = mutableListOf<String>()
        private var activePrompt: String? = null
        private var readsIssued = 0
        private var callId = 0

        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: List<JsonObject>,
        ): AssistantReply {
            if (tools.isEmpty()) return AssistantReply(content = "compressed summary")
            messages.filterIsInstance<AgentMessage.System>().firstOrNull()?.let {
                systemPrompts += it.content
            }
            val prompt = messages.filterIsInstance<AgentMessage.User>().last().content
            if (activePrompt != prompt) {
                activePrompt = prompt
                readsIssued = 0
            }
            val desiredReads = if (prompt == "first") 2 else 1
            if (readsIssued < desiredReads) {
                readsIssued++
                callId++
                return AssistantReply(
                    content = null,
                    toolCalls = listOf(
                        ModelToolCall(
                            id = "call-$callId",
                            name = "read_file",
                            argumentsJson = """{"path":"$path"}""",
                        ),
                    ),
                )
            }
            finalRequests += messages
            return AssistantReply(content = "done")
        }
    }

    private class SingleReadModel(private val path: String) : ChatModel {
        var lastMessages: List<AgentMessage> = emptyList()
        private var issued = false

        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: List<JsonObject>,
        ): AssistantReply {
            lastMessages = messages
            if (!issued) {
                issued = true
                return AssistantReply(
                    content = null,
                    toolCalls = listOf(
                        ModelToolCall(
                            id = "call-1",
                            name = "read_file",
                            argumentsJson = """{"path":"$path"}""",
                        ),
                    ),
                )
            }
            return AssistantReply(content = "done")
        }
    }
}
