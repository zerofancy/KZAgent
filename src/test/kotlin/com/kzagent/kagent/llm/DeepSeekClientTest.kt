package com.kzagent.kagent.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepSeekClientTest {
    @Test
    fun scopedInstructionIsSentAsSystemMessageWithSourceAndScope() {
        val message = AgentMessage.ScopedInstruction(
            sourcePath = "src/AGENTS.md",
            scopePath = "src",
            content = "source guidance",
        )

        val json = message.toDeepSeekJson()
        val content = json["content"].toString()

        assertEquals("\"system\"", json["role"].toString())
        assertTrue("src/AGENTS.md" in content)
        assertTrue("Applies to: src" in content)
        assertTrue("source guidance" in content)
    }
}
