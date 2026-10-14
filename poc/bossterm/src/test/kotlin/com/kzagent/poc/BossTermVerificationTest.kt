package com.kzagent.poc

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.session.TerminalSessionStack
import com.kzagent.kagent.desktop.terminal.TerminalProcessPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeFalse

class BossTermVerificationTest {
    @Test
    fun `Unix session closes child when shell exits before watcher can sample`() = runBlocking {
        assumeFalse(isWindows(), "POSIX session probe is Unix-only")
        TerminalProcessPlatform().use { platform ->
            NativeSession(platform = platform).use { session ->
                var child: ProcessHandle? = null
                try {
                    session.engine.start()
                    session.connected()
                    assertNull(com.kzagent.kagent.desktop.terminal.TerminalUnixSession.capture(ProcessHandle.current().pid()))
                    assertNotNull(com.kzagent.kagent.desktop.terminal.TerminalUnixSession.capture(session.engine.processHandle?.getPid()))
                    session.engine.writeInput("trap '' HUP TERM; sleep 300 & echo \$! > child.pid; exit\r")
                    val file = session.workspace.resolve("child.pid")
                    withTimeout(10_000) { while (!Files.exists(file) || Files.readString(file).trim().isEmpty()) delay(25) }
                    child = ProcessHandle.of(Files.readString(file).trim().toLong()).orElse(null)
                    withTimeout(10_000) { session.engine.awaitTermination() }
                    assertFalse(child?.isAlive ?: false)
                    println("GATE host/fast-shell-exit: PASS")
                } finally { child?.takeIf { it.isAlive }?.destroyForcibly() }
            }
        }
    }

    @Test
    fun `direct renderer menu contains only host supported entries`() {
        val items = ai.rever.bossterm.compose.features.createTerminalContextMenuItems(
            hasSelection = false, onCopy = {}, onPaste = {}, onSelectAll = {},
            onClearScreen = {}, onClearScrollback = {}, onFind = {}, onOpenFolder = {},
        ).filterIsInstance<ai.rever.bossterm.compose.features.ContextMenuController.MenuItem>()
        assertEquals(listOf("copy", "paste", "select_all", "find", "clear", "clear_scrollback", "open_folder"), items.map { it.id })
        assertTrue(items.none { it.label.contains("Install") || it.label.contains("AI Assistant") })
        println("GATE host/context-menu-builder: PASS")
    }

    @Test
    fun `configured credential variable names are filtered before native spawn`() = runBlocking {
        var captured: ai.rever.bossterm.compose.PlatformServices.ProcessService.ProcessConfig? = null
        val native = ai.rever.bossterm.compose.getPlatformServices()
        val delegate = object : ai.rever.bossterm.compose.PlatformServices by native {
            override fun getProcessService() = object : ai.rever.bossterm.compose.PlatformServices.ProcessService {
                override suspend fun spawnProcess(config: ai.rever.bossterm.compose.PlatformServices.ProcessService.ProcessConfig): ai.rever.bossterm.compose.PlatformServices.ProcessService.ProcessHandle? {
                    captured = config
                    return null // No real process or inherited environment in this policy probe.
                }
            }
        }
        TerminalProcessPlatform(delegate, setOf("DEEPSEEK_API_KEY", "OPENAI_API_KEY")).use { platform ->
            platform.getProcessService().spawnProcess(ai.rever.bossterm.compose.PlatformServices.ProcessService.ProcessConfig(
                command = "unused", arguments = emptyList(),
                environment = mapOf("DEEPSEEK_API_KEY" to "fixture", "openai_api_key" to "fixture", "PATH" to "fixture"),
            ))
        }
        assertEquals(setOf("PATH"), captured?.environment?.keys)
        println("GATE host/environment-filter: PASS")
    }

    @Test
    fun `host owned platform cleans resistant child before and after natural shell exit`() = runBlocking {
        assumeFalse(isWindows(), "HUP probe is Unix-only; Windows process tree needs a native probe")
        for (exitShell in listOf(false, true)) {
            TerminalProcessPlatform().use { platform ->
                NativeSession(platform = platform).use { session ->
                    var child: ProcessHandle? = null
                    try {
                        session.engine.start()
                        session.connected()
                        session.engine.writeInput("trap '' HUP TERM; sleep 300 & echo \$! > child.pid\r")
                        val pidFile = session.workspace.resolve("child.pid")
                        withTimeout(10_000) { while (!Files.exists(pidFile) || Files.readString(pidFile).trim().isEmpty()) delay(25) }
                        child = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElseThrow()
                        // Allow the host to capture descendants before the shell can reparent them.
                        delay(200)
                        if (exitShell) session.engine.writeInput("exit\r") else session.engine.close()
                        withTimeout(10_000) { session.engine.awaitTermination() }
                        assertFalse(child.isAlive)
                    } finally {
                        child?.takeIf { it.isAlive }?.destroyForcibly()
                    }
                }
            }
        }
        println("GATE host/process-tree: PASS")
    }

    @Test
    fun `published embedder cannot replace installation menus or isolate renderer settings`() {
        ZipFile(System.getProperty("bossterm.sources")).use { sources ->
            fun source(name: String) = sources.getInputStream(
                sources.getEntry("desktopMain/ai/rever/bossterm/compose/$name"),
            ).bufferedReader().use { it.readText() }
            val embedder = source("EmbeddableTerminal.kt")
            val renderer = source("ui/ProperTerminal.kt")
            // This is a release-contract probe, not a claim that the menu gate passed.
            // If an upstream release fixes either limitation, review the new API here.
            assertTrue(embedder.contains("items = items + vcsItems"))
            assertTrue(embedder.contains("items = items + shellItems"))
            assertTrue(renderer.contains("val settingsManager = remember { SettingsManager.instance }"))
            assertFalse(renderer.contains("VersionControlMenuProvider"))
            assertFalse(renderer.contains("ShellCustomizationMenuProvider"))
            println("GATE menu/isolation: FAIL — embedder appends VCS/shell installation menus; renderer uses global settings")
            println("GATE host/menu: PASS — direct renderer does not construct installation menu providers (source audit)")
        }
    }

    @Test
    fun `real PTY starts in workspace resizes and reports natural exit`() = runBlocking {
        NativeSession().use { session ->
            session.engine.start()
            session.connected()
            session.engine.resize(110, 32)
            session.engine.writeInput(if (isWindows()) "cd > cwd.txt & exit 7\r" else "pwd > cwd.txt; exit 7\r")
            withTimeout(10_000) { session.engine.state.first { it is TerminalSessionEngine.State.Exited } }
            assertEquals(7, session.engine.exitCode)
            assertEquals(session.workspace.toRealPath(), Path.of(Files.readString(session.workspace.resolve("cwd.txt")).trim()).toRealPath())
            println("GATE native/start-resize-exit: PASS")
        }
    }

    @Test
    fun `close twice terminates shell and does not leave startup race behind`() = runBlocking {
        repeat(5) { iteration ->
            NativeSession().use { session ->
                session.engine.start()
                if (iteration % 2 == 0) session.connected()
                session.engine.close()
                session.engine.close()
                withTimeout(10_000) { session.engine.awaitTermination() }
                assertFalse(session.engine.processHandle?.isAlive() ?: false)
            }
        }
        println("GATE native/close-startup-race: PASS")
    }

    @Test
    fun `measure whether default close cleans a HUP resistant child`() = runBlocking<Unit> {
        assumeFalse(isWindows(), "HUP probe is Unix-only")
        NativeSession().use { session ->
            var child: ProcessHandle? = null
            try {
                session.engine.start()
                session.connected()
                session.engine.writeInput("trap '' HUP; sleep 300 & echo \$! > child.pid\r")
                val pidFile = session.workspace.resolve("child.pid")
                withTimeout(10_000) {
                    while (!Files.exists(pidFile) || Files.readString(pidFile).trim().isEmpty()) delay(25)
                }
                child = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElseThrow()
                assertTrue(child.isAlive)
                session.engine.close()
                withTimeout(10_000) { session.engine.awaitTermination() }
                delay(250)
                val cleaned = !child.isAlive
                println("GATE native/process-tree: ${if (cleaned) "PASS" else "FAIL — HUP-resistant background child survives default close"}")
                // Preserve the measured verdict even when other release-contract tests pass.
                Files.createDirectories(Path.of("build/poc-results"))
                Files.writeString(Path.of("build/poc-results/process-tree.txt"), if (cleaned) "PASS\n" else "FAIL\n")
            } finally {
                // Never leave a probe process behind, even when the candidate library does.
                child?.takeIf { it.isAlive }?.destroyForcibly()
                withTimeout(5_000) { while (child?.isAlive == true) delay(25) }
            }
        }
    }

    @Test
    fun `missing shell exposes error and finishes shutdown`() = runBlocking {
        NativeSession(command = "/nonexistent/kzagent-poc-shell").use { session ->
            session.engine.start()
            withTimeout(10_000) { session.engine.state.first { it is TerminalSessionEngine.State.Error } }
            withTimeout(10_000) { session.engine.awaitTermination() }
            assertFalse(session.engine.processHandle?.isAlive() ?: false)
            println("GATE native/startup-error: PASS")
        }
    }

    private class NativeSession(
        command: String = if (isWindows()) "cmd.exe" else "/bin/sh",
        platform: ai.rever.bossterm.compose.PlatformServices = ai.rever.bossterm.compose.getPlatformServices(),
    ) : AutoCloseable {
        val workspace: Path = Files.createTempDirectory("kzagent-bossterm-native-")
        private val display = ComposeTerminalDisplay(isolatedSettings())
        val engine = TerminalSessionEngine(
            settings = isolatedSettings(),
            workingDir = workspace.toString(),
            command = command,
            stack = TerminalSessionStack.create(isolatedSettings(), display),
            platformServices = platform,
        )

        suspend fun connected() = withTimeout(10_000) {
            val state = engine.state.first { it !is TerminalSessionEngine.State.Initializing }
            check(state is TerminalSessionEngine.State.Connected) { "PTY failed to connect: $state" }
        }

        override fun close() {
            engine.close()
            runBlocking { withTimeout(10_000) { engine.awaitTermination() } }
            display.dispose()
            workspace.toFile().deleteRecursively()
        }
    }

    companion object {
        private fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")
    }
}
