package com.kzagent.kagent.desktop

import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ApprovalDecision
import com.kzagent.kagent.tools.ApprovalResult
import com.kzagent.kagent.tools.ApprovalSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionManagerTest {
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
    fun workspacesAreIndependentAndNewSessionInheritsActiveWorkspace() = runBlocking {
        val firstWorkspace = testWorkspace()
        val secondWorkspace = testWorkspace()
        val sessionsRoot = Files.createTempDirectory("kagent-sessions-test")
        val manager = SessionManager(denyAll, sessionsRoot)
        manager.loadOrCreate(firstWorkspace)
        val original = manager.activeSession()
        assertEquals(sessionsRoot, original.sessionFile.parent)

        manager.addNewSession()
        val changed = manager.activeSession()
        val sessionIdsBeforeWorkspaceChange = manager.sessions.map { it.id }
        manager.changeWorkspace(changed, secondWorkspace)

        assertEquals(sessionIdsBeforeWorkspaceChange, manager.sessions.map { it.id })
        assertEquals(firstWorkspace, original.workspace)
        assertEquals(secondWorkspace, changed.workspace)
        assertEquals(sessionsRoot, changed.sessionFile.parent)

        manager.addNewSession()
        val inherited = manager.activeSession()
        assertEquals(secondWorkspace, inherited.workspace)
        assertEquals(sessionsRoot, inherited.sessionFile.parent)

        val reloaded = SessionManager(denyAll, sessionsRoot)
        reloaded.loadOrCreate(firstWorkspace)
        assertEquals(firstWorkspace, reloaded.sessions.single { it.id == original.id }.workspace)
        assertEquals(secondWorkspace, reloaded.sessions.single { it.id == changed.id }.workspace)
        assertEquals(secondWorkspace, reloaded.sessions.single { it.id == inherited.id }.workspace)
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

    private fun testWorkspace(): Path {
        val path = Path.of("build", "test-workspaces", UUID.randomUUID().toString())
        Files.createDirectories(path)
        return path.toAbsolutePath().normalize()
    }

    private class InMemorySessionRepository(
        private val initial: List<StoredSession>,
    ) : SessionRepository {
        var loadCalls = 0

        override suspend fun loadAll(defaultWorkspace: Path): List<StoredSession> {
            loadCalls++
            return initial
        }

        override suspend fun create(workspace: Path, name: String): StoredSession =
            StoredSession("created", name, workspace, workspace.resolve("created.jsonl"))

        override suspend fun updateName(sessionFile: Path, name: String) = Unit

        override suspend fun updateWorkspace(sessionFile: Path, workspace: Path) = Unit

        override suspend fun delete(sessionFile: Path) = Unit
    }
}
