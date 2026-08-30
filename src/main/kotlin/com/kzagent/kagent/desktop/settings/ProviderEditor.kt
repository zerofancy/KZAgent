package com.kzagent.kagent.desktop

import com.kzagent.kagent.config.ProviderConfig
import com.kzagent.kagent.config.ProviderKind

/** Editable view-model for a single provider row in the settings panel. */
internal data class ProviderEditor(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val apiKey: String,
    val baseUrl: String,
) {
    fun toConfig(): ProviderConfig = ProviderConfig(
        id = id.trim(),
        name = name.trim(),
        kind = kind,
        apiKey = apiKey.trim(),
        baseUrl = baseUrl.trim().trimEnd('/'),
    )
}
