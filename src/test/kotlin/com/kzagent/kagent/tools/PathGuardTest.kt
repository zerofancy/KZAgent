package com.kzagent.kagent.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PathGuardTest {
    @Test
    fun allowsWorkspacePaths() {
        val dir = Files.createTempDirectory("kagent-path-test")
        Files.writeString(dir.resolve("a.txt"), "hello")
        val guard = PathGuard(dir)

        assertEquals("a.txt", guard.display(guard.resolveExisting("a.txt")))
    }

    @Test
    fun rejectsPathEscape() {
        val dir = Files.createTempDirectory("kagent-path-test")
        val guard = PathGuard(dir)

        assertFailsWith<IllegalArgumentException> {
            guard.resolveExisting("../outside.txt")
        }
    }

    @Test
    fun resolvesExternalReadPathWithoutWeakeningRegularResolvers() {
        val dir = Files.createTempDirectory("kagent-path-test")
        val external = Files.createTempFile("kagent-outside-test", ".txt")
        val guard = PathGuard(dir)

        val resolved = guard.resolveReadableExistingFile(external.toString())

        assertEquals(external.toRealPath(), resolved.path)
        assertFalse(resolved.insideWorkspace)
        assertFailsWith<IllegalArgumentException> { guard.resolveExisting(external.toString()) }
    }

    @Test
    fun identifiesWorkspaceReadPath() {
        val dir = Files.createTempDirectory("kagent-path-test")
        val file = dir.resolve("inside.txt")
        Files.writeString(file, "hello")

        val resolved = PathGuard(dir).resolveReadableExistingFile(file.toString())

        assertTrue(resolved.insideWorkspace)
        assertEquals(file.toRealPath(), resolved.path)
    }

    @Test
    fun resolvesParentRelativeExternalReadPath() {
        val parent = Files.createTempDirectory("kagent-parent-read-test")
        val workspace = parent.resolve("workspace").also(Files::createDirectories)
        val external = parent.resolve("outside.txt")
        Files.writeString(external, "outside")

        val resolved = PathGuard(workspace).resolveReadableExistingFile("../outside.txt")

        assertFalse(resolved.insideWorkspace)
        assertEquals(external.toRealPath(), resolved.path)
    }
}

