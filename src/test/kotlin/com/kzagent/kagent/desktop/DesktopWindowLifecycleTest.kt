package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.app.DesktopWindowLifecycle
import com.kzagent.kagent.desktop.app.desktopWindowLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowLifecycleTest {
    @Test
    fun macOsKeepsApplicationRunningAfterWindowCloses() {
        assertEquals(
            DesktopWindowLifecycle.KEEP_RUNNING,
            desktopWindowLifecycle("Mac OS X"),
        )
    }

    @Test
    fun windowsAndLinuxExitAfterWindowCloses() {
        assertEquals(
            DesktopWindowLifecycle.EXIT_AFTER_CLOSE,
            desktopWindowLifecycle("Windows 11"),
        )
        assertEquals(
            DesktopWindowLifecycle.EXIT_AFTER_CLOSE,
            desktopWindowLifecycle("Linux"),
        )
    }
}
