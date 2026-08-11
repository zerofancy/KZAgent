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
import com.kzagent.kagent.tools.ApprovalMode

data class AppConfig(
    val deepSeek: ProviderConfig? = null,
    val openRouter: ProviderConfig? = null,
    val defaultModel: ModelSelection = ModelSelection(ProviderId.DEEPSEEK, DEFAULT_MODEL, DEFAULT_CONTEXT_WINDOW_SIZE),
    val sensitivePathProtection: Boolean = DEFAULT_SENSITIVE_PATH_PROTECTION,
    val contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW_SIZE,
    val userPrompt: String = "",
    val approvalMode: ApprovalMode = DEFAULT_APPROVAL_MODE,
) {
    init {
        require(deepSeek != null || openRouter != null) { "At least one model provider must be configured." }
        require(provider(defaultModel.provider) != null) { "The default model provider is not configured." }
        require(contextWindowSize > 0) { "Context window size must be a positive integer." }
    }

    /** Compatibility constructor for callers that still construct a DeepSeek-only config. */
    constructor(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        model: String = DEFAULT_MODEL,
        sensitivePathProtection: Boolean = DEFAULT_SENSITIVE_PATH_PROTECTION,
        contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW_SIZE,
        userPrompt: String = "",
        approvalMode: ApprovalMode = DEFAULT_APPROVAL_MODE,
    ) : this(
        deepSeek = ProviderConfig(apiKey, baseUrl),
        defaultModel = ModelSelection(ProviderId.DEEPSEEK, model, contextWindowSize),
        sensitivePathProtection = sensitivePathProtection,
        contextWindowSize = contextWindowSize,
        userPrompt = userPrompt,
        approvalMode = approvalMode,
    )

    fun provider(id: ProviderId): ProviderConfig? = when (id) {
        ProviderId.DEEPSEEK -> deepSeek
        ProviderId.OPENROUTER -> openRouter
    }

    val configuredProviders: List<ProviderId>
        get() = ProviderId.entries.filter { provider(it) != null }

    // Source-compatible accessors for the existing DeepSeek-focused call sites.
    val apiKey: String get() = deepSeek?.apiKey.orEmpty()
    val baseUrl: String get() = deepSeek?.baseUrl ?: DEFAULT_BASE_URL
    val model: String get() = defaultModel.modelId

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_MODEL = "deepseek-v4-pro"
        const val DEFAULT_SENSITIVE_PATH_PROTECTION = false
        const val DEFAULT_CONTEXT_WINDOW_SIZE = 1_000_000
        val DEFAULT_APPROVAL_MODE = ApprovalMode.AUTO
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

        fun provider(prefix: String, envKey: String, defaultBaseUrl: String): ProviderConfig? {
            val key = env[envKey]?.trim()?.takeIf(String::isNotEmpty)
                ?: props.getProperty("$prefix.api.key")?.trim()?.takeIf(String::isNotEmpty)
                ?: return null
            val baseUrl = props.getProperty("$prefix.base.url")?.trim()?.takeIf(String::isNotEmpty)
                ?: defaultBaseUrl
            return ProviderConfig(key, baseUrl.trimEnd('/'))
        }

        val deepSeek = provider("deepseek", "DEEPSEEK_API_KEY", AppConfig.DEFAULT_BASE_URL)
        val openRouter = provider("openrouter", "OPENROUTER_API_KEY", AppConfig.DEFAULT_OPENROUTER_BASE_URL)
        if (deepSeek == null && openRouter == null) {
            throw IllegalStateException(
                "Missing model provider API key. Set DEEPSEEK_API_KEY, OPENROUTER_API_KEY, " +
                    "or configure deepseek.api.key/openrouter.api.key in $configFile."
            )
        }

        val legacyModel = props.getProperty("deepseek.model")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: AppConfig.DEFAULT_MODEL

        val sensitivePathProtection = (
            props.getProperty("kzagent.sensitive.path.protection")
                ?: props.getProperty("deepseek.sensitive.path.protection")
            )?.trim()
            ?.toBooleanStrictOrNull()
            ?: AppConfig.DEFAULT_SENSITIVE_PATH_PROTECTION

        val contextWindowSize = (
            props.getProperty("kzagent.context.window.size")
                ?: props.getProperty("deepseek.context.window.size")
            )
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                raw.toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "kzagent.context.window.size must be a positive integer.",
                    )
            }
            ?: AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE

        val userPrompt = (props.getProperty("kzagent.user.prompt")
            ?: props.getProperty("deepseek.user.prompt"))?.trim() ?: ""
        val approvalMode = ApprovalMode.fromConfig(props.getProperty("kzagent.approval.mode"))

        val defaultProviderValue = props.getProperty("kzagent.default.provider")?.trim()?.takeIf(String::isNotEmpty)
        val configuredDefaultProvider = ProviderId.fromConfig(defaultProviderValue)
        if (defaultProviderValue != null && configuredDefaultProvider == null) {
            throw IllegalArgumentException("Unknown kzagent.default.provider: $defaultProviderValue")
        }
        if (configuredDefaultProvider != null && when (configuredDefaultProvider) {
                ProviderId.DEEPSEEK -> deepSeek == null
                ProviderId.OPENROUTER -> openRouter == null
            }
        ) {
            throw IllegalArgumentException(
                "kzagent.default.provider ${configuredDefaultProvider.configValue} is not configured.",
            )
        }
        val defaultProvider = configuredDefaultProvider
            ?: if (deepSeek != null) ProviderId.DEEPSEEK else ProviderId.OPENROUTER
        val defaultModelId = props.getProperty("kzagent.default.model")?.trim()?.takeIf(String::isNotEmpty)
            ?: if (defaultProvider == ProviderId.DEEPSEEK) legacyModel else "openrouter/auto"
        val defaultContext = props.getProperty("kzagent.default.context.window.size")?.trim()?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: contextWindowSize
        val supportsToolChoice = props.getProperty("kzagent.default.supports.tool.choice")
            ?.trim()?.toBooleanStrictOrNull() ?: true

        return AppConfig(
            deepSeek = deepSeek,
            openRouter = openRouter,
            defaultModel = ModelSelection(defaultProvider, defaultModelId, defaultContext, supportsToolChoice),
            sensitivePathProtection = sensitivePathProtection,
            contextWindowSize = contextWindowSize,
            userPrompt = userPrompt,
            approvalMode = approvalMode,
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
            config.deepSeek?.let {
                appendLine("deepseek.api.key=${it.apiKey}")
                appendLine("deepseek.base.url=${it.baseUrl}")
            }
            config.openRouter?.let {
                appendLine("openrouter.api.key=${it.apiKey}")
                appendLine("openrouter.base.url=${it.baseUrl}")
            }
            appendLine("kzagent.default.provider=${config.defaultModel.provider.configValue}")
            appendLine("kzagent.default.model=${config.defaultModel.modelId}")
            config.defaultModel.contextWindowSize?.let {
                appendLine("kzagent.default.context.window.size=$it")
            }
            appendLine("kzagent.default.supports.tool.choice=${config.defaultModel.supportsToolChoice}")
            appendLine("kzagent.sensitive.path.protection=${config.sensitivePathProtection}")
            appendLine("kzagent.context.window.size=${config.contextWindowSize}")
            appendLine("kzagent.approval.mode=${config.approvalMode.configValue}")
            if (config.userPrompt.isNotBlank()) {
                appendLine("kzagent.user.prompt=${escapePropertyValue(config.userPrompt)}")
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
