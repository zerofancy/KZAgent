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
    @Test
    fun draftPreferencesPersistWithoutCreatingSessionsOrDismissingPage() = runBlocking {
        val root = Files.createTempDirectory("kagent-draft-config-test")
        val policy = ApprovalPolicy { ApprovalResult(ApprovalDecision.DENY, ApprovalSource.HUMAN, "test") }
        SessionManager(policy, root).use { manager ->
            manager.loadSessions(root)
            var writes = 0
            var dismissals = 0
            val state = DesktopSessionSettingsState(manager, this, { dismissals++ }, {}, { writes++ })
            try {
                val config = AppConfig(apiKey = "test-placeholder")
                state.savedConfig = config
                val selected = config.defaultModel.copy(modelId = "draft-model")
                state.commitNewSessionPreferences(selected, com.kzagent.kagent.tools.ApprovalMode.MANUAL)
                assertEquals(selected, state.savedConfig?.defaultModel)
                assertEquals(com.kzagent.kagent.tools.ApprovalMode.MANUAL, state.savedConfig?.approvalMode)
                assertEquals(1, writes)
                assertEquals(0, dismissals)
                assertTrue(manager.sessions.isEmpty())
                val session = manager.startNewSessionInWorkspace(root, selected)
                session.isBusy = true
                assertFailsWith<IllegalStateException> {
                    state.commitNewSessionPreferences(selected, com.kzagent.kagent.tools.ApprovalMode.AUTO)
                }
                assertEquals(1, writes)
                state.commitNewSessionPreferences(selected, com.kzagent.kagent.tools.ApprovalMode.MANUAL)
                assertTrue(session.isBusy)
            } finally { state.close() }
        }
    }

    @Test
    fun draftConfigurationWriteFailureAndMissingProviderLeaveStateUntouched() = runBlocking {
        val root = Files.createTempDirectory("kagent-draft-config-failure")
        val policy = ApprovalPolicy { ApprovalResult(ApprovalDecision.DENY, ApprovalSource.HUMAN, "test") }
        SessionManager(policy, root).use { manager ->
            val config = AppConfig(apiKey = "test-placeholder")
            val state = DesktopSessionSettingsState(manager, this, {}, {}, { error("write failed") })
            try {
                state.savedConfig = config
                assertFailsWith<IllegalArgumentException> {
                    state.commitNewSessionPreferences(config.defaultModel.copy(provider = "removed"), config.approvalMode)
                }
                assertFailsWith<IllegalStateException> {
                    state.commitNewSessionPreferences(config.defaultModel.copy(modelId = "draft"), config.approvalMode)
                }
                assertSame(config, state.savedConfig)
                assertTrue(manager.sessions.isEmpty())
            } finally { state.close() }
        }
    }

}
