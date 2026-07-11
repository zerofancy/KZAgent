package com.kzagent.kagent.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.io.StringReader
import java.util.Properties

data class AppConfig(
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val sensitivePathProtection: Boolean = DEFAULT_SENSITIVE_PATH_PROTECTION,
    val contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW_SIZE,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val DEFAULT_SENSITIVE_PATH_PROTECTION = false
        const val DEFAULT_CONTEXT_WINDOW_SIZE = 1_000_000
    }
}

object AppConfigLoader {
    fun load(env: Map<String, String> = System.getenv()): AppConfig =
        load(configFile = defaultConfigFile(env), env = env)

    internal fun load(configFile: Path, env: Map<String, String> = System.getenv()): AppConfig {
        val props = Properties()
        if (Files.exists(configFile)) {
            StringReader(Files.readString(configFile, StandardCharsets.UTF_8).removePrefix("\uFEFF")).use {
                props.load(it)
            }
        }

        val apiKey = env["DEEPSEEK_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: props.getProperty("deepseek.api.key")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException(
                "Missing DeepSeek API key. Set DEEPSEEK_API_KEY or create $configFile with deepseek.api.key."
            )

        val baseUrl = props.getProperty("deepseek.base.url")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: AppConfig.DEFAULT_BASE_URL

        val model = props.getProperty("deepseek.model")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: AppConfig.DEFAULT_MODEL

        val sensitivePathProtection = props.getProperty("deepseek.sensitive.path.protection")?.trim()
            ?.toBooleanStrictOrNull()
            ?: AppConfig.DEFAULT_SENSITIVE_PATH_PROTECTION

        val contextWindowSize = props.getProperty("deepseek.context.window.size")?.trim()
            ?.toIntOrNull()
            ?: AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE

        return AppConfig(
            apiKey = apiKey,
            baseUrl = baseUrl.trimEnd('/'),
            model = model,
            sensitivePathProtection = sensitivePathProtection,
            contextWindowSize = contextWindowSize,
        )
    }

    private fun defaultConfigFile(env: Map<String, String>): Path {
        val osName = System.getProperty("os.name").lowercase()
        val userHome = System.getProperty("user.home")?.trim()?.takeIf { it.isNotEmpty() }

        val configDir = when {
            osName.contains("win") -> {
                env["APPDATA"]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
                    ?: env["USERPROFILE"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { Path.of(it, "AppData", "Roaming") }
                    ?: userHome?.let { Path.of(it, "AppData", "Roaming") }
            }
            osName.contains("mac") -> userHome?.let { Path.of(it, "Library", "Application Support") }
            else -> {
                env["XDG_CONFIG_HOME"]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
                    ?: userHome?.let { Path.of(it, ".config") }
            }
        } ?: throw IllegalStateException("Cannot resolve user config directory for KZAgent.")

        return configDir.resolve("kzagent").resolve("config.properties")
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

