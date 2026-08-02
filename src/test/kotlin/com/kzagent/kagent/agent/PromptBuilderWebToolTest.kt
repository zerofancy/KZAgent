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

    @Test
    fun describesWhenAndHowToUseSessionTodoTools() {
        val prompt = PromptBuilder(Files.createTempDirectory("kagent-todo-prompt-test")).build()

        assertContains(prompt, "hierarchical Todo plan")
        assertContains(prompt, "todo_read")
        assertContains(prompt, "todo_write")
        assertContains(prompt, "simple one-step request")
        assertContains(prompt, "0 credits")
        assertContains(prompt, "Do not wait until the final response")
        assertContains(prompt, "changed=false")
        assertContains(prompt, "Pending already includes work in progress")
    }
}
