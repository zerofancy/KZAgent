package com.kzagent.kagent.desktop

import com.kzagent.kagent.agent.SessionWriter
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.ProviderId
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ApprovalDecision
import com.kzagent.kagent.tools.ApprovalResult
import com.kzagent.kagent.tools.ApprovalSource
import com.kzagent.kagent.todo.TodoFiles
import com.kzagent.kagent.todo.TodoOperation
import com.kzagent.kagent.todo.TodoStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionManagerTest {
    @Test
    fun modelSelectionSurvivesReloadInItsSidecar() = runBlocking {
        val workspace = testWorkspace()
        val sessionsRoot = Files.createTempDirectory("kagent-model-session-test")
        val openRouter = ModelSelection(ProviderId.OPENROUTER, "vendor/agent", 128_000, false)
        val manager = SessionManager(denyAll, sessionsRoot, initialDefaultModel = openRouter)
        manager.loadOrCreate(workspace)

        val session = manager.activeSession()
        assertEquals(openRouter, session.modelSelection)
        assertTrue(Files.isRegularFile(session.sessionFile.resolveSibling("${session.sessionFile.fileName}.model")))

        val replacement = openRouter.copy(modelId = "vendor/agent-2", contextWindowSize = 256_000)
        manager.updateModel(session, replacement)
        val reloaded = SessionManager(denyAll, sessionsRoot)
        reloaded.loadOrCreate(workspace)

        assertEquals(replacement, reloaded.activeSession().modelSelection)
    }
    private val denyAll = ApprovalPolicy {
        ApprovalResult(ApprovalDecision.DENY, ApprovalSource.HUMAN, "test")
    }

    @Test
    fun emptySessionAndRenamedTitleSurviveReload() = runBlocking {
        val workspace = testWorkspace()
        val sessionsRoot = Files.createTempDirectory("kagent-sessions-test")
        val manager = SessionManager(denyAll, sessionsRoot)
        manager.loadOrCreate(workspace)

        val session = manager.activeSession()
        assertTrue(Files.isRegularFile(session.sessionFile))
        assertTrue(manager.renameSession(0, "持久化名称"))

        val reloaded = SessionManager(denyAll, sessionsRoot)
        reloaded.loadOrCreate(workspace)
        assertEquals("持久化名称", reloaded.activeSession().name)
    }

    @Test
    fun mostRecentlyModifiedSessionLoadsFirst() = runBlocking {
        val workspace = testWorkspace()
        val sessionsRoot = Files.createTempDirectory("kagent-sessions-test")
        val manager = SessionManager(denyAll, sessionsRoot)
        manager.loadOrCreate(workspace)
        val older = manager.activeSession()
        manager.addNewSession()
        val newer = manager.activeSession()

        Files.setLastModifiedTime(older.sessionFile, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(newer.sessionFile, FileTime.fromMillis(2_000))

        val reloaded = SessionManager(denyAll, sessionsRoot)
        reloaded.loadOrCreate(workspace)
        assertEquals(newer.id, reloaded.activeSession().id)
    }

    @Test
    fun commandLineDesktopStartupCreatesFreshSessionInRequestedWorkspace() = runBlocking {
        val previousWorkspace = testWorkspace()
        val startupWorkspace = testWorkspace()
        val existing = StoredSession(
            id = "existing",
            name = "Existing",
            workspace = previousWorkspace,
            sessionFile = previousWorkspace.resolve("existing.jsonl"),
            history = listOf(AgentMessage.User("preserve me")),
            usedTokens = 21,
        )
        val repository = InMemorySessionRepository(listOf(existing))
        val manager = SessionManager(
            approvalPolicy = denyAll,
            sessionsRoot = startupWorkspace,
            repository = repository,
        )

        manager.loadOrCreate(startupWorkspace, createStartupSession = true)

        assertEquals(2, manager.sessions.size)
        val created = manager.activeSession()
        assertEquals(startupWorkspace, created.workspace)
        assertTrue(created.conversationHistory.isEmpty())
        assertTrue(created.messages.isEmpty())
        assertEquals(0, created.usedTokens)
        assertEquals(1, repository.createCalls)
        val preserved = manager.sessions.single { it.id == "existing" }
        assertEquals(previousWorkspace, preserved.workspace)
        assertEquals(listOf(AgentMessage.User("preserve me")), preserved.conversationHistory)
        assertEquals(21, preserved.usedTokens)
    }

    @Test
    fun conditionalRenameUsesStableIdAndPreservesNewerManualName() = runBlocking {
        val workspace = testWorkspace()
        val manager = SessionManager(denyAll, Files.createTempDirectory("kagent-sessions-test"))
        manager.loadOrCreate(workspace)
        val target = manager.activeSession()
        val initialRevision = target.titleRevision

        manager.addNewSession()
        val other = manager.activeSession()

        assertTrue(manager.renameSessionIfRevisionMatches(target.id, initialRevision, "自动标题"))
        assertEquals("自动标题", target.name)
        assertTrue(other.name.startsWith("新会话"))

        val targetIndex = manager.sessions.indexOfFirst { it.id == target.id }
        val revisionBeforeManualRename = target.titleRevision
        assertTrue(manager.renameSession(targetIndex, "手动标题"))
        assertFalse(
            manager.renameSessionIfRevisionMatches(target.id, revisionBeforeManualRename, "迟到的自动标题")
        )
        assertEquals("手动标题", target.name)
    }

    @Test
    fun switchingWorkspaceCreatesAnIsolatedSessionAndPreservesTheOriginal() = runBlocking {
        val firstWorkspace = testWorkspace()
        val secondWorkspace = testWorkspace()
        val sessionsRoot = Files.createTempDirectory("kagent-sessions-test")
        val manager = SessionManager(denyAll, sessionsRoot)
        manager.loadOrCreate(firstWorkspace)
        val original = manager.activeSession()
        val originalMessage = AgentMessage.User("first workspace context")
        SessionWriter(original.sessionFile).append(originalMessage)
        original.conversationHistory = listOf(originalMessage)
        original.messages.add(DisplayMessage("user", "first workspace context"))
        original.usedTokens = 123
        val originalJob = Job()
        original.currentJob = originalJob
        original.isBusy = true
        assertEquals(sessionsRoot, original.sessionFile.parent)

        val switched = manager.startSessionInWorkspace(original, secondWorkspace)

        assertEquals(2, manager.sessions.size)
        assertSame(switched, manager.activeSession())
        assertEquals(firstWorkspace, original.workspace)
        assertEquals(listOf(originalMessage), original.conversationHistory)
        assertEquals(123, original.usedTokens)
        assertTrue(originalJob.isActive)
        assertTrue(original.isBusy)
        assertEquals(secondWorkspace, switched.workspace)
        assertTrue(switched.conversationHistory.isEmpty())
        assertTrue(switched.messages.isEmpty())
        assertEquals(0, switched.usedTokens)
        assertEquals(sessionsRoot, switched.sessionFile.parent)
        assertFalse(original.sessionFile == switched.sessionFile)

        manager.addNewSession()
        val inherited = manager.activeSession()
        assertEquals(secondWorkspace, inherited.workspace)
        assertEquals(sessionsRoot, inherited.sessionFile.parent)

        val reloaded = SessionManager(denyAll, sessionsRoot)
        reloaded.loadOrCreate(firstWorkspace)
        assertEquals(firstWorkspace, reloaded.sessions.single { it.id == original.id }.workspace)
        assertEquals(listOf(originalMessage), reloaded.sessions.single { it.id == original.id }.conversationHistory)
        assertEquals(secondWorkspace, reloaded.sessions.single { it.id == switched.id }.workspace)
        assertEquals(secondWorkspace, reloaded.sessions.single { it.id == inherited.id }.workspace)
        originalJob.cancel()
    }

    @Test
    fun selectingTheCurrentWorkspaceKeepsTheExistingSession() = runBlocking {
        val workspace = testWorkspace()
        val repository = InMemorySessionRepository(
            listOf(
                StoredSession(
                    id = "first",
                    name = "First",
                    workspace = workspace,
                    sessionFile = workspace.resolve("first.jsonl"),
                ),
            ),
        )
        val manager = SessionManager(
            approvalPolicy = denyAll,
            sessionsRoot = workspace,
            repository = repository,
        )
        manager.loadOrCreate(workspace)
        val original = manager.activeSession()

        val selected = manager.startSessionInWorkspace(original, workspace)

        assertSame(original, selected)
        assertEquals(1, manager.sessions.size)
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun forwardedLaunchAlwaysCreatesANewSessionForTheCurrentWorkspace() = runBlocking {
        val workspace = testWorkspace()
        val repository = InMemorySessionRepository(
            listOf(
                StoredSession(
                    id = "first",
                    name = "First",
                    workspace = workspace,
                    sessionFile = workspace.resolve("first.jsonl"),
                    history = listOf(AgentMessage.User("keep me")),
                    usedTokens = 42,
                ),
            ),
        )
        val manager = SessionManager(
            approvalPolicy = denyAll,
            sessionsRoot = workspace,
            repository = repository,
        )
        manager.loadOrCreate(workspace)
        val original = manager.activeSession()

        val created = manager.startNewSessionInWorkspace(workspace)

        assertEquals(2, manager.sessions.size)
        assertSame(created, manager.activeSession())
        assertEquals(workspace, created.workspace)
        assertTrue(created.conversationHistory.isEmpty())
        assertTrue(created.messages.isEmpty())
        assertEquals(listOf(AgentMessage.User("keep me")), original.conversationHistory)
        assertEquals(42, original.usedTokens)
        assertEquals(1, repository.createCalls)
    }

    @Test
    fun failedWorkspaceSessionCreationLeavesTheOriginalSessionUntouched() = runBlocking {
        val firstWorkspace = testWorkspace()
        val secondWorkspace = testWorkspace()
        val stored = StoredSession(
            id = "first",
            name = "First",
            workspace = firstWorkspace,
            sessionFile = firstWorkspace.resolve("first.jsonl"),
        )
        val repository = InMemorySessionRepository(
            initial = listOf(stored),
            createFailure = IllegalStateException("create failed"),
        )
        val manager = SessionManager(
            approvalPolicy = denyAll,
            sessionsRoot = firstWorkspace,
            repository = repository,
        )
        manager.loadOrCreate(firstWorkspace)
        val original = manager.activeSession()
        original.conversationHistory = listOf(AgentMessage.User("keep me"))
        original.usedTokens = 42

        assertFailsWith<IllegalStateException> {
            manager.startSessionInWorkspace(original, secondWorkspace)
        }

        assertEquals(1, manager.sessions.size)
        assertSame(original, manager.activeSession())
        assertEquals(firstWorkspace, original.workspace)
        assertEquals(listOf(AgentMessage.User("keep me")), original.conversationHistory)
        assertEquals(42, original.usedTokens)
    }

    @Test
    fun repeatedInitializationDoesNotReloadOrReplaceSessionState() = runBlocking {
        val workspace = testWorkspace()
        val first = StoredSession(
            id = "first",
            name = "First",
            workspace = workspace,
            sessionFile = workspace.resolve("first.jsonl"),
        )
        val second = StoredSession(
            id = "second",
            name = "Second",
            workspace = workspace,
            sessionFile = workspace.resolve("second.jsonl"),
        )
        val repository = InMemorySessionRepository(listOf(first, second))
        val manager = SessionManager(
            approvalPolicy = denyAll,
            sessionsRoot = workspace,
            repository = repository,
        )

        manager.loadOrCreate(workspace)
        manager.switchTo(1)
        val sessionObjects = manager.sessions.toList()
        manager.loadOrCreate(workspace)
        manager.invalidateRuntimes()

        assertEquals(1, repository.loadCalls)
        assertEquals(1, manager.activeSessionIndex)
        assertTrue(manager.sessions.zip(sessionObjects).all { (actual, expected) -> actual === expected })
    }

    @Test
    fun deletingSessionAlsoDeletesItsTodoSidecar() = runBlocking {
        val workspace = testWorkspace()
        val sessionsRoot = Files.createTempDirectory("kagent-session-todo-delete")
        val manager = SessionManager(denyAll, sessionsRoot)
        manager.loadOrCreate(workspace)
        val original = manager.activeSession()
        val todoPath = TodoFiles.forSession(original.sessionFile)
        TodoStore(todoPath).applyOperations(
            listOf(
                TodoOperation(
                    type = TodoOperation.Type.CREATE,
                    id = "task",
                    content = "Task",
                ),
            ),
        )
        manager.addNewSession()

        assertTrue(Files.exists(todoPath))
        assertTrue(manager.deleteSession(manager.sessions.indexOf(original)))
        assertFalse(Files.exists(todoPath))
    }

    private fun testWorkspace(): Path {
        val path = Path.of("build", "test-workspaces", UUID.randomUUID().toString())
        Files.createDirectories(path)
        return path.toAbsolutePath().normalize()
    }

    private class InMemorySessionRepository(
        private val initial: List<StoredSession>,
        private val createFailure: Exception? = null,
    ) : SessionRepository {
        var loadCalls = 0
        var createCalls = 0

        override suspend fun loadAll(defaultWorkspace: Path): List<StoredSession> {
            loadCalls++
            return initial
        }

        override suspend fun create(
            workspace: Path,
            name: String,
            modelSelection: ModelSelection?,
        ): StoredSession {
            createFailure?.let { throw it }
            createCalls++
            return StoredSession(
                "created-$createCalls",
                name,
                workspace,
                workspace.resolve("created-$createCalls.jsonl"),
                modelSelection = modelSelection,
            )
        }

        override suspend fun updateName(sessionFile: Path, name: String) = Unit

        override suspend fun updateModel(sessionFile: Path, selection: ModelSelection) = Unit

        override suspend fun delete(sessionFile: Path) = Unit
    }
}
