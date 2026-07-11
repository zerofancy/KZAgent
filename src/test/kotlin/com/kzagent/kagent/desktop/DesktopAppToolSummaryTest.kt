package com.kzagent.kagent.desktop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAppToolSummaryTest {
    @Test
    fun extractsArgumentFromLargeJsonWithoutRegexStackOverflow() {
        val patch = buildString {
            appendLine("diff --git a/doc/index.html b/doc/index.html")
            appendLine("--- /dev/null")
            appendLine("+++ b/doc/index.html")
            appendLine("@@ -0,0 +1,20000 @@")
            repeat(20_000) { appendLine("+<div>line $it with \\\"quotes\\\"</div>") }
        }
        val argsJson = buildJsonObject { put("patch", patch) }.toString()

        assertEquals(patch, extractArg(argsJson, "patch"))
        assertEquals(listOf("doc/index.html"), extractPatchedFiles(argsJson))
    }

    @Test
    fun malformedArgumentsHaveSafeFallback() {
        assertEquals(null, extractArg("{not-json", "patch"))
        assertEquals(listOf("(patch)"), extractPatchedFiles("{not-json"))
    }
}
