package com.kzagent.kagent.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class AppConfig(
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
    }
}

object AppConfigLoader {
    fun load(workspace: Path, env: Map<String, String> = System.getenv()): AppConfig {
        val props = Properties()
        val localProperties = workspace.resolve("local.properties")
        if (Files.exists(localProperties)) {
            Files.newInputStream(localProperties).use { props.load(it) }
        }

        val apiKey = props.getProperty("deepseek.api.key")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: env["DEEPSEEK_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException(
                "Missing DeepSeek API key. Set deepseek.api.key in local.properties or DEEPSEEK_API_KEY."
            )

        val baseUrl = props.getProperty("deepseek.base.url")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: AppConfig.DEFAULT_BASE_URL

        val model = props.getProperty("deepseek.model")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: AppConfig.DEFAULT_MODEL

        return AppConfig(apiKey = apiKey, baseUrl = baseUrl.trimEnd('/'), model = model)
    }
}

object SecretRedactor {
    private val secretPatterns = listOf(
        Regex("sk-[A-Za-z0-9_-]{12,}"),
        Regex("(?i)(Authorization:\\s*Bearer\\s+)[^\\s]+"),
        Regex("(?i)(\"Authorization\"\\s*:\\s*\"Bearer\\s+)[^\"]+"),
    )

    fun redact(value: String): String {
        var redacted = value
        for (pattern in secretPatterns) {
            redacted = pattern.replace(redacted) { match ->
                if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty()) {
                    match.groupValues[1] + "***REDACTED***"
                } else {
                    "***REDACTED***"
                }
            }
        }
        return redacted
    }
}

