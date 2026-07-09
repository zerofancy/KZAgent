package com.kzagent.kagent.tools

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalToolsTest {
    @Test
    fun readSearchAndListToolsWorkWithinLimits() = runBlocking {
        val dir = Files.createTempDirectory("kagent-tools-test")
        Files.writeString(dir.resolve("sample.txt"), "alpha\nbeta\nGamma\n")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val list = registry.get("list_files")!!.handler(buildJsonObject {
            put("path", ".")
            put("max_depth", 2)
        })
        assertContains(list.content, "sample.txt")

        val read = registry.get("read_file")!!.handler(buildJsonObject {
            put("path", "sample.txt")
            put("start_line", 2)
            put("max_lines", 1)
        })
        assertContains(read.content, "2: beta")

        val search = registry.get("search_text")!!.handler(buildJsonObject {
            put("query", "gamma")
            put("path", ".")
            put("max_results", 10)
        })
        assertContains(search.content, "sample.txt:3")
    }

    @Test
    fun replaceInFileRequiresUniqueMatch() = runBlocking {
        val dir = Files.createTempDirectory("kagent-replace-test")
        val file = dir.resolve("sample.txt")
        Files.writeString(file, "one two three")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val result = registry.get("replace_in_file")!!.handler(buildJsonObject {
            put("path", "sample.txt")
            put("old_text", "two")
            put("new_text", "TWO")
        })

        assertFalse(result.isError)
        assertContains(Files.readString(file), "one TWO three")
    }

    @Test
    fun replaceInFileRejectsAmbiguousMatch() = runBlocking {
        val dir = Files.createTempDirectory("kagent-replace-ambiguous-test")
        Files.writeString(dir.resolve("sample.txt"), "x x")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val result = registry.get("replace_in_file")!!.handler(buildJsonObject {
            put("path", "sample.txt")
            put("old_text", "x")
            put("new_text", "y")
        })

        assertTrue(result.isError)
        assertContains(result.content, "matched 2 times")
    }

    @Test
    fun runCommandBlocksDangerousCommands() = runBlocking {
        val dir = Files.createTempDirectory("kagent-command-test")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val result = registry.get("run_command")!!.handler(buildJsonObject {
            put("command", "rm -rf .")
        })

        assertTrue(result.isError)
        assertContains(result.content, "blocked dangerous command")
    }

    @Test
    fun toolsDoNotExposeLocalProperties() = runBlocking {
        val dir = Files.createTempDirectory("kagent-sensitive-test")
        Files.writeString(dir.resolve("local.properties"), "deepseek.api.key=sk-secret")
        Files.writeString(dir.resolve("safe.txt"), "hello")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val list = registry.get("list_files")!!.handler(buildJsonObject {
            put("path", ".")
            put("max_depth", 1)
        })
        assertFalse(list.content.contains("local.properties"))

        val read = registry.get("read_file")!!.handler(buildJsonObject {
            put("path", "local.properties")
        })
        assertTrue(read.isError)
        assertFalse(read.content.contains("sk-secret"))

        val command = registry.get("run_command")!!.handler(buildJsonObject {
            put("command", "cat local.properties")
        })
        assertTrue(command.isError)
        assertFalse(command.content.contains("sk-secret"))
    }

    @Test
    fun runCommandAllowsApprovedSafeCommand() = runBlocking {
        val dir = Files.createTempDirectory("kagent-command-safe-test")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val result = registry.get("run_command")!!.handler(buildJsonObject {
            put("command", "printf ok")
            put("timeout_seconds", 5)
        })

        assertFalse(result.isError)
        assertContains(result.content, "Exit code: 0")
        assertContains(result.content, "ok")
    }
}
