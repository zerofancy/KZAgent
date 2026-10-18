package com.kzagent.kagent.desktop

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import com.kzagent.kagent.desktop.terminal.TerminalProcessPlatform
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerminalProcessPlatformTest {
    @Test
    fun `failed process cleanup remains owned and retryable after platform closes`() = runBlocking<Unit> {
        val process = FakeProcess(failFirst = true)
        val platform = platform(process)
        platform.getProcessService().spawnProcess(config())
        assertTrue(runCatching { platform.close() }.isFailure)
        assertTrue(process.isAlive())
        platform.close()
        assertFalse(process.isAlive())
        platform.close()
        assertEquals(2, process.kills.get())
        assertTrue(runCatching { platform.getProcessService().spawnProcess(config()) }.isFailure)
    }

    @Test
    fun `concurrent process cleanup and platform close terminate underlying handle once`() = runBlocking<Unit> {
        val process = FakeProcess()
        val platform = platform(process)
        val handle = assertNotNull(platform.getProcessService().spawnProcess(config()))
        List(4) { async { handle.kill() } }.awaitAll()
        platform.close()
        assertEquals(1, process.kills.get())
        assertFalse(process.isAlive())
    }

    private fun platform(process: FakeProcess) = TerminalProcessPlatform(object : PlatformServices by getPlatformServices() {
        override fun getProcessService() = object : PlatformServices.ProcessService {
            override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig) = process
        }
    })

    private fun config() = PlatformServices.ProcessService.ProcessConfig(
        command = "fixture", arguments = emptyList(), environment = emptyMap(),
    )

    private class FakeProcess(private val failFirst: Boolean = false) : PlatformServices.ProcessService.ProcessHandle {
        val kills = AtomicInteger()
        @Volatile private var alive = true
        override suspend fun kill() {
            if (kills.incrementAndGet() == 1 && failFirst) error("fixture cleanup failure")
            alive = false
        }
        override suspend fun write(data: String) = Unit
        override suspend fun writeBytes(data: ByteArray) = Unit
        override suspend fun read(): String? = null
        override fun isAlive() = alive
        override suspend fun waitFor() = 0
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override fun getExitCode(): Int? = if (alive) null else 0
        override fun getPid(): Long? = null
        override fun getWorkingDirectory(): String? = null
    }
}
