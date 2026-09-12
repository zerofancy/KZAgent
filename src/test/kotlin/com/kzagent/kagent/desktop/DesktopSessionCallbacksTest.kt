package com.kzagent.kagent.desktop

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.desktop.app.DesktopSessionSettingsState
import com.kzagent.kagent.desktop.app.confirmSessionRename
import com.kzagent.kagent.tools.ApprovalDecision
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ApprovalResult
import com.kzagent.kagent.tools.ApprovalSource
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopSessionCallbacksTest {
    @Test
    fun renameCapturesTargetBeforeDismissal() {
        var targetIndex = 2
        var renamed: Pair<Int, String>? = null
        confirmSessionRename(
            "新名称",
            onRenameSession = { renamed = targetIndex to it },
            onDismiss = { targetIndex = -1 },
        )
        assertEquals(2 to "新名称", renamed)
        assertEquals(-1, targetIndex)
    }

    @Test
    fun blankRenameOnlyDismisses() {
        var dismissed = false
        confirmSessionRename(
            "  ",
            onRenameSession = { error("空名称不应触发重命名") },
            onDismiss = { dismissed = true },
        )
        assertTrue(dismissed)
    }

    @Test
    fun startupConfigurationHandlesSuccessFailureAndCancellation() = runBlocking {
        val sessionsRoot = Files.createTempDirectory("kagent-startup-config-test")
        val policy = ApprovalPolicy {
            ApprovalResult(ApprovalDecision.DENY, ApprovalSource.HUMAN, "test")
        }
        try {
            SessionManager(policy, sessionsRoot).use { manager ->
                val state = DesktopSessionSettingsState(manager, this, {}, {})
                try {
                    var requests = 0
                    val config = AppConfig(apiKey = "test-placeholder")
                    state.loadInitialConfig({ requests++ }, { config })
                    assertSame(config, state.savedConfig)
                    assertTrue(state.configLoaded)
                    assertEquals(0, requests)

                    state.loadInitialConfig({ requests++ }, { error("配置不可用") })
                    assertNull(state.savedConfig)
                    assertTrue(state.configLoaded)
                    assertEquals(1, requests)
                    assertFalse(manager.initialized)

                    assertFailsWith<CancellationException> {
                        state.loadInitialConfig({ requests++ }, { throw CancellationException() })
                    }
                    assertEquals(1, requests)
                } finally {
                    state.close()
                }
            }
        } finally {
            Files.deleteIfExists(sessionsRoot)
        }
    }
}
