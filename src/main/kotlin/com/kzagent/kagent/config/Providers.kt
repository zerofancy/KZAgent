package com.kzagent.kagent.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kind of an OpenAI-compatible provider. Determines how the model catalog is
 * fetched and parsed. Built-in providers DeepSeek and OpenRouter keep their legacy
 * catalog quirks; user-added providers are treated as generic OpenAI-compatible
 * endpoints.
 */
@Serializable
enum class ProviderKind(val displayName: String, val defaultBaseUrl: String) {
    @SerialName("DEEPSEEK") DEEPSEEK("DeepSeek", "https://api.deepseek.com"),
    @SerialName("OPENROUTER") OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
    @SerialName("MIMOCODE") MIMOCODE("MiMo Code", "https://api.xiaomimimo.com/v1"),
    @SerialName("OPENAI_COMPATIBLE") OPENAI_COMPATIBLE("Custom OpenAI-Compatible", ""),
}

/**
 * A configured model provider instance. [id] is a stable unique key used to persist
 * the selection and link it to a [ModelSelection]; [name] is the human-facing label.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val apiKey: String,
    val baseUrl: String,
) {
    init {
        require(id.isNotBlank()) { "Provider id must not be blank." }
        require(name.isNotBlank()) { "Provider name must not be blank." }
        require(apiKey.isNotBlank()) { "Provider API key must not be blank." }
        require(baseUrl.isNotBlank()) { "Provider base URL must not be blank." }
    }

    /** Returns a copy with the trailing slash trimmed from the base URL. */
    fun normalized(): ProviderConfig = copy(baseUrl = baseUrl.trim().trimEnd('/'))
}

/** A template describing how to create a new provider in the settings UI. */
data class ProviderTemplate(
    val kind: ProviderKind,
    val name: String,
    val defaultBaseUrl: String,
) {
    fun createProvider(id: String, apiKey: String): ProviderConfig = ProviderConfig(
        id = id,
        name = name,
        kind = kind,
        apiKey = apiKey,
        baseUrl = defaultBaseUrl,
    )
}

@Serializable
data class ModelSelection(
    val provider: String,
    val modelId: String,
    val contextWindowSize: Int? = null,
    val supportsToolChoice: Boolean = true,
) {
    init {
        require(modelId.isNotBlank()) { "Model id must not be blank." }
        require(contextWindowSize == null || contextWindowSize > 0) {
            "Context window size must be positive when present."
        }
    }
}

@Serializable
data class ModelDescriptor(
    val provider: String,
    val id: String,
    val providerName: String = "",
    val displayName: String = id,
    val contextWindowSize: Int? = null,
    val supportsToolChoice: Boolean = true,
) {
    fun toSelection(): ModelSelection = ModelSelection(
        provider = provider,
        modelId = id,
        contextWindowSize = contextWindowSize,
        supportsToolChoice = supportsToolChoice,
    )
}

object ProviderTemplates {
    val DEEPSEEK = ProviderTemplate(ProviderKind.DEEPSEEK, "DeepSeek", ProviderKind.DEEPSEEK.defaultBaseUrl)
    val OPENROUTER = ProviderTemplate(ProviderKind.OPENROUTER, "OpenRouter", ProviderKind.OPENROUTER.defaultBaseUrl)
    val MIMOCODE = ProviderTemplate(ProviderKind.MIMOCODE, "MiMo Code", ProviderKind.MIMOCODE.defaultBaseUrl)
    val OPENAI_COMPATIBLE = ProviderTemplate(
        ProviderKind.OPENAI_COMPATIBLE,
        "Custom OpenAI-Compatible",
        ProviderKind.OPENAI_COMPATIBLE.defaultBaseUrl,
    )
    val all = listOf(DEEPSEEK, OPENROUTER, MIMOCODE, OPENAI_COMPATIBLE)
}
