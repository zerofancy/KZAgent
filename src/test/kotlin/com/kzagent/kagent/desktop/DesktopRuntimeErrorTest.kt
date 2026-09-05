package com.kzagent.kagent.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopRuntimeErrorTest {
    @Test
    fun runtimeErrorMessageShowsDeepestActionableCause() {
        val failure = IllegalStateException(
            "com/kzagent/kagent/tools/TextFileCodec",
            IllegalArgumentException("Unsupported charset GB18030"),
        )

        assertEquals("Unsupported charset GB18030",
            _root_ide_package_.com.kzagent.kagent.desktop.app.runtimeErrorMessage(failure)
        )
    }
}
