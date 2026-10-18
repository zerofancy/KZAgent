package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.ui.TerminalFocusState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalFocusStateTest {
    @Test
    fun `completed close focuses successor only while its request remains current`() {
        val focus = TerminalFocusState("session")
        val pending = focus.request("closing")
        focus.retargetIfCurrent(pending, "successor")
        assertEquals("successor", focus.request?.tabId)
        assertEquals("session", focus.request?.sessionId)
        assertTrue(focus.request!!.requestId > pending.requestId)
        focus.retargetIfCurrent(focus.request!!, null)
        assertNull(focus.request?.tabId) // Empty panel targets the create button.
    }

    @Test
    fun `late close cannot steal focus after a new selection or cancellation`() {
        val focus = TerminalFocusState("session")
        val pending = focus.request("closing")
        val selected = focus.request("other")
        focus.retargetIfCurrent(pending, "successor")
        assertEquals(selected, focus.request)
        focus.cancel()
        focus.retargetIfCurrent(selected, "successor")
        assertNull(focus.request)
    }

    @Test
    fun `late consumption cannot clear a newer request for the same terminal`() {
        val focus = TerminalFocusState("session")
        val first = focus.request("tab")
        val second = focus.request("tab")
        focus.consume(first.requestId)
        assertEquals(second, focus.request)
        focus.consume(second.requestId)
        assertNull(focus.request)
    }
}
