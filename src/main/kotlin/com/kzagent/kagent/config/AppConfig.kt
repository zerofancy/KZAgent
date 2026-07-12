package com.kzagent.kagent.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir

data class AppConfig(
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val sensitivePathProtection: Boolean = DEFAULT_SENSITIVE_PATH_PROTECTION,
    val contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW_SIZE,
    val userPrompt: String = "",
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-pro"
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

        val userPrompt = props.getProperty("deepseek.user.prompt")?.trim() ?: ""

        return AppConfig(
            apiKey = apiKey,
            baseUrl = baseUrl.trimEnd('/'),
            model = model,
            sensitivePathProtection = sensitivePathProtection,
            contextWindowSize = contextWindowSize,
            userPrompt = userPrompt,
        )
    }

    internal fun defaultConfigFile(@Suppress("UNUSED_PARAMETER") env: Map<String, String> = System.getenv()): Path =
        FileKitPaths.filesDir().resolve("config.properties")
}

object ConfigWriter {
    fun save(config: AppConfig) {
        val configFile = AppConfigLoader.defaultConfigFile()
        save(configFile, config)
    }

    internal fun save(configFile: Path, config: AppConfig) {
        Files.createDirectories(configFile.parent)
        val content = buildString {
            appendLine("deepseek.api.key=${config.apiKey}")
            appendLine("deepseek.base.url=${config.baseUrl}")
            appendLine("deepseek.model=${config.model}")
            appendLine("deepseek.sensitive.path.protection=${config.sensitivePathProtection}")
            appendLine("deepseek.context.window.size=${config.contextWindowSize}")
            if (config.userPrompt.isNotBlank()) {
                appendLine("deepseek.user.prompt=${escapePropertyValue(config.userPrompt)}")
            }
        }
        Files.writeString(
            configFile,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun escapePropertyValue(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
}

object AppDataDir {
    /** FileKit-managed application data directory for the current platform. */
    fun appDir(): Path = FileKitPaths.filesDir()

    fun sessionsRoot(): Path = appDir().resolve("sessions")

    fun ensureSessionsRoot(): Path = sessionsRoot().also(Files::createDirectories)

    /** Fixed-length, collision-resistant sessions directory for a workspace. */
    fun sessionsDir(workspace: Path): Path = sessionsDir(workspace, appDir())

    internal fun sessionsDir(workspace: Path, appDir: Path): Path =
        appDir.resolve("sessions").resolve(workspaceKey(workspace))

    /** Creates and returns the sessions directory for a workspace. */
    fun ensureSessionsDir(workspace: Path): Path = ensureSessionsDir(workspace, appDir())

    internal fun ensureSessionsDir(workspace: Path, appDir: Path): Path {
        val destination = sessionsDir(workspace, appDir)
        Files.createDirectories(destination)
        return destination
    }

    internal fun workspaceKey(workspace: Path): String {
        val normalized = runCatching { workspace.toRealPath() }
            .getOrElse { workspace.toAbsolutePath().normalize() }
            .toString()
            .let { if (System.getProperty("os.name").lowercase().contains("win")) it.lowercase(Locale.ROOT) else it }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8))
        val hash = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val label = workspace.fileName?.toString().orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(40)
            .ifBlank { "workspace" }
        return "$label-$hash"
    }
}

object FileKitPaths {
    private const val APP_ID = "kzagent"
    @Volatile private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                FileKit.init(appId = APP_ID)
                initialized = true
            }
        }
    }

    fun filesDir(): Path {
        initialize()
        return FileKit.filesDir.file.toPath().toAbsolutePath().normalize()
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
