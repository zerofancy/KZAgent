package com.kzagent.kagent.tools

import java.nio.file.Files
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun applyPatchUpdatesFile() = runBlocking {
        val dir = Files.createTempDirectory("kagent-replace-test")
        val file = dir.resolve("sample.txt")
        Files.writeString(file, "one two three")
        val registry = LocalTools(PathGuard(dir), AlwaysDenyPolicy).registry()

        val result = registry.get("apply_patch")!!.handler(buildJsonObject {
            put("patch", """diff --git a/sample.txt b/sample.txt
--- a/sample.txt
+++ b/sample.txt
@@ -1 +1 @@
-one two three
+one TWO three
""")
        })

        assertFalse(result.isError)
        assertContains(Files.readString(file), "one TWO three")
    }

    @Test
    fun applyPatchCreatesNewFileWithoutApproval() = runBlocking {
        val dir = Files.createTempDirectory("kagent-create-test")
        val registry = LocalTools(PathGuard(dir), AlwaysDenyPolicy).registry()

        val result = registry.get("apply_patch")!!.handler(buildJsonObject {
            put("patch", """diff --git a/nested/new-file.txt b/nested/new-file.txt
--- /dev/null
+++ b/nested/new-file.txt
@@ -0,0 +1 @@
+created content
""")
        })

        assertFalse(result.isError)
        assertContains(result.content.replace('\\', '/'), "nested/new-file.txt")
        assertContains(Files.readString(dir.resolve("nested/new-file.txt")), "created content")
    }

    @Test
    fun applyPatchRejectsMismatchedContext() = runBlocking {
        val dir = Files.createTempDirectory("kagent-replace-ambiguous-test")
        Files.writeString(dir.resolve("sample.txt"), "x x")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val result = registry.get("apply_patch")!!.handler(buildJsonObject {
            put("patch", """diff --git a/sample.txt b/sample.txt
--- a/sample.txt
+++ b/sample.txt
@@ -1 +1 @@
-not x
+y
""")
        })

        assertTrue(result.isError)
        assertContains(result.content, "did not match")
        assertContains(result.content, "Patch failed for sample.txt")
        assertContains(result.content, "Hunk #1")
        assertContains(result.content, "Expected old content")
        assertContains(result.content, "Actual content at declared position")
        assertContains(result.content, "Re-read this target region")
    }

    @Test
    fun applyPatchRelocatesHunkUsingContextWhenLineNumberIsOff() = runBlocking {
        val dir = Files.createTempDirectory("kagent-patch-offset-test")
        val file = dir.resolve("sample.txt")
        Files.writeString(file, "header\nalpha\nbeta\ngamma\n")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val result = registry.get("apply_patch")!!.handler(buildJsonObject {
            put("patch", """diff --git a/sample.txt b/sample.txt
--- a/sample.txt
+++ b/sample.txt
@@ -2,2 +2,2 @@
 beta
-gamma
+GAMMA
""")
        })

        assertFalse(result.isError)
        assertContains(Files.readString(file), "beta\nGAMMA")
    }

    @Test
    fun applyPatchRejectsEquallyNearAmbiguousContext() = runBlocking {
        val dir = Files.createTempDirectory("kagent-patch-ambiguous-test")
        val file = dir.resolve("sample.txt")
        Files.writeString(file, "same\nmiddle\nsame\n")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val result = registry.get("apply_patch")!!.handler(buildJsonObject {
            put("patch", """diff --git a/sample.txt b/sample.txt
--- a/sample.txt
+++ b/sample.txt
@@ -2 +2 @@
-same
+changed
""")
        })

        assertTrue(result.isError)
        assertContains(result.content, "ambiguous")
    }

    @Test
    fun applyPatchPreservesUtf8BomAndCrLf() = runBlocking {
        val dir = Files.createTempDirectory("kagent-bom-test")
        val file = dir.resolve("sample.txt")
        Files.write(file, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "旧值\r\n下一行\r\n".toByteArray())
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()
        val result = registry.get("apply_patch")!!.handler(buildJsonObject {
            put("patch", """diff --git a/sample.txt b/sample.txt
--- a/sample.txt
+++ b/sample.txt
@@ -1,2 +1,2 @@
-旧值
+新值
 下一行
""")
        })
        val bytes = Files.readAllBytes(file)
        assertFalse(result.isError)
        assertTrue(bytes.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        assertContains(String(bytes.drop(3).toByteArray()), "新值\r\n下一行\r\n")
    }

    @Test
    fun applyPatchPreservesGbkEncoding() = runBlocking {
        val dir = Files.createTempDirectory("kagent-gbk-test")
        val file = dir.resolve("sample.txt")
        val gbk = Charset.forName("GB18030")
        Files.write(file, "旧值\r\n".toByteArray(gbk))
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()
        val result = registry.get("apply_patch")!!.handler(buildJsonObject {
            put("patch", """diff --git a/sample.txt b/sample.txt
--- a/sample.txt
+++ b/sample.txt
@@ -1 +1 @@
-旧值
+新值
""")
        })
        assertFalse(result.isError)
        assertTrue(Files.readAllBytes(file).contentEquals("新值\r\n".toByteArray(gbk)))
    }

    @Test
    fun dangerousCommandUsesApprovalInsteadOfBeingBlocked() = runBlocking {
        val dir = Files.createTempDirectory("kagent-command-test")
        var request: ApprovalRequest? = null
        val policy = ApprovalPolicy {
            request = it
            ApprovalResult(ApprovalDecision.ALLOW, ApprovalSource.HUMAN, "approved")
        }
        val registry = LocalTools(PathGuard(dir), policy).registry()

        val result = registry.get("run_command")!!.handler(buildJsonObject {
            put("command", "rm --version")
        })

        assertFalse(result.isError)
        assertTrue(assertIs<ApprovalRequest.CommandExecution>(request).risk.isHighRisk)
    }

    @Test
    fun toolsDoNotExposeLocalProperties() = runBlocking {
        val dir = Files.createTempDirectory("kagent-sensitive-test")
        Files.writeString(dir.resolve("local.properties"), "deepseek.api.key=sk-secret")
        Files.writeString(dir.resolve("safe.txt"), "hello")
        val registry = LocalTools(PathGuard(dir), AlwaysDenyPolicy, sensitivePathProtection = true).registry()

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
    fun approvedExternalFileReadDoesNotExposeInstructionDiscoveryPath() = runBlocking {
        val workspace = Files.createTempDirectory("kagent-external-read-workspace")
        val externalDir = Files.createTempDirectory("kagent-external-read-target")
        val external = externalDir.resolve("outside.txt")
        Files.writeString(external, "outside content")
        Files.writeString(externalDir.resolve("AGENTS.md"), "untrusted external instructions")
        var request: ApprovalRequest? = null
        val policy = ApprovalPolicy {
            request = it
            ApprovalResult(ApprovalDecision.ALLOW, ApprovalSource.HUMAN, "approved")
        }
        val registry = LocalTools(PathGuard(workspace), policy).registry()

        val result = registry.get("read_file")!!.handler(buildJsonObject {
            put("path", external.toString())
        })

        assertFalse(result.isError)
        assertContains(result.content, "outside content")
        assertTrue(result.readPaths.isEmpty())
        assertTrue(assertIs<ApprovalRequest.ExternalFileRead>(request).outsideWorkspace)
    }

    @Test
    fun deniedExternalFileReadReturnsError() = runBlocking {
        val workspace = Files.createTempDirectory("kagent-external-read-denied-workspace")
        val external = Files.createTempFile("kagent-external-read-denied", ".txt")
        Files.writeString(external, "outside content")
        val registry = LocalTools(PathGuard(workspace), AlwaysDenyPolicy).registry()

        val result = registry.get("read_file")!!.handler(buildJsonObject {
            put("path", external.toString())
        })

        assertTrue(result.isError)
        assertFalse(result.content.contains("outside content"))
    }

    @Test
    fun ordinaryWorkspaceReadDoesNotInvokeApproval() = runBlocking {
        val workspace = Files.createTempDirectory("kagent-workspace-read")
        Files.writeString(workspace.resolve("inside.txt"), "inside content")
        var approvalCalls = 0
        val registry = LocalTools(
            PathGuard(workspace),
            ApprovalPolicy {
                approvalCalls++
                ApprovalResult(ApprovalDecision.DENY, ApprovalSource.HUMAN, "unexpected")
            },
        ).registry()

        val result = registry.get("read_file")!!.handler(buildJsonObject {
            put("path", "inside.txt")
        })

        assertFalse(result.isError)
        assertEquals(0, approvalCalls)
        assertEquals(1, result.readPaths.size)
    }

    @Test
    fun invalidReadRangeFailsBeforeApproval() = runBlocking {
        val workspace = Files.createTempDirectory("kagent-invalid-read-range")
        Files.writeString(workspace.resolve("inside.txt"), "inside content")
        var approvalCalls = 0
        val registry = LocalTools(
            PathGuard(workspace),
            ApprovalPolicy {
                approvalCalls++
                ApprovalResult(ApprovalDecision.ALLOW, ApprovalSource.HUMAN, "unexpected")
            },
        ).registry()

        val result = registry.get("read_file")!!.handler(buildJsonObject {
            put("path", "inside.txt")
            put("start_line", 0)
        })

        assertTrue(result.isError)
        assertContains(result.content, "start_line")
        assertEquals(0, approvalCalls)
    }

    @Test
    fun runCommandAllowsApprovedSafeCommand() = runBlocking {
        val dir = Files.createTempDirectory("kagent-command-safe-test")
        val registry = LocalTools(PathGuard(dir), AlwaysApprovePolicy).registry()

        val result = registry.get("run_command")!!.handler(buildJsonObject {
            put("command", "echo ok")
            put("timeout_seconds", 5)
        })

        assertFalse(result.isError)
        assertContains(result.content, "Exit code: 0")
        assertContains(result.content, "ok")
    }

    @Test
    fun runCommandStillRequiresApproval() = runBlocking {
        val dir = Files.createTempDirectory("kagent-command-approval-test")
        val registry = LocalTools(PathGuard(dir), AlwaysDenyPolicy).registry()

        val result = registry.get("run_command")!!.handler(buildJsonObject {
            put("command", "printf ok")
            put("timeout_seconds", 5)
        })

        assertTrue(result.isError)
        assertContains(result.content, "User denied run_command")
    }
}
