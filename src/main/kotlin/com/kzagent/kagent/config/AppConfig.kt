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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AppConfig(
    val providers: List<ProviderConfig> = emptyList(),
    val defaultModel: ModelSelection = ModelSelection(DEFAULT_PROVIDER_ID, DEFAULT_MODEL, DEFAULT_CONTEXT_WINDOW_SIZE),
    val sensitivePathProtection: Boolean = DEFAULT_SENSITIVE_PATH_PROTECTION,
    val contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW_SIZE,
    val userPrompt: String = "",
    val approvalMode: ApprovalMode = DEFAULT_APPROVAL_MODE,
) {
    init {
        require(providers.isNotEmpty()) { "At least one model provider must be configured." }
        require(provider(defaultModel.provider) != null) {
            "The default model provider ${defaultModel.provider} is not configured."
        }
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
        providers = listOf(
            ProviderConfig(
                id = "deepseek",
                name = ProviderKind.DEEPSEEK.displayName,
                kind = ProviderKind.DEEPSEEK,
                apiKey = apiKey,
                baseUrl = baseUrl,
            ),
        ),
        defaultModel = ModelSelection("deepseek", model, contextWindowSize),
        sensitivePathProtection = sensitivePathProtection,
        contextWindowSize = contextWindowSize,
        userPrompt = userPrompt,
        approvalMode = approvalMode,
    )

    fun provider(id: String): ProviderConfig? = providers.firstOrNull { it.id == id }

    /** Alias used by callers iterating over the configured providers. */
    val configuredProviders: List<ProviderConfig>
        get() = providers

    internal fun toDto(): AppConfigDto = AppConfigDto(
        providers = providers,
        defaultModel = defaultModel,
        sensitivePathProtection = sensitivePathProtection,
        contextWindowSize = contextWindowSize,
        userPrompt = userPrompt,
        approvalMode = approvalMode,
    )

    // Source-compatible accessors for the existing DeepSeek-focused call sites.
    val deepSeek: ProviderConfig? get() = providers.firstOrNull { it.kind == ProviderKind.DEEPSEEK }
    val openRouter: ProviderConfig? get() = providers.firstOrNull { it.kind == ProviderKind.OPENROUTER }
    @Deprecated("Use provider(id) or providers instead.")
    val apiKey: String get() = deepSeek?.apiKey.orEmpty()
    @Deprecated("Use provider(id) or providers instead.")
    val baseUrl: String get() = deepSeek?.baseUrl ?: DEFAULT_BASE_URL
    @Deprecated("Use defaultModel.modelId instead.")
    val model: String get() = defaultModel.modelId

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_PROVIDER_ID = "deepseek"
        const val DEFAULT_MODEL = "deepseek-v4-pro"
        const val DEFAULT_MIMO_MODEL = "mimo-v2.5-pro"
        const val DEFAULT_SENSITIVE_PATH_PROTECTION = false
        const val DEFAULT_CONTEXT_WINDOW_SIZE = 1_000_000
        val DEFAULT_APPROVAL_MODE = ApprovalMode.AUTO
    }
}

/**
 * Serialization-friendly mirror of [AppConfig] without invariant checks. Deserializing
 * through this intermediate prevents [AppConfig]'s constructor validation from firing
 * before environment-variable fallback providers are applied.
 */
@Serializable
internal data class AppConfigDto(
    val providers: List<ProviderConfig> = emptyList(),
    val defaultModel: ModelSelection = ModelSelection(
        AppConfig.DEFAULT_PROVIDER_ID,
        AppConfig.DEFAULT_MODEL,
        AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
    ),
    val sensitivePathProtection: Boolean = AppConfig.DEFAULT_SENSITIVE_PATH_PROTECTION,
    val contextWindowSize: Int = AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
    val userPrompt: String = "",
    val approvalMode: ApprovalMode = AppConfig.DEFAULT_APPROVAL_MODE,
) {
    fun toAppConfig(): AppConfig = AppConfig(
        providers = providers,
        defaultModel = defaultModel,
        sensitivePathProtection = sensitivePathProtection,
        contextWindowSize = contextWindowSize,
        userPrompt = userPrompt,
        approvalMode = approvalMode,
    )
}

object JsonConfigCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(config: AppConfig): String = json.encodeToString(config.toDto())

    /** Decodes JSON into the invariant-checked model (used where construction-time validation is desired). */
    fun decode(text: String): AppConfig = decodeDto(text).toAppConfig()

    /** Decodes JSON into the unvalidated DTO, deferring checks until callers apply environment fallbacks. */
    internal fun decodeDto(text: String): AppConfigDto = json.decodeFromString(text)
}

object AppConfigLoader {
    private const val JSON_FILE_NAME = "config.json"
    private const val LEGACY_FILE_NAME = "config.properties"

    fun load(env: Map<String, String> = System.getenv()): AppConfig =
        load(configFile = defaultConfigFile(env), env = env)

    internal fun load(configFile: Path, env: Map<String, String> = System.getenv()): AppConfig {
        val fileName = configFile.fileName.toString()
        return if (fileName.endsWith(".json")) {
            loadJson(configFile, env)
        } else {
            // Backward-compatible path for callers that pass a legacy properties file.
            loadProperties(configFile, env)
        }
    }

    private fun loadJson(configFile: Path, env: Map<String, String>): AppConfig {
        val props = Properties()
        val legacyFile = configFile.resolveSibling(LEGACY_FILE_NAME)
        if (Files.exists(legacyFile)) {
            loadPropertiesInto(legacyFile, props)
        }

        if (Files.exists(configFile) && Files.size(configFile) > 0) {
            val content = Files.readString(configFile, StandardCharsets.UTF_8).removePrefix("\uFEFF")
            val dto = JsonConfigCodec.decodeDto(content)
            return applyEnvironmentFallbacks(dto, props, env).toAppConfig()
        }

        // No JSON file yet: build from legacy properties (auto-migrating) or environment keys.
        val migrated = configFromProperties(props, env)
        writeJsonIfAbsent(configFile, migrated)
        return migrated
    }

    private fun loadProperties(configFile: Path, env: Map<String, String>): AppConfig {
        val props = Properties()
        if (Files.exists(configFile)) {
            loadPropertiesInto(configFile, props)
        }
        return configFromProperties(props, env)
    }

    /** Applies environment-key overrides to a freshly deserialized config DTO. */
    private fun applyEnvironmentFallbacks(config: AppConfigDto, props: Properties, env: Map<String, String>): AppConfigDto {
        // Environment keys only apply when no JSON provider of that kind already has a key.
        val providers = config.providers.toMutableList()
        for (spec in ENV_PROVIDER_SPECS) {
            if (spec.baseUrl.isBlank()) continue
            val keyValue = env[spec.envKey]?.trim()?.takeIf(String::isNotEmpty) ?: continue
            if (providers.none { it.kind == spec.kind }) {
                providers.add(
                    ProviderConfig(
                        id = spec.defaultId,
                        name = spec.kind.displayName,
                        kind = spec.kind,
                        apiKey = keyValue,
                        baseUrl = (props.getProperty("${spec.defaultId}.base.url")
                            ?.trim()?.takeIf(String::isNotEmpty) ?: spec.baseUrl).trimEnd('/'),
                    ),
                )
            }
        }
        return config.copy(providers = providers)
    }

    private fun configFromProperties(props: Properties, env: Map<String, String>): AppConfig {
        val providers = buildList {
            for (spec in ENV_PROVIDER_SPECS) {
                if (spec.baseUrl.isBlank()) continue
                legacyProvider(
                    props,
                    env,
                    prefix = spec.prefix,
                    envKey = spec.envKey,
                    kind = spec.kind,
                    defaultId = spec.defaultId,
                    defaultBaseUrl = spec.baseUrl,
                )?.let { add(it) }
            }
        }
        if (providers.isEmpty()) {
            throw IllegalStateException(
                "Missing model provider API key. Set DEEPSEEK_API_KEY, OPENROUTER_API_KEY, " +
                    "MIMOCODE_API_KEY, or configure a provider in the JSON config file."
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

        val defaultProviderId = props.getProperty("kzagent.default.provider")?.trim()?.takeIf(String::isNotEmpty)
        if (defaultProviderId != null) {
            // Legacy values used the provider config value, which matches our provider ids.
            val ids = providers.map { it.id }
            if (defaultProviderId !in ids) {
                throw IllegalArgumentException(
                    "kzagent.default.provider $defaultProviderId is not configured.",
                )
            }
        }
        val defaultProvider = defaultProviderId ?: providers.first().id
        val defaultModelId = props.getProperty("kzagent.default.model")?.trim()?.takeIf(String::isNotEmpty)
            ?: when (defaultProvider) {
                "openrouter" -> "openrouter/auto"
                "mimocode" -> AppConfig.DEFAULT_MIMO_MODEL
                else -> legacyModel
            }
        val defaultContext = props.getProperty("kzagent.default.context.window.size")?.trim()?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: contextWindowSize
        val supportsToolChoice = props.getProperty("kzagent.default.supports.tool.choice")
            ?.trim()?.toBooleanStrictOrNull() ?: true

        return AppConfig(
            providers = providers,
            defaultModel = ModelSelection(defaultProvider, defaultModelId, defaultContext, supportsToolChoice),
            sensitivePathProtection = sensitivePathProtection,
            contextWindowSize = contextWindowSize,
            userPrompt = userPrompt,
            approvalMode = approvalMode,
        )
    }

    private fun legacyProvider(
        props: Properties,
        env: Map<String, String>,
        prefix: String,
        envKey: String,
        kind: ProviderKind,
        defaultId: String,
        defaultBaseUrl: String,
    ): ProviderConfig? {
        val key = env[envKey]?.trim()?.takeIf(String::isNotEmpty)
            ?: props.getProperty("$prefix.api.key")?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        val baseUrl = props.getProperty("$prefix.base.url")?.trim()?.takeIf(String::isNotEmpty)
            ?: defaultBaseUrl
        return ProviderConfig(
            id = defaultId,
            name = kind.displayName,
            kind = kind,
            apiKey = key,
            baseUrl = baseUrl.trimEnd('/'),
        )
    }

    private class EnvProviderSpec(
        val envKey: String,
        val prefix: String,
        val kind: ProviderKind,
        val defaultId: String,
        val baseUrl: String,
    )

    private val ENV_PROVIDER_SPECS = listOf(
        EnvProviderSpec("DEEPSEEK_API_KEY", "deepseek", ProviderKind.DEEPSEEK, "deepseek", AppConfig.DEFAULT_BASE_URL),
        EnvProviderSpec(
            "OPENROUTER_API_KEY",
            "openrouter",
            ProviderKind.OPENROUTER,
            "openrouter",
            AppConfig.DEFAULT_OPENROUTER_BASE_URL,
        ),
        EnvProviderSpec(
            "MIMOCODE_API_KEY",
            "mimocode",
            ProviderKind.MIMOCODE,
            "mimocode",
            ProviderKind.MIMOCODE.defaultBaseUrl,
        ),
    )

    private fun loadPropertiesInto(configFile: Path, props: Properties) {
        StringReader(Files.readString(configFile, StandardCharsets.UTF_8).removePrefix("\uFEFF")).use {
            props.load(it)
        }
    }

    private fun writeJsonIfAbsent(configFile: Path, config: AppConfig) {
        if (Files.exists(configFile)) return
        Files.createDirectories(configFile.parent)
        Files.writeString(
            configFile,
            JsonConfigCodec.encode(config),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    internal fun defaultConfigFile(env: Map<String, String> = System.getenv()): Path =
        FileKitPaths.filesDir().resolve(JSON_FILE_NAME)

    internal fun legacyConfigFile(): Path = FileKitPaths.filesDir().resolve(LEGACY_FILE_NAME)
}

object ConfigWriter {
    fun save(config: AppConfig) {
        val configFile = AppConfigLoader.defaultConfigFile()
        save(configFile, config)
    }

    internal fun save(configFile: Path, config: AppConfig) {
        Files.createDirectories(configFile.parent)
        Files.writeString(
            configFile,
            JsonConfigCodec.encode(config),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
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
