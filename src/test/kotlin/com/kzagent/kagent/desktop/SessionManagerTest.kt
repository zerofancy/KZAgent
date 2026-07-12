package com.kzagent.kagent.desktop

import com.kzagent.kagent.tools.ApprovalPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionManagerTest {
    private val denyAll = ApprovalPolicy { _, _ -> false }

    @Test
    fun emptySessionAndRenamedTitleSurviveReload() {
        val workspace = testWorkspace()
        val manager = SessionManager(denyAll)
        manager.loadOrCreate(workspace)

        val session = manager.activeSession()
        assertTrue(Files.isRegularFile(session.sessionFile))
        assertTrue(manager.renameSession(0, "持久化名称"))

        val reloaded = SessionManager(denyAll)
        reloaded.loadOrCreate(workspace)
        assertEquals("持久化名称", reloaded.activeSession().name)
    }

    @Test
    fun mostRecentlyModifiedSessionLoadsFirst() {
        val workspace = testWorkspace()
        val manager = SessionManager(denyAll)
        manager.loadOrCreate(workspace)
        val older = manager.activeSession()
        manager.addNewSession(workspace)
        val newer = manager.activeSession()

        Files.setLastModifiedTime(older.sessionFile, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(newer.sessionFile, FileTime.fromMillis(2_000))

        val reloaded = SessionManager(denyAll)
        reloaded.loadOrCreate(workspace)
        assertEquals(newer.id, reloaded.activeSession().id)
    }

    @Test
    fun conditionalRenameUsesStableIdAndPreservesNewerManualName() {
        val workspace = testWorkspace()
        val manager = SessionManager(denyAll)
        manager.loadOrCreate(workspace)
        val target = manager.activeSession()
        val initialRevision = target.titleRevision

        manager.addNewSession(workspace)
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

    private fun testWorkspace(): Path {
        val path = Path.of("build", "test-workspaces", UUID.randomUUID().toString())
        Files.createDirectories(path)
        return path.toAbsolutePath().normalize()
    }
}
