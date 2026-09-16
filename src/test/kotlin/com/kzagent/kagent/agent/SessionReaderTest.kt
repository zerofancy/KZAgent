package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionReaderTest {
    @Test
    fun loadsLatestJsonlSessionWithoutSystemHistory() {
        val dir = Files.createTempDirectory("kagent-session-reader-test")
        val sessions = dir.resolve(".kagent").resolve("sessions")
        Files.createDirectories(sessions)
        Files.writeString(
            sessions.resolve("session-2026-01-01T000000Z.jsonl"),
            """
            {"role":"user","content":"old"}
            {"role":"assistant","content":"old answer","tool_calls":[]}
            """.trimIndent() + "\n",
        )
        Files.writeString(
            sessions.resolve("session-2026-01-02T000000Z.jsonl"),
            """
            {"role":"system","content":"system"}
            {"role":"user","content":"new"}
            {"role":"assistant","content":null,"tool_calls":[{"id":"call-1","name":"list_files","arguments":"{\"path\":\".\"}"}]}
            {"role":"tool","tool_call_id":"call-1","name":"list_files","content":"README.md","is_error":false}
            """.trimIndent() + "\n",
        )

        val history = SessionReader(sessions).loadLatestHistory()

        assertNotNull(history)
        assertEquals(3, history.size)
        assertIs<AgentMessage.User>(history[0])
        val assistant = assertIs<AgentMessage.Assistant>(history[1])
        assertEquals("list_files", assistant.toolCalls.single().name)
        assertIs<AgentMessage.Tool>(history[2])
    }

    @Test
    fun scopedInstructionRoundTripsThroughSessionJsonl() = runBlocking {
        val dir = Files.createTempDirectory("kagent-scoped-instruction-session-test")
        val sessionFile = dir.resolve("session.jsonl")
        val instruction = AgentMessage.ScopedInstruction(
            sourcePath = "src/AGENTS.md",
            scopePath = "src",
            content = "source guidance",
        )

        SessionWriter(sessionFile).append(instruction)
        val loaded = SessionReader(dir).loadFile(sessionFile)

        assertEquals(listOf(instruction), loaded)
    }

    @Test
    fun sanitizesBlankToolCallsAndOrphanedToolResults() {
        val dir = Files.createTempDirectory("kagent-session-sanitize-test")
        val sessions = dir.resolve(".kagent").resolve("sessions")
        Files.createDirectories(sessions)
        Files.writeString(
            sessions.resolve("session-2026-01-03T000000Z.jsonl"),
            """
            {"role":"user","content":"modify ui"}
            {"role":"assistant","content":"let me look","tool_calls":[{"id":"","name":"","arguments":"{}"}]}
            {"role":"tool","tool_call_id":"","name":"","content":"Unknown tool: ","is_error":true}
            {"role":"assistant","content":null,"tool_calls":[{"id":"","name":"","arguments":"{}"}]}
            {"role":"tool","tool_call_id":"","name":"","content":"Unknown tool: ","is_error":true}
            {"role":"user","content":"modify ui again"}
            """.trimIndent() + "\n",
        )

        val history = SessionReader(sessions).loadLatestHistory()

        assertNotNull(history)
        assertEquals(3, history.size)
        assertIs<AgentMessage.User>(history[0])
        val assistant = assertIs<AgentMessage.Assistant>(history[1])
        assertEquals("let me look", assistant.content)
        assertTrue(assistant.toolCalls.isEmpty())
        assertIs<AgentMessage.User>(history[2])
    }

    @Test
    fun reasoningContentRoundTripsThroughSessionJsonl() = runBlocking {
        val dir = Files.createTempDirectory("kagent-reasoning-session-test")
        val sessionFile = dir.resolve("session.jsonl")
        val assistant = AgentMessage.Assistant("answer", emptyList(), "reasoning trace")

        SessionWriter(sessionFile).append(assistant)
        val loaded = SessionReader(dir).loadFile(sessionFile)

        assertEquals(listOf(assistant), loaded)
    }
}

