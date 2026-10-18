package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.ui.TerminalTabSizing
import com.kzagent.kagent.desktop.ui.terminalTabSizing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalTabSizingTest {
    @Test
    fun `tabs shrink to share available space before overflowing`() {
        assertEquals(TerminalTabSizing(160, 0), terminalTabSizing(640, 4, 140, 250))
        assertEquals(TerminalTabSizing(250, 0), terminalTabSizing(640, 2, 140, 250))
    }

    @Test
    fun `overflow leaves whole tab slots at the end even with fractional scale rounding`() {
        for (available in 280..1400) {
            val sizing = terminalTabSizing(available, 32, 245, 438) // 175% desktop scale.
            val slots = (available / 245).coerceAtLeast(1)
            assertEquals(available, slots * sizing.width + sizing.endPadding)
            assertTrue(sizing.endPadding >= 0)
            // A single visible slot may reach the maximum width and leave more
            // than a rounding pixel; that space must remain trailing padding.
            if (sizing.width < 438) assertTrue(sizing.endPadding < slots)
            assertTrue(sizing.width <= 438)
            // At the end, the offset starts on a complete slot, never a lone close button.
            val maxScroll = 32 * sizing.width + sizing.endPadding - available
            assertEquals(0, maxScroll % sizing.width)
        }
    }

    @Test
    fun `very narrow and empty viewports remain valid`() {
        assertEquals(TerminalTabSizing(100, 0), terminalTabSizing(100, 8, 140, 250))
        assertEquals(TerminalTabSizing(0, 0), terminalTabSizing(0, 8, 140, 250))
        assertEquals(TerminalTabSizing(250, 0), terminalTabSizing(640, 0, 140, 250))
    }
}
