package com.kzagent.kagent

import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchModeResolverTest {
    @Test
    fun emptyArgsLaunchDesktop() {
        assertEquals(LaunchMode.Desktop, LaunchModeResolver.resolve(emptyArray()))
    }

    @Test
    fun argsLaunchCli() {
        assertEquals(LaunchMode.Cli, LaunchModeResolver.resolve(arrayOf("ask", "hello")))
        assertEquals(LaunchMode.Cli, LaunchModeResolver.resolve(arrayOf("chat")))
    }
}
