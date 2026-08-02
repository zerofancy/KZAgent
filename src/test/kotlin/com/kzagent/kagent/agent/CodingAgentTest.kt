package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import com.kzagent.kagent.llm.ModelToolCall
import com.kzagent.kagent.tools.AlwaysApprovePolicy
import com.kzagent.kagent.tools.LocalTools
import com.kzagent.kagent.tools.PathGuard
import com.kzagent.kagent.tools.TodoTools
import com.kzagent.kagent.tools.ToolQuota
import com.kzagent.kagent.todo.TodoFiles
import com.kzagent.kagent.todo.TodoOperation
import com.kzagent.kagent.todo.TodoStore
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

    @Test
    fun automaticallyCompressesHistoryBeforeARequestInSharedRuntime() = runBlocking {
        val dir = Files.createTempDirectory("kagent-auto-compression-test")
        val sessionFile = dir.resolve("session.jsonl")
        val model = CompressionModel()
        val observer = RecordingCompressionObserver()
        val agent = CodingAgent(
            model = model,
            tools = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(sessionFile),
            observer = observer,
            contextWindowSize = 100,
        )
        val history = (1..8).map { AgentMessage.User("old message $it") }

        val result = agent.runConversation("continue", history)

        assertIs<AgentMessage.Summary>(result.history.first())
        assertEquals(1, observer.started)
        assertEquals(1, observer.completed)
        assertTrue(observer.usagePercent > 80)
        assertEquals(result.history, SessionReader(dir).loadFile(sessionFile))
    }

    @Test
    fun tokenUsagePrefersApiTotalAndFallsBackToLocalEstimate() = runBlocking {
        val dir = Files.createTempDirectory("kagent-token-usage-test")
        val reportedAgent = CodingAgent(
            model = FixedReplyModel(
                AssistantReply(content = "done", totalTokens = 321, promptTokens = 123),
            ),
            tools = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(dir.resolve("reported.jsonl")),
        )
        val estimatedAgent = CodingAgent(
            model = FixedReplyModel(AssistantReply(content = "done")),
            tools = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(dir.resolve("estimated.jsonl")),
        )

        assertEquals(321, reportedAgent.runConversation("hello").totalTokens)
        assertTrue(estimatedAgent.runConversation("hello").totalTokens > 0)
    }

    @Test
    fun generatedTitleIsCleanedAndLimitedToThirtyCharacters() = runBlocking {
        val dir = Files.createTempDirectory("kagent-title-test")
        val model = object : ChatModel {
            override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>) =
                AssistantReply(content = "\"1234567890123456789012345678901234567890\"\nextra")
        }
        val agent = CodingAgent(
            model = model,
            tools = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(dir.resolve("session.jsonl")),
        )

        assertEquals("123456789012345678901234567890", agent.generateTitle("fallback"))
    }

    @Test
    fun todoReminderStartsAfterSevenRepliesAndRespectsFourReplyCooldown() = runBlocking {
        val dir = Files.createTempDirectory("kagent-todo-reminder-agent")
        val sessionFile = dir.resolve("session.jsonl")
        val store = TodoStore(TodoFiles.forSession(sessionFile))
        store.applyOperations(
            listOf(
                TodoOperation(
                    type = TodoOperation.Type.CREATE,
                    id = "task",
                    content = "Task",
                ),
            ),
        )
        val model = ReminderRecordingModel()
        val agent = CodingAgent(
            model = model,
            tools = TodoTools(store).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(sessionFile),
            todoStore = store,
        )
        var history: List<AgentMessage> = emptyList()

        repeat(12) { index ->
            history = agent.runConversation("turn $index", history).history
        }

        assertEquals(
            listOf(false, false, false, false, false, false, false, true, false, false, false, true),
            model.reminderRequests,
        )
    }

    @Test
    fun finalReplyClearsOnlyAnAllCompletedTodoList() = runBlocking {
        val dir = Files.createTempDirectory("kagent-completed-todo-cleanup")
        val sessionFile = dir.resolve("session.jsonl")
        val store = TodoStore(TodoFiles.forSession(sessionFile))
        store.applyOperations(
            listOf(
                TodoOperation(
                    type = TodoOperation.Type.CREATE,
                    id = "task",
                    content = "Task",
                ),
                TodoOperation(
                    type = TodoOperation.Type.SET_STATUS,
                    id = "task",
                    status = com.kzagent.kagent.todo.TodoStatus.COMPLETED,
                ),
            ),
        )
        val agent = CodingAgent(
            model = FixedReplyModel(AssistantReply(content = "done")),
            tools = TodoTools(store).registry(),
            promptBuilder = PromptBuilder(dir),
            sessionWriter = SessionWriter(sessionFile),
            todoStore = store,
        )

        agent.runConversation("finish")

        assertTrue(store.current().items.isEmpty())
        assertTrue(TodoStore(TodoFiles.forSession(sessionFile)).current().items.isEmpty())
    }

    @Test
    fun dynamicNoticesPreservePriorConversationAsCacheablePrefix() {
        val system = AgentMessage.System("base")
        val previousUser = AgentMessage.User("previous")
        val previousAssistant = AgentMessage.Assistant("previous answer")
        val currentUser = AgentMessage.User("current")
        val toolCall = ModelToolCall("call-1", "read_file", """{"path":"sample.txt"}""")
        val currentAssistant = AgentMessage.Assistant(null, listOf(toolCall))
        val toolResult = AgentMessage.Tool("call-1", "read_file", "content", false)
        val messages = listOf(
            previousUser,
            previousAssistant,
            currentUser,
            currentAssistant,
            toolResult,
        )

        val withoutNotices = buildModelContextMessages(system, messages)
        val withNotices = buildModelContextMessages(
            system = system,
            messages = messages,
            quotaWarning = "quota",
            todoReminder = "todo",
        )

        assertEquals(
            listOf(system, previousUser, previousAssistant),
            withNotices.take(3),
        )
        assertEquals(
            listOf(
                system,
                previousUser,
                previousAssistant,
                AgentMessage.System("quota"),
                AgentMessage.System("todo"),
                currentUser,
                currentAssistant,
                toolResult,
            ),
            withNotices,
        )
        assertEquals(listOf(system) + messages, withoutNotices)
        assertEquals(
            listOf(currentAssistant, toolResult),
            withNotices.takeLast(2),
        )
    }

    private class CompressionModel : ChatModel {
        var lastMessages: List<AgentMessage> = emptyList()

        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
            lastMessages = messages
            return if (tools.isEmpty()) AssistantReply(content = "durable summary")
            else AssistantReply(content = "continued")
        }
    }

    private class RecordingCompressionObserver : AgentObserver {
        var started = 0
        var completed = 0
        var usagePercent = 0

        override suspend fun onContextCompressionStarted(usagePercent: Int) {
            started++
            this.usagePercent = usagePercent
        }

        override suspend fun onContextCompressionCompleted(estimatedTokens: Int) {
            completed++
            assertTrue(estimatedTokens > 0)
        }
    }

    private class FixedReplyModel(private val reply: AssistantReply) : ChatModel {
        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply = reply
    }

    private class ReminderRecordingModel : ChatModel {
        val reminderRequests = mutableListOf<Boolean>()

        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
            reminderRequests += messages.any {
                it is AgentMessage.System && it.content.contains("TODO REMINDER")
            }
            return AssistantReply(content = "continued")
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
