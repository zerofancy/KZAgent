package com.kzagent.kagent.desktop

import androidx.compose.runtime.mutableStateListOf
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionProjectUiHelperTest {
    private val workspace = Path.of("same-directory").toAbsolutePath()
    private fun project(id: String, createdAt: Long = 0) =
        Project(id, "同名项目", workspace, Instant.ofEpochMilli(createdAt))

    private fun session(id: String, projectId: String?) = SessionData(
        id = id, name = id, workspace = workspace, sessionFile = workspace.resolve("$id.jsonl"),
        messages = mutableStateListOf(), projectId = projectId,
    )

    @Test
    fun groupsByProjectIdIncludesEmptyProjectsAndKeepsOriginalSessionIndices() {
        val first = project("first")
        val second = project("second")
        val empty = project("empty", 300)
        val sessions = listOf(session("old", first.id), session("other", second.id), session("new", first.id))
        val updates = mapOf("old" to 100L, "other" to 200L, "new" to 400L)
        val groups = sessionProjectGroups(listOf(first, second, empty), sessions) { updates.getValue(it.id) }
        assertEquals(listOf(first.id, empty.id, second.id), groups.map { it.project.id })
        assertEquals(listOf("new", "old"), groups.first().sessions.map { it.session.id })
        assertEquals(listOf(2, 0), groups.first().sessions.map { it.index })
        assertTrue(groups[1].sessions.isEmpty())
        assertEquals(listOf("other"), groups[2].sessions.map { it.session.id })
    }

    @Test
    fun equalTimestampsUseStableIdsAndRenamingDoesNotChangeOrder() {
        val projects = listOf(project("b", 100), project("a", 100))
        val order = sessionProjectGroups(projects, emptyList()).map { it.project.id }
        assertEquals(listOf("a", "b"), order)
        assertEquals(order, sessionProjectGroups(projects.map { it.copy(name = "新名称") }, emptyList()).map { it.project.id })
    }

    @Test
    fun explicitInvalidReferencesAreNotRegroupedByMatchingWorkspace() {
        val group = sessionProjectGroups(listOf(project("valid")), listOf(session("orphan", "missing"), session("legacy", null))) { 10L }
        assertTrue(group.single().sessions.isEmpty())
    }

    @Test
    fun identicalProjectNamesAndPathsHaveIdSuffixesOnlyWhenNeeded() {
        val first = project("11111111-aaaa")
        val second = project("22222222-bbbb")
        val elsewhere = second.copy(workspace = Path.of("another-directory").toAbsolutePath())
        assertEquals(first.name, projectChoiceLabel(first, listOf(first, elsewhere)))
        assertEquals("同名项目 · 11111111", projectChoiceLabel(first, listOf(first, second)))
        assertEquals("同名项目 · 22222222", projectChoiceLabel(second, listOf(first, second)))
        val collidingPrefix = first.copy(id = "11111111-bbbb")
        assertEquals("同名项目 · 11111111-a", projectChoiceLabel(first, listOf(first, collidingPrefix)))
    }
}
