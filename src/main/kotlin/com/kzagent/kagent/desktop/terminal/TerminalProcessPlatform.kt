package com.kzagent.kagent.desktop.terminal

import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Tracks descendants before the PTY parent exits, since reparented children disappear from it. */
internal class TerminalProcessPlatform(
    private val delegate: PlatformServices = getPlatformServices(),
    private val excludedEnvironmentKeys: Set<String> = emptySet(),
) : PlatformServices by delegate, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handles = ConcurrentHashMap.newKeySet<OwnedProcess>()
    private val closed = AtomicBoolean()
    private val cleanupFailure = AtomicReference<Throwable?>(null)

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
        private val stopped = CompletableDeferred<Unit>()
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
            if (!stopping.compareAndSet(false, true)) { stopped.await(); return@withContext }
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
                } finally { handles.remove(this@OwnedProcess) }
            }
            failure?.let { error ->
                cleanupFailure.compareAndSet(null, error)
                stopped.completeExceptionally(error)
                throw error
            }
            stopped.complete(Unit)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        kotlinx.coroutines.runBlocking {
            try {
                val results = handles.toList().map { handle -> async { runCatching { handle.kill() } } }.awaitAll()
                results.firstOrNull { it.isFailure }?.getOrThrow()
                cleanupFailure.get()?.let { throw IllegalStateException("Terminal process cleanup failed", it) }
            } finally { scope.cancel() }
        }
    }
}
