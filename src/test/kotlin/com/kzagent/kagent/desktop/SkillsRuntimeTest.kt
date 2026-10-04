package com.kzagent.kagent.desktop

import com.kzagent.kagent.AgentRuntime
import com.kzagent.kagent.agent.*
import com.kzagent.kagent.llm.*
import com.kzagent.kagent.tools.*
import com.kzagent.kagent.todo.TodoSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.*

class SkillsRuntimeTest {
    private fun runtime(session: SessionData, close: () -> Unit): AgentRuntime {
        val model = object : ChatModel {
            override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply = error("No network expected")
        }
        return AgentRuntime(session.workspace,
            CodingAgent(model, ToolRegistry(emptyList()), PromptBuilder(session.workspace), SessionWriter(session.sessionFile)),
            SessionReader(session.sessionFile.parent), 100000, session.modelSelection,
            MutableStateFlow(TodoSnapshot()), close)
    }

    @Test fun refreshDefersUntilNextTurnAndPreservesCompressedHistory() = runBlocking {
        val root = Files.createTempDirectory("skills-runtime")
        var created = 0
        var closed = 0
        var fail = false
        SessionManager(AlwaysApprovePolicy, root, createRuntime = { session, _ ->
            if (fail) error("initialization failed")
            created++
            runtime(session) { closed++ }
        }).use { manager ->
            manager.loadOrCreate(root)
            val session = manager.activeSession()
            SessionWriter(session.sessionFile).append(AgentMessage.Summary("compressed history"))
            session.reloadSavedHistory()
            manager.ensureRuntime(session, NoOpAgentObserver)
            val first = session.runtime
            session.isBusy = true
            val job = Job()
            session.currentJob = job
            manager.markSkillsChanged()
            manager.markSkillsChanged()
            assertSame(first, session.runtime)
            assertTrue(job.isActive)
            assertEquals(0, closed)
            session.isBusy = false
            job.cancel()
            manager.ensureRuntime(session, NoOpAgentObserver, refreshSkills = true)
            assertEquals(2, created)
            assertEquals(1, closed)
            assertEquals(listOf(AgentMessage.Summary("compressed history")), session.conversationHistory)
            manager.ensureRuntime(session, NoOpAgentObserver, refreshSkills = true)
            assertEquals(2, created)
            fail = true
            manager.markSkillsChanged()
            assertFailsWith<IllegalStateException> { manager.ensureRuntime(session, NoOpAgentObserver, true) }
            assertNull(session.runtime)
            assertEquals(2, closed)
            fail = false
            manager.ensureRuntime(session, NoOpAgentObserver, true)
            assertEquals(3, created)
        }
        assertEquals(3, closed)
    }

    @Test fun simultaneousInitializationCreatesOnlyOneRuntime() = runBlocking {
        val root = Files.createTempDirectory("skills-init")
        var created = 0
        var closed = 0
        SessionManager(AlwaysApprovePolicy, root, createRuntime = { session, _ ->
            created++
            runtime(session) { closed++ }
        }).use { manager ->
            manager.loadOrCreate(root)
            coroutineScope { repeat(5) { launch { manager.ensureRuntime(manager.activeSession(), NoOpAgentObserver, true) } } }
            assertEquals(1, created)
        }
        assertEquals(1, closed)
    }

    @Test fun cancellationClosesRuntimeCreatedOnIoThread() = runBlocking {
        val root = Files.createTempDirectory("skills-cancel-init")
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        var closed = 0
        SessionManager(AlwaysApprovePolicy, root, createRuntime = { session, _ ->
            started.complete(Unit)
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            runtime(session) { closed++ }
        }).use { manager ->
            manager.loadOrCreate(root)
            val init = launch { manager.ensureRuntime(manager.activeSession(), NoOpAgentObserver) }
            started.await()
            init.cancel()
            release.countDown()
            init.join()
            assertEquals(1, closed)
            assertNull(manager.activeSession().runtime)
        }
        assertEquals(1, closed)
    }
}
