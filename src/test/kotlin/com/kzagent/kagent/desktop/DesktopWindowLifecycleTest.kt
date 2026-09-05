package com.kzagent.kagent.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowLifecycleTest {
    @Test
    fun macOsKeepsApplicationRunningAfterWindowCloses() {
        assertEquals(
            com.kzagent.kagent.desktop.app.DesktopWindowLifecycle.KEEP_RUNNING,
            _root_ide_package_.com.kzagent.kagent.desktop.app.desktopWindowLifecycle("Mac OS X"),
        )
    }

    @Test
    fun windowsAndLinuxExitAfterWindowCloses() {
        assertEquals(
            com.kzagent.kagent.desktop.app.DesktopWindowLifecycle.EXIT_AFTER_CLOSE,
            _root_ide_package_.com.kzagent.kagent.desktop.app.desktopWindowLifecycle("Windows 11"),
        )
        assertEquals(
            com.kzagent.kagent.desktop.app.DesktopWindowLifecycle.EXIT_AFTER_CLOSE,
            _root_ide_package_.com.kzagent.kagent.desktop.app.desktopWindowLifecycle("Linux"),
        )
    }
}
