package com.kzagent.kagent.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectExpandStateTest {
    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("project-expand-test")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun missingStateIsEmptyAndExplicitStatesRoundTripIndependentlyById() = temporary { root -> runBlocking {
        val path = root.resolve("session-project-collapses.json")
        assertEquals(emptyMap(), loadProjectExpandState(path))
        assertFalse(Files.exists(path))
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        saveProjectExpandState(mapOf(first to false, second to true), path)
        assertEquals(mapOf(first to false, second to true), loadProjectExpandState(path))
        saveProjectExpandState(mapOf(first to true), path)
        assertEquals(mapOf(first to true), loadProjectExpandState(path))
        saveProjectExpandState(emptyMap(), path)
        assertEquals(emptyMap(), loadProjectExpandState(path))
    } }

    @Test
    fun schemaDamageAndInvalidIdsPreventReadAndOverwrite() = temporary { root -> runBlocking {
        val path = root.resolve("session-project-collapses.json")
        listOf(
            "broken", "{}",
            "{\"schemaVersion\":2,\"expandedGroups\":{}}",
            "{\"schemaVersion\":0,\"expandedGroups\":{}}",
            "{\"schemaVersion\":1,\"expandedGroups\":{\"not-a-uuid\":true}}",
            "{\"schemaVersion\":1,\"expandedGroups\":{\"1-1-1-1-1\":false}}",
            "{\"schemaVersion\":1,\"expandedGroups\":null}",
        ).forEach { content ->
            Files.writeString(path, content)
            assertFailsWith<Exception> { loadProjectExpandState(path) }
            assertFailsWith<Exception> { saveProjectExpandState(emptyMap(), path) }
            assertEquals(content, Files.readString(path))
        }
    } }

    @Test
    fun invalidInputCannotChangeValidStateAndOldPathStateIsNeverTouched() = temporary { root -> runBlocking {
        val oldPath = root.resolve("session-group-collapses.json")
        Files.writeString(oldPath, "old path state")
        val path = root.resolve("session-project-collapses.json")
        val states = mapOf(UUID.randomUUID().toString() to false)
        saveProjectExpandState(states, path)
        val before = Files.readString(path)
        assertFailsWith<IllegalArgumentException> { saveProjectExpandState(mapOf("invalid" to true), path) }
        assertEquals(before, Files.readString(path))
        assertEquals("old path state", Files.readString(oldPath))
    } }

    @Test
    fun interruptedReplacementRecoversBackupButNeverOverwritesDamagedTarget() = temporary { root -> runBlocking {
        val path = root.resolve("session-project-collapses.json")
        val backup = metadataBackupFile(path)
        val expected = mapOf(UUID.randomUUID().toString() to false)
        saveProjectExpandState(expected, path)
        Files.move(path, backup)
        assertEquals(expected, loadProjectExpandState(path))
        assertFalse(Files.exists(backup))
        Files.copy(path, backup)
        Files.writeString(path, "broken")
        assertFailsWith<Exception> { loadProjectExpandState(path) }
        assertEquals("broken", Files.readString(path))
        assertTrue(Files.exists(backup))
    } }

    @Test
    fun writeFailuresAndCancellationArePropagated() = temporary { root -> runBlocking {
        val blocker = root.resolve("file")
        Files.writeString(blocker, "keep")
        assertFailsWith<Exception> { saveProjectExpandState(emptyMap(), blocker.resolve("state.json")) }
        assertEquals("keep", Files.readString(blocker))
        val path = root.resolve("state.json")
        val cancelled = Job().also { it.cancel() }
        assertFailsWith<CancellationException> { withContext(cancelled) { loadProjectExpandState(path) } }
        assertFailsWith<CancellationException> { withContext(cancelled) { saveProjectExpandState(emptyMap(), path) } }
        assertFalse(Files.exists(path))
    } }
}
