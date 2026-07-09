package com.kzagent.kagent.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}

