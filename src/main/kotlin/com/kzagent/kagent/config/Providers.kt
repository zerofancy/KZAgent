package com.kzagent.kagent.config

enum class ProviderId(val configValue: String, val displayName: String) {
    DEEPSEEK("deepseek", "DeepSeek"),
    OPENROUTER("openrouter", "OpenRouter");

    companion object {
        fun fromConfig(value: String?): ProviderId? = entries.firstOrNull {
            it.configValue.equals(value?.trim(), ignoreCase = true)
        }
    }
}

data class ProviderConfig(
    val apiKey: String,
    val baseUrl: String,
) {
    init {
        require(apiKey.isNotBlank()) { "Provider API key must not be blank." }
        require(baseUrl.isNotBlank()) { "Provider base URL must not be blank." }
    }
}

data class ModelSelection(
    val provider: ProviderId,
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

data class ModelDescriptor(
    val provider: ProviderId,
    val id: String,
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
