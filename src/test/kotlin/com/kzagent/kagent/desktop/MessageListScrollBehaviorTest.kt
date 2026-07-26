package com.kzagent.kagent.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageListScrollBehaviorTest {
    @Test
    fun `switching sessions jumps directly to the bottom`() {
        assertEquals(
            MessageListScrollBehavior.JUMP_TO_BOTTOM,
            messageListScrollBehavior(
                previousSessionId = "session-a",
                activeSessionId = "session-b",
                previousMessageCount = 12,
                currentMessageCount = 12,
            ),
        )
    }

    @Test
    fun `the initially displayed session jumps directly to the bottom`() {
        assertEquals(
            MessageListScrollBehavior.JUMP_TO_BOTTOM,
            messageListScrollBehavior(
                previousSessionId = null,
                activeSessionId = "session-a",
                previousMessageCount = 0,
                currentMessageCount = 8,
            ),
        )
    }

    @Test
    fun `new messages in the current session animate to the bottom`() {
        assertEquals(
            MessageListScrollBehavior.ANIMATE_TO_BOTTOM,
            messageListScrollBehavior(
                previousSessionId = "session-a",
                activeSessionId = "session-a",
                previousMessageCount = 8,
                currentMessageCount = 9,
            ),
        )
    }

    @Test
    fun `unchanged current session does not request another scroll`() {
        assertEquals(
            MessageListScrollBehavior.NONE,
            messageListScrollBehavior(
                previousSessionId = "session-a",
                activeSessionId = "session-a",
                previousMessageCount = 8,
                currentMessageCount = 8,
            ),
        )
    }

    @Test
    fun `compose unknown scroll range is not treated as measured`() {
        assertFalse(isMeasuredMessageScrollRange(Int.MAX_VALUE))
        assertTrue(isMeasuredMessageScrollRange(0))
        assertTrue(isMeasuredMessageScrollRange(240))
    }
}
