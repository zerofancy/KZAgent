package com.kzagent.kagent.desktop

import androidx.compose.ui.unit.dp
import io.github.composefluent.component.NavigationDisplayMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationViewLayoutTest {
    @Test
    fun compactModeIsUsedBelowBreakpoint() {
        assertEquals(
            NavigationDisplayMode.LeftCompact,
            navigationDisplayModeForWidth(NavigationCompactBreakpoint - 1.dp),
        )
    }

    @Test
    fun leftModeIsUsedAtAndAboveBreakpoint() {
        assertEquals(
            NavigationDisplayMode.Left,
            navigationDisplayModeForWidth(NavigationCompactBreakpoint),
        )
        assertEquals(
            NavigationDisplayMode.Left,
            navigationDisplayModeForWidth(NavigationCompactBreakpoint + 1.dp),
        )
    }

    @Test
    fun settingsSelectionSuppressesSessionSelection() {
        assertTrue(isSessionNavigationSelected(settingsSelected = false, activeIndex = 2, sessionIndex = 2))
        assertFalse(isSessionNavigationSelected(settingsSelected = true, activeIndex = 2, sessionIndex = 2))
        assertFalse(isSessionNavigationSelected(settingsSelected = false, activeIndex = 2, sessionIndex = 1))
    }

    @Test
    fun onlyCompactDestinationsCollapseTheNavigation() {
        assertTrue(shouldCollapseNavigationAfterDestination(NavigationDisplayMode.LeftCompact))
        assertFalse(shouldCollapseNavigationAfterDestination(NavigationDisplayMode.Left))
        assertFalse(shouldCollapseNavigationAfterDestination(NavigationDisplayMode.LeftCollapsed))
        assertFalse(shouldCollapseNavigationAfterDestination(NavigationDisplayMode.Top))
    }
}
