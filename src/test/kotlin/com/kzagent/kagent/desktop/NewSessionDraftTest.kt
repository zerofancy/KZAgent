package com.kzagent.kagent.desktop

import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class NewSessionDraftTest {
    @Test
    fun selectingProjectPreservesInputAndClearsPendingDirectory() {
        val draft = NewSessionDraft(Path.of("initial"))
        val project = Project("selected", "自定义名称", Path.of("target").toAbsolutePath(), Instant.EPOCH)
        draft.input.edit { append("保留任务") }
        assertEquals(draft.initialWorkspace, draft.pendingWorkspace)
        draft.selectProject(project)
        assertEquals(project.id, draft.projectId)
        assertEquals(project.workspace, draft.workspace)
        assertNull(draft.pendingWorkspace)
        assertEquals("保留任务", draft.input.text.toString())
    }

    @Test
    fun workspaceResolutionRequiresOneMatchingProjectAndNeverPicksFirstOfDuplicates() {
        val path = Path.of("project").toAbsolutePath().normalize()
        val first = Project("first", "同名", path, Instant.EPOCH)
        val second = first.copy(id = "second")
        val draft = NewSessionDraft(Path.of("initial"))
        draft.resolveWorkspace(path.resolve("child").resolve(".."), listOf(first))
        assertEquals(first.id, draft.projectId)
        assertNull(draft.pendingWorkspace)
        draft.resolveWorkspace(path, listOf(first, second))
        assertNull(draft.projectId)
        assertEquals(path, draft.pendingWorkspace)
        assertEquals(path, draft.workspace)
        draft.resolveWorkspace(Path.of("no-project"), listOf(first))
        assertNull(draft.projectId)
        assertEquals(Path.of("no-project").toAbsolutePath().normalize(), draft.pendingWorkspace)
    }

    @Test
    fun missingDeletedAndMismatchedProjectsAreNotValidSendTargets() {
        val project = Project("selected", "项目", Path.of("target").toAbsolutePath(), Instant.EPOCH)
        val draft = NewSessionDraft(project.workspace)
        assertNull(selectedDraftProject(draft, listOf(project)))
        draft.selectProject(project)
        assertEquals(project, selectedDraftProject(draft, listOf(project)))
        assertNull(selectedDraftProject(draft, emptyList()))
        draft.workspace = Path.of("different")
        assertNull(selectedDraftProject(draft, listOf(project)))
        draft.selectProject(project)
        draft.pendingWorkspace = project.workspace
        assertNull(selectedDraftProject(draft, listOf(project)))
    }

    @Test
    fun blankInputAndRepeatedSubmissionAreRejectedWithoutLosingTheDraft() {
        val draft = NewSessionDraft(Path.of("project"))
        draft.input.edit { append("  \n ") }
        assertFalse(draft.beginSubmission())
        draft.input.edit { replace(0, length, "修复问题") }
        assertTrue(draft.beginSubmission())
        assertFalse(draft.beginSubmission())
        assertEquals("修复问题", draft.input.text.toString())
        draft.submitting = false
        assertTrue(draft.beginSubmission())
    }
}
