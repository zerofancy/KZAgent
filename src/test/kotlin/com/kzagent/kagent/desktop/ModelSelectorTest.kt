package com.kzagent.kagent.desktop

import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelSelectorTest {
    @Test
    fun searchMatchesDisplayNameAndIdAndRetainsOfflineCurrentSelection() {
        val current = ModelSelection(ProviderId.OPENROUTER, "vendor/offline", 64_000)
        val models = listOf(
            ModelDescriptor(ProviderId.DEEPSEEK, "deepseek-v4-pro", "DeepSeek V4 Pro"),
            ModelDescriptor(ProviderId.OPENROUTER, "anthropic/claude-test", "Claude Test"),
        )

        assertEquals(
            listOf("anthropic/claude-test"),
            filterModelCatalog(models, current, "claude").map { it.id },
        )
        assertEquals(
            listOf("deepseek-v4-pro"),
            filterModelCatalog(models, current, "deepseek-v4").map { it.id },
        )
        assertTrue(filterModelCatalog(models, current, "").any { it.id == "vendor/offline" })
    }
}
