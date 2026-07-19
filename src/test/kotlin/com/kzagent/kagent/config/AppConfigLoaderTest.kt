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
    @Test
    fun loadsUserConfigProperties() {
        val dir = Files.createTempDirectory("kagent-config-test")
        val configFile = dir.resolve("kzagent").resolve("config.properties")
        Files.createDirectories(configFile.parent)
        Files.writeString(
            configFile,
            """
            deepseek.api.key=sk-test-local
            deepseek.model=deepseek-v4-pro
            deepseek.base.url=https://api.deepseek.com/
            """.trimIndent(),
        )

        val config = AppConfigLoader.load(configFile, emptyMap())

        assertEquals("sk-test-local", config.apiKey)
        assertEquals("deepseek-v4-pro", config.model)
        assertEquals("https://api.deepseek.com", config.baseUrl)
    }

    @Test
    fun loadsConfigPropertiesWithUtf8Bom() {
        val dir = Files.createTempDirectory("kagent-config-bom-test")
        val configFile = dir.resolve("kzagent").resolve("config.properties")
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, "\uFEFFdeepseek.api.key=sk-test-local")

        val config = AppConfigLoader.load(configFile, emptyMap())

        assertEquals("sk-test-local", config.apiKey)
    }

    @Test
    fun environmentKeyOverridesConfigFile() {
        val dir = Files.createTempDirectory("kagent-config-env-test")
        val configFile = dir.resolve("kzagent").resolve("config.properties")
        Files.createDirectories(configFile.parent)
        Files.writeString(
            configFile,
            """
            deepseek.api.key=sk-test-file
            deepseek.model=deepseek-v4-pro
            """.trimIndent(),
        )

        val config = AppConfigLoader.load(configFile, mapOf("DEEPSEEK_API_KEY" to "sk-test-env"))

        assertEquals("sk-test-env", config.apiKey)
        assertEquals("deepseek-v4-pro", config.model)
    }

    @Test
    fun usesDefaultsWithEnvironmentKeyOnly() {
        val configFile = Files.createTempDirectory("kagent-config-default-test")
            .resolve("kzagent")
            .resolve("config.properties")

        val config = AppConfigLoader.load(configFile, mapOf("DEEPSEEK_API_KEY" to "sk-test-env"))

        assertEquals("sk-test-env", config.apiKey)
        assertEquals(AppConfig.DEFAULT_MODEL, config.model)
        assertEquals(AppConfig.DEFAULT_BASE_URL, config.baseUrl)
        assertEquals(ApprovalMode.AUTO, config.approvalMode)
    }

    @Test
    fun failsWhenKeyIsMissing() {
        val dir = Files.createTempDirectory("kagent-config-missing-test")
        val configFile = dir.resolve("kzagent").resolve("config.properties")

        val error = assertFailsWith<IllegalStateException> {
            AppConfigLoader.load(configFile, emptyMap())
        }

        assertContains(error.message.orEmpty(), configFile.toString())
        assertContains(error.message.orEmpty(), "deepseek.api.key")
    }

    @Test
    fun userPromptRoundTripsBackslashesAndNewlines() {
        val configFile = Files.createTempDirectory("kagent-prompt-roundtrip-test")
            .resolve("kzagent")
            .resolve("config.properties")
        val prompt = """Use C:\Users\name and regex \d+\s+\w+.
Keep the literal text \n unchanged.
This is a real second line."""
        val original = AppConfig(apiKey = "sk-test-local", userPrompt = prompt)

        ConfigWriter.save(configFile, original)
        val loaded = AppConfigLoader.load(configFile, emptyMap())

        assertEquals(prompt, loaded.userPrompt)
    }

    @Test
    fun approvalModeRoundTripsAndInvalidValueFallsBackToAuto() {
        val configFile = Files.createTempDirectory("kagent-approval-config-test")
            .resolve("config.properties")
        val original = AppConfig(apiKey = "sk-test-local", approvalMode = ApprovalMode.FULL)

        ConfigWriter.save(configFile, original)
        assertEquals(ApprovalMode.FULL, AppConfigLoader.load(configFile, emptyMap()).approvalMode)

        Files.writeString(
            configFile,
            "deepseek.api.key=sk-test-local\nkzagent.approval.mode=unknown\n",
        )
        assertEquals(ApprovalMode.AUTO, AppConfigLoader.load(configFile, emptyMap()).approvalMode)
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

