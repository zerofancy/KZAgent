package com.kzagent.kagent.agent

import com.kzagent.kagent.llm.AgentMessage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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

        val history = SessionReader(dir).loadLatestHistory()

        assertNotNull(history)
        assertEquals(3, history.size)
        assertIs<AgentMessage.User>(history[0])
        val assistant = assertIs<AgentMessage.Assistant>(history[1])
        assertEquals("list_files", assistant.toolCalls.single().name)
        assertIs<AgentMessage.Tool>(history[2])
    }
}

