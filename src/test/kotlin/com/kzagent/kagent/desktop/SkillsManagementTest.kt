package com.kzagent.kagent.desktop

import com.kzagent.kagent.config.*
import com.kzagent.kagent.desktop.app.DesktopSessionSettingsState
import com.kzagent.kagent.skill.*
import com.kzagent.kagent.tools.AlwaysApprovePolicy
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class SkillsManagementTest {
    @Test fun filterCombinesSearchOriginAndStatus() {
        val entry = SkillEntry("id", null, SkillOrigin.USER, manifest = SkillManifest("pdf", "Read documents"))
        val entries = listOf(entry, entry.copy(id = "other", origin = SkillOrigin.EXTRA, disabled = true))
        assertEquals(listOf(entry), filterSkills(entries, "DOCUMENT", SkillOrigin.USER, "已启用"))
        assertTrue(filterSkills(entries, "missing", null, null).isEmpty())
        assertEquals(1, filterSkills(entries, "", null, "已禁用").size)
    }

    @Test fun savesMergeAndFailurePreservesPublishedState() = runBlocking {
        val root = Files.createTempDirectory("skills-settings")
        SessionManager(AlwaysApprovePolicy, root).use { manager ->
            manager.loadOrCreate(root)
            val session = manager.activeSession()
            val running = Job()
            session.currentJob = running
            session.isBusy = true
            var fail = false
            val writes = mutableListOf<AppConfig>()
            val state = DesktopSessionSettingsState(manager, this, {}, {}, writeConfig = {
                if (fail) error("disk full")
                writes += it
            })
            try {
                val original = AppConfig(apiKey = "test-placeholder", userPrompt = "keep prompt")
                state.loadInitialConfig({}, { original })
                state.saveSkills { it.copy(enabled = false) }
                state.saveSkills { it.copy(disabled = listOf("pdf")) }
                assertFalse(state.savedConfig!!.skills.enabled)
                assertEquals(listOf("pdf"), state.savedConfig!!.skills.disabled)
                assertEquals(original.userPrompt, state.savedConfig!!.userPrompt)
                assertTrue(running.isActive)
                assertTrue(session.isBusy)
                fail = true
                val saved = state.savedConfig
                assertFailsWith<IllegalStateException> { state.saveSkills { it.copy(enabled = true) } }
                assertSame(saved, state.savedConfig)
                assertEquals(2, writes.size)
                fail = false
                state.saveSettings(original.copy(userPrompt = "updated prompt"))
                while (state.settingsSaving) delay(1)
                assertFalse(state.savedConfig!!.skills.enabled)
                assertEquals(listOf("pdf"), state.savedConfig!!.skills.disabled)
                assertEquals("updated prompt", state.savedConfig!!.userPrompt)
            } finally { state.close(); running.cancel() }
        }
    }
}
