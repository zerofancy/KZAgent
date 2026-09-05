package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.app.DesktopInstanceStart
import com.kzagent.kagent.desktop.app.DesktopLaunchRequest
import com.kzagent.kagent.desktop.app.DesktopSingleInstanceCoordinator
import com.kzagent.kagent.desktop.app.requireReadableWorkspace
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DesktopSingleInstanceTest {
    @Test
    fun secondaryLaunchesAreForwardedInOrderAndBufferedUntilCollected() = runBlocking {
        val root = Files.createTempDirectory("kagent-single-instance-test")
        val lockFile = root.resolve("desktop-instance.lock")
        val first = assertIs<DesktopInstanceStart.Primary>(
            DesktopSingleInstanceCoordinator.startOrForward(
                lockFile,
                DesktopLaunchRequest.Activate,
            ),
        )
        try {
            val workspace = root.resolve("中文 workspace").toAbsolutePath().normalize()
            assertIs<DesktopInstanceStart.Forwarded>(
                DesktopSingleInstanceCoordinator.startOrForward(
                    lockFile,
                    DesktopLaunchRequest.OpenWorkspace(workspace),
                ),
            )
            assertIs<DesktopInstanceStart.Forwarded>(
                DesktopSingleInstanceCoordinator.startOrForward(
                    lockFile,
                    DesktopLaunchRequest.Activate,
                ),
            )

            val requests = withTimeout(2_000.milliseconds) {
                first.coordinator.requests.take(2).toList()
            }
            assertEquals(
                listOf(
                    DesktopLaunchRequest.OpenWorkspace(workspace),
                    DesktopLaunchRequest.Activate,
                ),
                requests,
            )
        } finally {
            first.coordinator.close()
        }
    }

    @Test
    fun closingPrimaryAllowsAnotherPrimaryToAcquireTheSameLock() {
        val lockFile = Files.createTempDirectory("kagent-single-instance-restart")
            .resolve("desktop-instance.lock")
        val first = assertIs<DesktopInstanceStart.Primary>(
            DesktopSingleInstanceCoordinator.startOrForward(
                lockFile,
                DesktopLaunchRequest.Activate,
            ),
        )
        first.coordinator.close()

        val replacement = assertIs<DesktopInstanceStart.Primary>(
            DesktopSingleInstanceCoordinator.startOrForward(
                lockFile,
                DesktopLaunchRequest.Activate,
            ),
        )
        replacement.coordinator.close()
    }

    @Test
    fun corruptEndpointFailsClosedInsteadOfStartingAnotherGui() {
        val lockFile = Files.createTempDirectory("kagent-single-instance-corrupt")
            .resolve("desktop-instance.lock")
        RandomAccessFile(lockFile.toFile(), "rw").use { owner ->
            owner.seek(1)
            owner.write("not-an-endpoint".toByteArray())
            owner.channel.lock(0, 1, false).use {
                assertFailsWith<IllegalStateException> {
                    DesktopSingleInstanceCoordinator.startOrForward(
                        lockFile = lockFile,
                        request = DesktopLaunchRequest.OpenWorkspace(Path.of(".").toAbsolutePath()),
                        forwardTimeoutMillis = 100,
                    )
                }
            }
        }
    }

    @Test
    fun forwardedWorkspaceMustStillExistAndBeReadable() {
        val workspace = Files.createTempDirectory("kagent-forwarded-workspace")

        assertEquals(workspace, requireReadableWorkspace(workspace))
        val error = assertFailsWith<IllegalStateException> {
            requireReadableWorkspace(workspace.resolve("missing"))
        }
        assertTrue(error.message.orEmpty().contains("不存在或不可读"))
    }
}
