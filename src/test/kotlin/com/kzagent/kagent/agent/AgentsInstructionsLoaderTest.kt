package com.kzagent.kagent.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsInstructionsLoaderTest {
    @Test
    fun loadsRootAndSkipsMissingOrEmptyFiles() {
        val workspace = Files.createTempDirectory("kagent-agents-root-test")
        val loader = AgentsInstructionsLoader(workspace)

        assertNull(loader.loadRoot())

        Files.writeString(workspace.resolve("AGENTS.md"), "  \r\n")
        assertNull(loader.loadRoot())

        Files.writeString(workspace.resolve("AGENTS.md"), "root guidance")
        val loaded = loader.loadRoot()

        assertEquals("AGENTS.md", loaded?.sourcePath)
        assertEquals(".", loaded?.scopePath)
        assertEquals("root guidance", loaded?.content)
    }

    @Test
    fun discoversNestedInstructionsFromShallowToDeepAndExcludesRoot() {
        val workspace = Files.createTempDirectory("kagent-agents-nested-test")
        val source = workspace.resolve("src").also(Files::createDirectories)
        val feature = source.resolve("feature").also(Files::createDirectories)
        Files.writeString(workspace.resolve("AGENTS.md"), "root")
        Files.writeString(source.resolve("AGENTS.md"), "source")
        Files.writeString(feature.resolve("AGENTS.md"), "feature")
        val target = feature.resolve("Target.kt")
        Files.writeString(target, "class Target")
        val loader = AgentsInstructionsLoader(workspace)

        val loaded = loader.loadForRead(target)

        assertEquals(listOf("source", "feature"), loaded.map { it.content })
        assertTrue(loaded.none { it.scopePath == "." })

        val withoutSource = loader.loadForRead(target, setOf(loaded.first().source))
        assertEquals(listOf("feature"), withoutSource.map { it.content })
    }
}
