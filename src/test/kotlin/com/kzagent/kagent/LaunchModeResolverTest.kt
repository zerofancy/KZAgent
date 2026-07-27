package com.kzagent.kagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.Path

class LaunchModeResolverTest {
    @Test
    fun emptyArgsStartChatOutsidePackagedApplication() {
        val request = assertIs<LaunchRequest.Cli>(
            LaunchModeResolver.resolve(emptyArray(), packagedAppPath = null),
        )
        assertTrue(request.args.contentEquals(arrayOf("chat")))
    }

    @Test
    fun packagedApplicationWithoutArgsRestoresDesktop() {
        val workspace = Path.of("build", "packaged-workspace")
        val request = assertIs<LaunchRequest.Desktop>(
            LaunchModeResolver.resolve(
                emptyArray(),
                currentDirectory = workspace,
                packagedAppPath = "/Applications/KZAgent.app/Contents/MacOS/KZAgent",
            ),
        )
        assertEquals(workspace.toAbsolutePath().normalize(), request.initialWorkspace)
        assertFalse(request.createStartupSession)
    }

    @Test
    fun appArgStartsDesktopWithFreshSessionInCurrentDirectory() {
        val workspace = Path.of("build", "cli-app-workspace")
        val request = assertIs<LaunchRequest.Desktop>(
            LaunchModeResolver.resolve(
                arrayOf("app"),
                currentDirectory = workspace,
                packagedAppPath = null,
            ),
        )
        assertEquals(workspace.toAbsolutePath().normalize(), request.initialWorkspace)
        assertTrue(request.createStartupSession)
    }

    @Test
    fun cliArgsArePreserved() {
        val ask = assertIs<LaunchRequest.Cli>(
            LaunchModeResolver.resolve(arrayOf("ask", "hello"), packagedAppPath = null),
        )
        val chat = assertIs<LaunchRequest.Cli>(
            LaunchModeResolver.resolve(arrayOf("chat"), packagedAppPath = null),
        )
        assertTrue(ask.args.contentEquals(arrayOf("ask", "hello")))
        assertTrue(chat.args.contentEquals(arrayOf("chat")))
    }
}
