package com.kzagent.kagent.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AppConfigLoaderTest {
    @Test
    fun loadsLocalProperties() {
        val dir = Files.createTempDirectory("kagent-config-test")
        Files.writeString(
            dir.resolve("local.properties"),
            """
            deepseek.api.key=sk-test-local
            deepseek.model=deepseek-v4-flash
            deepseek.base.url=https://api.deepseek.com
            """.trimIndent(),
        )

        val config = AppConfigLoader.load(dir, emptyMap())

        assertEquals("sk-test-local", config.apiKey)
        assertEquals("deepseek-v4-flash", config.model)
        assertEquals("https://api.deepseek.com", config.baseUrl)
    }

    @Test
    fun fallsBackToEnvironmentKey() {
        val dir = Files.createTempDirectory("kagent-config-env-test")

        val config = AppConfigLoader.load(dir, mapOf("DEEPSEEK_API_KEY" to "sk-test-env"))

        assertEquals("sk-test-env", config.apiKey)
        assertEquals(AppConfig.DEFAULT_MODEL, config.model)
    }

    @Test
    fun failsWhenKeyIsMissing() {
        val dir = Files.createTempDirectory("kagent-config-missing-test")

        assertFailsWith<IllegalStateException> {
            AppConfigLoader.load(dir, emptyMap())
        }
    }

    @Test
    fun redactsSecrets() {
        val redacted = SecretRedactor.redact("Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz")

        assertFalse(redacted.contains("sk-abcdefghijklmnopqrstuvwxyz"))
    }
}

