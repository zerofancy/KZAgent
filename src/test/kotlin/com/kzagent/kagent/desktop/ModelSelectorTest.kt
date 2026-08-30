package com.kzagent.kagent.desktop

import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ModelSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelSelectorTest {
    @Test
    fun searchMatchesDisplayNameAndIdAndRetainsOfflineCurrentSelection() {
        val current = ModelSelection("openrouter", "vendor/offline", 64_000)
        val models = listOf(
            ModelDescriptor("deepseek", "deepseek-v4-pro", displayName = "DeepSeek V4 Pro"),
            ModelDescriptor("openrouter", "anthropic/claude-test", displayName = "Claude Test"),
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
