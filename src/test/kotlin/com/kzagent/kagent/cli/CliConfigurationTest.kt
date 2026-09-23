package com.kzagent.kagent.cli

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.AppConfigLoader
import com.kzagent.kagent.config.ConfigWriter
import com.kzagent.kagent.config.MissingProviderConfigurationException
import com.kzagent.kagent.config.ProviderKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliConfigurationTest {
    @Test
    fun firstRunSavesConfigurationThatTheRuntimeCanReload() {
        val root = Files.createTempDirectory("cli-setup-test")
        val file = root.resolve("config.json")
        try {
            val answers = ArrayDeque(listOf("", "", "", "test-placeholder"))
            val output = mutableListOf<String>()
            val secretInputs = mutableListOf<Boolean>()
            ensureCliConfiguration(
                load = { AppConfigLoader.load(file, emptyMap()) },
                save = { ConfigWriter.save(file, it) },
                readInput = { secret -> secretInputs.add(secret); answers.removeFirst() },
                output = output::add,
            )
            val loaded = AppConfigLoader.load(file, emptyMap())
            assertEquals(ProviderKind.DEEPSEEK, loaded.providers.single().kind)
            assertEquals(AppConfig.DEFAULT_MODEL, loaded.defaultModel.modelId)
            assertEquals(listOf(false, false, false, true), secretInputs)
            assertFalse(output.any { "test-placeholder" in it })
            ensureCliConfiguration(
                load = { AppConfigLoader.load(file, emptyMap()) },
                save = { error("已有配置不应重写") },
                readInput = { error("已有配置不应询问") },
                output = { error("已有配置不应显示设置") },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidChoicesAndEmptyFieldsRetryAndCustomProviderIsSupported() {
        val answers = ArrayDeque(listOf("9", "4", "invalid", "https://example.com/v1/", "", "custom-model", "", "test-placeholder"))
        var saved: AppConfig? = null
        ensureCliConfiguration(
            load = { throw MissingProviderConfigurationException() },
            save = { saved = it },
            readInput = { answers.removeFirst() },
            output = {},
        )
        assertEquals(ProviderKind.OPENAI_COMPATIBLE, saved!!.providers.single().kind)
        assertEquals("https://example.com/v1", saved!!.providers.single().baseUrl)
        assertEquals("custom-model", saved!!.defaultModel.modelId)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun cancellationAndEofNeverSave() {
        for (lastInput in listOf(null, "/cancel")) {
            var reads = 0
            assertFailsWith<IllegalStateException> {
                ensureCliConfiguration(
                    load = { throw MissingProviderConfigurationException() },
                    save = { error("取消不应保存") },
                    readInput = { if (++reads <= 3) "" else lastInput },
                    output = {},
                )
            }
            assertEquals(4, reads)
        }
    }

    @Test
    fun malformedConfigurationAndSaveFailuresArePropagated() {
        assertFailsWith<IllegalArgumentException> {
            ensureCliConfiguration(
                load = { throw IllegalArgumentException("invalid config") },
                save = { error("损坏配置不应覆盖") },
                readInput = { error("损坏配置不应进入首次设置") },
                output = {},
            )
        }
        val answers = ArrayDeque(listOf("3", "", "", "test-placeholder"))
        val output = mutableListOf<String>()
        assertFailsWith<java.io.IOException> {
            ensureCliConfiguration(
                load = { throw MissingProviderConfigurationException() },
                save = { throw java.io.IOException("write failed") },
                readInput = { answers.removeFirst() },
                output = output::add,
            )
        }
        assertFalse(output.any { "配置已保存" in it })
    }
}
