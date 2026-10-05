package com.kzagent.kagent.desktop

import com.kzagent.kagent.agent.SessionWriter
import com.kzagent.kagent.config.ModelSelection
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
    fun reloadAfterFailureKeepsCompletedToolsWithoutDuplicatingTheUserTurn() = runBlocking {
        val root = Files.createTempDirectory("kagent-recovery-test")
        SessionManager(denyAll, root).use { manager ->
            manager.loadSessions(testWorkspace())
            manager.startNewSessionInWorkspace(testWorkspace())
            val session = manager.activeSession()
            val writer = SessionWriter(session.sessionFile)
            val user = AgentMessage.User("review this project")
            val assistant = AgentMessage.Assistant(null, listOf(
                com.kzagent.kagent.llm.ModelToolCall("call-1", "read_file", "{}"),
            ))
            val result = AgentMessage.Tool("call-1", "read_file", "saved result", false)
            writer.append(AgentMessage.System("system"))
            writer.append(user)
            writer.append(assistant, tokens = 123)
            writer.append(result)
            // A model failure prevented runConversation from returning its updated history.
            assertTrue(session.conversationHistory.isEmpty())
            session.reloadSavedHistory()
            assertEquals(listOf(user, assistant, result), session.conversationHistory)
            assertEquals(123, session.usedTokens)
            session.reloadSavedHistory()
            assertEquals(3, session.conversationHistory.size)
        }
    }

    @Test
    fun modelSelectionSurvivesReloadInItsSidecar() = runBlocking {
        val workspace = testWorkspace()
        val sessionsRoot = Files.createTempDirectory("kagent-model-session-test")
        val openRouter = ModelSelection("openrouter", "vendor/agent", 128_000, false)
        val manager = SessionManager(denyAll, sessionsRoot, initialDefaultModel = openRouter)
        manager.loadSessions(workspace)
        manager.startNewSessionInWorkspace(workspace)

        val session = manager.activeSession()
        assertEquals(openRouter, session.modelSelection)
        assertTrue(Files.isRegularFile(session.sessionFile.resolveSibling("${session.sessionFile.fileName}.model")))

        val replacement = openRouter.copy(modelId = "vendor/agent-2", contextWindowSize = 256_000)
        manager.updateModel(session, replacement)
        val reloaded = SessionManager(denyAll, sessionsRoot)
        reloaded.loadSessions(workspace)

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
        manager.loadSessions(workspace)
        manager.startNewSessionInWorkspace(workspace)

        val session = manager.activeSession()
        assertTrue(Files.isRegularFile(session.sessionFile))
        assertTrue(manager.renameSession(0, "持久化名称"))

        val reloaded = SessionManager(denyAll, sessionsRoot)
        reloaded.loadSessions(workspace)
        assertEquals("持久化名称", reloaded.activeSession().name)
    }

    @Test
    fun mostRecentlyModifiedSessionLoadsFirst() = runBlocking {
        val workspace = testWorkspace()
        val sessionsRoot = Files.createTempDirectory("kagent-sessions-test")
        val manager = SessionManager(denyAll, sessionsRoot)
        manager.loadSessions(workspace)
        manager.startNewSessionInWorkspace(workspace)
        val older = manager.activeSession()
        manager.addNewSession()
        val newer = manager.activeSession()

        Files.setLastModifiedTime(older.sessionFile, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(newer.sessionFile, FileTime.fromMillis(2_000))

        val reloaded = SessionManager(denyAll, sessionsRoot)
        reloaded.loadSessions(workspace)
        assertEquals(newer.id, reloaded.activeSession().id)
    }

    @Test
    fun startupLoadsHistoryWithoutCreatingSession() = runBlocking {
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

        manager.loadSessions(startupWorkspace)

        assertEquals(1, manager.sessions.size)
        assertEquals(0, repository.createCalls)
        val preserved = manager.sessions.single { it.id == "existing" }
        assertEquals(previousWorkspace, preserved.workspace)
        assertEquals(listOf(AgentMessage.User("preserve me")), preserved.conversationHistory)
        assertEquals(21, preserved.usedTokens)
    }

    @Test
    fun conditionalRenameUsesStableIdAndPreservesNewerManualName() = runBlocking {
        val workspace = testWorkspace()
        val manager = SessionManager(denyAll, Files.createTempDirectory("kagent-sessions-test"))
        manager.loadSessions(workspace)
        manager.startNewSessionInWorkspace(workspace)
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
        manager.loadSessions(firstWorkspace)
        manager.startNewSessionInWorkspace(firstWorkspace)
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
        reloaded.loadSessions(firstWorkspace)
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
        manager.loadSessions(workspace)
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
        manager.loadSessions(workspace)
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
        manager.loadSessions(firstWorkspace)
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

        manager.loadSessions(workspace)
        manager.switchTo(1)
        val sessionObjects = manager.sessions.toList()
        manager.loadSessions(workspace)
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
        manager.loadSessions(workspace)
        manager.startNewSessionInWorkspace(workspace)
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

    @Test
    fun emptyHistoryDoesNotCreateFilesAndLastSessionCanBeDeleted() = runBlocking {
        val root = Files.createTempDirectory("kagent-empty-session-test")
        val workspace = testWorkspace()
        SessionManager(denyAll, root).use { manager ->
            manager.loadSessions(workspace)
            assertTrue(manager.initialized)
            assertTrue(manager.sessions.isEmpty())
            assertEquals(-1, manager.activeSessionIndex)
            assertEquals(null, manager.activeSessionOrNull())
            Files.list(root).use { assertEquals(0L, it.count()) }
            val selected = ModelSelection("openrouter", "explicit-model", 123_456, false)
            val session = manager.startNewSessionInWorkspace(workspace, selected)
            assertEquals(selected, session.modelSelection)
            assertTrue(manager.deleteSession(0))
            assertTrue(manager.sessions.isEmpty())
            assertEquals(null, manager.activeSessionOrNull())
            Files.list(root).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun firstCreationFailureLeavesAnInitializedEmptyManager() = runBlocking {
        val workspace = testWorkspace()
        val repository = InMemorySessionRepository(emptyList(), IllegalStateException("create failed"))
        SessionManager(denyAll, workspace, repository).use { manager ->
            manager.loadSessions(workspace)
            assertFailsWith<IllegalStateException> { manager.startNewSessionInWorkspace(workspace) }
            assertTrue(manager.initialized)
            assertTrue(manager.sessions.isEmpty())
            assertEquals(null, manager.activeSessionOrNull())
        }
    }

    @Test
    fun cancellationWhileReturningCreatedFilesRollsBackTheUnpublishedSession() = runBlocking {
        val root = Files.createTempDirectory("kagent-cancel-create")
        val submission = Job()
        var writtenBeforeCancellation = 0L
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                val count = Files.list(root).use { it.count() }
                if (count > 0 && !submission.isCancelled) {
                    writtenBeforeCancellation = count
                    submission.cancel()
                }
                block.run()
            }
        }
        val repository = FileSessionRepository(root)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            kotlinx.coroutines.withContext(submission + dispatcher) {
                repository.create(root, "draft", ModelSelection("deepseek", "selected"))
            }
        }
        assertTrue(writtenBeforeCancellation > 0)
        Files.list(root).use { assertEquals(0L, it.count()) }
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
