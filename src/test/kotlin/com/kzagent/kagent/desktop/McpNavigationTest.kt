package com.kzagent.kagent.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpNavigationTest {
    @Test fun mcpDestinationDoesNotAlsoSelectTheActiveConversation() {
        assertFalse(isSessionNavigationSelected(settingsSelected = false, activeIndex = 0, sessionIndex = 0, mcpSelected = true))
        assertTrue(isSessionNavigationSelected(settingsSelected = false, activeIndex = 0, sessionIndex = 0, mcpSelected = false))
    }
}
