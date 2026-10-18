package com.kzagent.kagent.desktop.terminal

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Tracks descendants before the PTY parent exits, since reparented children disappear from it. */
internal class TerminalProcessPlatform(
    private val delegate: PlatformServices = getPlatformServices(),
    private val excludedEnvironmentKeys: Set<String> = emptySet(),
) : PlatformServices by delegate, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handles = ConcurrentHashMap.newKeySet<OwnedProcess>()
    private val closed = AtomicBoolean()

    override fun getProcessService() = object : PlatformServices.ProcessService {
        override suspend fun spawnProcess(config: PlatformServices.ProcessService.ProcessConfig): PlatformServices.ProcessService.ProcessHandle? {
            check(!closed.get()) { "Terminal platform is closed" }
            val environment = config.environment.filterKeys { key ->
                excludedEnvironmentKeys.none { it.equals(key, ignoreCase = true) }
            }
            val raw = delegate.getProcessService().spawnProcess(config.copy(environment = environment)) ?: return null
            val owned = OwnedProcess(raw)
            handles.add(owned)
            if (closed.get()) owned.kill()
            return owned
        }
    }

    private inner class OwnedProcess(
        private val raw: PlatformServices.ProcessService.ProcessHandle,
    ) : PlatformServices.ProcessService.ProcessHandle by raw {
        private val stopping = AtomicBoolean()
        private val killMutex = Mutex()
        private var killed = false
        private val rootProcess = raw.getPid()?.let { ProcessHandle.of(it).orElse(null) }
        private val unixSession = TerminalUnixSession.capture(raw.getPid())
        private val descendants = ConcurrentHashMap<Long, ProcessHandle>()
        private val watcher = scope.launch {
            while (!stopping.get()) {
                rememberDescendants()
                delay(100)
            }
        }

        private fun rememberDescendants() {
            descendants.entries.removeIf { !it.value.isAlive }
            rootProcess?.descendants()?.use { children ->
                children.forEach { descendants[it.pid()] = it }
            }
        }

        override suspend fun kill() = withContext(Dispatchers.IO) {
            killMutex.withLock {
                if (killed) return@withLock
                stopping.set(true)
                var failure: Throwable? = null
                try {
                    rememberDescendants()
                    // Catch ordinary background jobs that outlive an immediately exiting shell.
                    unixSession?.let { session -> TerminalUnixSession.members(session).forEach { descendants[it.pid()] = it } }
                    watcher.cancel()
                    // Retain ProcessHandle identities rather than re-resolving PIDs after reparenting.
                    descendants.values.filter { it.isAlive }.forEach { it.destroy() }
                    raw.kill()
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    watcher.cancel()
                    try {
                        rootProcess?.takeIf { it.isAlive }?.destroyForcibly()
                        val deadline = System.nanoTime() + 2_000_000_000L
                        while (descendants.values.any { it.isAlive } && System.nanoTime() < deadline) delay(25)
                        descendants.values.filter { it.isAlive }.forEach { it.destroyForcibly() }
                        val forceDeadline = System.nanoTime() + 1_000_000_000L
                        while (descendants.values.any { it.isAlive } && System.nanoTime() < forceDeadline) delay(25)
                        check(descendants.values.none { it.isAlive }) { "Terminal child process failed to stop" }
                    } catch (error: Throwable) {
                        if (failure == null) failure = error
                    }
                }
                // A failure retains this identity and can be retried under the same mutex.
                failure?.let { throw it }
                handles.remove(this@OwnedProcess)
                killed = true
            }
        }
    }

    @Synchronized
    override fun close() {
        closed.set(true)
        kotlinx.coroutines.runBlocking {
            try {
                val results = handles.toList().map { handle -> async { runCatching { handle.kill() } } }.awaitAll()
                results.firstOrNull { it.isFailure }?.getOrThrow()
            } finally { scope.cancel() }
        }
    }
}
