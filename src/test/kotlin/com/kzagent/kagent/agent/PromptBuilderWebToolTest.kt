package com.kzagent.kagent.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains

class PromptBuilderWebToolTest {
    @Test
    fun describesStaticWebToolAndItsCurrentLimitations() {
        val prompt = PromptBuilder(Files.createTempDirectory("kagent-web-prompt-test")).build()

        assertContains(prompt, "fetch_web_page")
        assertContains(prompt, "isolated extraction subagent")
        assertContains(prompt, "cannot execute JavaScript")
        assertContains(prompt, "private networks")
        assertContains(prompt, "untrusted source data")
    }
}
