package com.kzagent.kagent.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.kzagent.kagent.tools.ApprovalMode

class AppConfigLoaderTest {
    private fun jsonFile(dir: java.nio.file.Path): java.nio.file.Path =
        dir.resolve("config.json")

    private fun deepseekConfig(apiKey: String = "sk-test-local"): ProviderConfig =
        ProviderConfig("deepseek", "DeepSeek", ProviderKind.DEEPSEEK, apiKey, "https://api.deepseek.com")

    @Test
    fun legacyPropertiesAutoMigrateToJsonAndRoundTrip() {
        val dir = Files.createTempDirectory("kagent-config-migrate-test")
        val legacy = dir.resolve("config.properties")
        Files.writeString(
            legacy,
            """
            deepseek.api.key=sk-test-local
            deepseek.model=deepseek-v4-pro
            deepseek.base.url=https://api.deepseek.com/
            """.trimIndent(),
        )

        val config = AppConfigLoader.load(jsonFile(dir), emptyMap())

        assertEquals(1, config.providers.size)
        assertEquals("sk-test-local", config.providers.single().apiKey)
        assertEquals("deepseek-v4-pro", config.defaultModel.modelId)
        assertEquals("deepseek", config.defaultModel.provider)
        // The migrated JSON config file is written for future loads.
        assertTrue(Files.exists(jsonFile(dir)))
    }

    @Test
    fun jsonConfigRoundTripsMultipleProvidersAndDefaultSelection() {
        val dir = Files.createTempDirectory("kagent-config-json-test")
        val configFile = jsonFile(dir)
        val original = AppConfig(
            providers = listOf(
                deepseekConfig(),
                ProviderConfig("custom", "My Provider", ProviderKind.OPENAI_COMPATIBLE, "sk-custom", "https://api.example.com/v1"),
            ),
            defaultModel = ModelSelection("custom", "gpt-4.1", 128_000, true),
            approvalMode = ApprovalMode.FULL,
            userPrompt = "Follow the spec.",
        )

        ConfigWriter.save(configFile, original)
        val loaded = AppConfigLoader.load(configFile, emptyMap())

        assertEquals(2, loaded.providers.size)
        assertEquals("My Provider", loaded.provider("custom")?.name)
        assertEquals("gpt-4.1", loaded.defaultModel.modelId)
        assertEquals("custom", loaded.defaultModel.provider)
        assertEquals(ApprovalMode.FULL, loaded.approvalMode)
        assertEquals("Follow the spec.", loaded.userPrompt)
    }

    @Test
    fun environmentKeyFillsMissingDeepSeekProvider() {
        val dir = Files.createTempDirectory("kagent-config-env-test")
        val configFile = jsonFile(dir)
        Files.writeString(
            configFile,
            """
            {"providers":[],"defaultModel":{"provider":"deepseek","modelId":"deepseek-v4-pro"}}
            """.trimIndent(),
        )

        val config = AppConfigLoader.load(configFile, mapOf("DEEPSEEK_API_KEY" to "sk-test-env"))

        assertEquals("sk-test-env", config.provider("deepseek")?.apiKey)
    }

    @Test
    fun openRouterEnvironmentKeyCanBeTheOnlyCredential() {
        val configFile = jsonFile(Files.createTempDirectory("kagent-openrouter-env-test"))
        val loaded = AppConfigLoader.load(configFile, mapOf("OPENROUTER_API_KEY" to "sk-or-env-secret"))

        assertEquals("openrouter", loaded.defaultModel.provider)
        assertEquals("openrouter/auto", loaded.defaultModel.modelId)
    }

    @Test
    fun rejectsDefaultProviderWithoutCredentials() {
        val dir = Files.createTempDirectory("kagent-invalid-default-provider-test")
        Files.writeString(
            jsonFile(dir),
            """
            {"providers":[{"id":"deepseek","name":"DeepSeek","kind":"DEEPSEEK","apiKey":"sk-test-local","baseUrl":"https://api.deepseek.com"}],
             "defaultModel":{"provider":"custom","modelId":"vendor/model"}}
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> {
            AppConfigLoader.load(jsonFile(dir), emptyMap())
        }
    }

    @Test
    fun usesDefaultsWithEnvironmentKeyOnly() {
        val configFile = jsonFile(Files.createTempDirectory("kagent-config-default-test"))
        val config = AppConfigLoader.load(configFile, mapOf("DEEPSEEK_API_KEY" to "sk-test-env"))

        assertEquals("sk-test-env", config.apiKey)
        assertEquals(AppConfig.DEFAULT_MODEL, config.model)
        assertEquals(AppConfig.DEFAULT_BASE_URL, config.baseUrl)
        assertEquals(ApprovalMode.AUTO, config.approvalMode)
    }

    @Test
    fun failsWhenKeyIsMissing() {
        val dir = Files.createTempDirectory("kagent-config-missing-test")
        val error = assertFailsWith<IllegalStateException> {
            AppConfigLoader.load(jsonFile(dir), emptyMap())
        }
        assertContains(error.message.orEmpty(), "Missing model provider API key")
    }

    @Test
    fun rejectsInvalidContextWindowSizes() {
        val dir = Files.createTempDirectory("kagent-invalid-context-test")
        Files.writeString(
            jsonFile(dir),
            """
            {"providers":[{"id":"deepseek","name":"DeepSeek","kind":"DEEPSEEK","apiKey":"sk-test-local","baseUrl":"https://api.deepseek.com"}],
             "contextWindowSize":0}
            """.trimIndent(),
        )
        assertFailsWith<IllegalArgumentException> {
            AppConfigLoader.load(jsonFile(dir), emptyMap())
        }
    }

    @Test
    fun approvalModeRoundTripsAcrossJson() {
        val dir = Files.createTempDirectory("kagent-approval-config-test")
        val configFile = jsonFile(dir)
        val original = AppConfig(providers = listOf(deepseekConfig()), approvalMode = ApprovalMode.FULL)

        ConfigWriter.save(configFile, original)
        assertEquals(ApprovalMode.FULL, AppConfigLoader.load(configFile, emptyMap()).approvalMode)
    }

    @Test
    fun userPromptRoundTrips() {
        val dir = Files.createTempDirectory("kagent-prompt-roundtrip-test")
        val configFile = jsonFile(dir)
        val prompt = """Use C:\Users\name and regex \d+\s+\w+.
Keep the literal text \n unchanged.
This is a real second line."""
        val original = AppConfig(providers = listOf(deepseekConfig()), userPrompt = prompt)

        ConfigWriter.save(configFile, original)
        val loaded = AppConfigLoader.load(configFile, emptyMap())

        assertEquals(prompt, loaded.userPrompt)
    }

    @Test
    fun workspaceSessionKeysAreFixedLengthAndDoNotCollideForSanitizedPaths() {
        val root = Files.createTempDirectory("kagent-workspace-key-test")
        val first = root.resolve("a_b").resolve("c")
        val second = root.resolve("a").resolve("b_c")

        val firstKey = AppDataDir.workspaceKey(first)
        val secondKey = AppDataDir.workspaceKey(second)

        assertNotEquals(firstKey, secondKey)
        assertTrue(firstKey.length <= 73)
        assertTrue(secondKey.length <= 73)
    }

    @Test
    fun redactsSecrets() {
        val redacted = SecretRedactor.redact("Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz")

        assertFalse(redacted.contains("sk-abcdefghijklmnopqrstuvwxyz"))
    }
}
