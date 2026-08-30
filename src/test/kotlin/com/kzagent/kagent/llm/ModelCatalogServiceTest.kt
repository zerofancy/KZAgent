package com.kzagent.kagent.llm

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.ProviderConfig
import com.kzagent.kagent.config.ProviderKind
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogServiceTest {
    @Test
    fun loadsDeepSeekModelsWithConfiguredContextFallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"data":[{"id":"deepseek-v4-pro"},{"id":"deepseek-v4-flash"}]}
            """.trimIndent()))
            val provider = ProviderConfig(
                "deepseek", "DeepSeek", ProviderKind.DEEPSEEK, "sk-test-secret", server.url("/").toString(),
            )
            val config = AppConfig(
                providers = listOf(provider),
                defaultModel = ModelSelection("deepseek", "deepseek-v4-pro", 123_456),
                contextWindowSize = 123_456,
            )

            val models = ModelCatalogService().loadProvider(config, config.providers.single())

            assertEquals(setOf("deepseek-v4-pro", "deepseek-v4-flash"), models.map { it.id }.toSet())
            assertTrue(models.all { it.contextWindowSize == 123_456 })
            assertEquals("/models", server.takeRequest().path)
        }
    }

    @Test
    fun openRouterCatalogKeepsOnlyTextModelsWithToolsAndMapsCapabilities() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"data":[
                  {"id":"vendor/agent","name":"Agent","context_length":200000,
                   "supported_parameters":["tools","tool_choice"],
                   "architecture":{"output_modalities":["text"]}},
                  {"id":"vendor/plain","supported_parameters":[],
                   "architecture":{"output_modalities":["text"]}},
                  {"id":"vendor/image","supported_parameters":["tools"],
                   "architecture":{"output_modalities":["image"]}}
                ]}
            """.trimIndent()))
            val provider = ProviderConfig(
                "openrouter", "OpenRouter", ProviderKind.OPENROUTER, "sk-or-test-secret", server.url("/api/v1").toString(),
            )
            val config = AppConfig(
                providers = listOf(provider),
                defaultModel = ModelSelection("openrouter", "vendor/agent"),
            )

            val models = ModelCatalogService().loadProvider(config, config.providers.single())

            assertEquals(listOf("vendor/agent"), models.map { it.id })
            assertEquals(200_000, models.single().contextWindowSize)
            assertTrue(models.single().supportsToolChoice)
            val request = server.takeRequest()
            assertEquals("/api/v1/models?supported_parameters=tools", request.path)
            assertEquals("Bearer sk-or-test-secret", request.getHeader("Authorization"))
            assertFalse(request.body.readUtf8().contains("sk-or-test-secret"))
        }
    }

    @Test
    fun loadsMiMoModelsFromOpenAiCompatibleModelsEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"object":"list","data":[
                  {"id":"mimo-v2.5-pro","object":"model","owned_by":"xiaomi"},
                  {"id":"mimo-v2.5","object":"model","owned_by":"xiaomi"}
                ]}
            """.trimIndent()))
            val provider = ProviderConfig(
                "mimocode", "MiMo Code", ProviderKind.MIMOCODE, "sk-mimo-secret", server.url("/v1").toString(),
            )
            val config = AppConfig(
                providers = listOf(provider),
                defaultModel = ModelSelection("mimocode", "mimo-v2.5-pro"),
            )

            val models = ModelCatalogService().loadProvider(config, config.providers.single())

            assertEquals(setOf("mimo-v2.5-pro", "mimo-v2.5"), models.map { it.id }.toSet())
            assertTrue(models.all { it.providerName == "MiMo Code" })
            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Bearer sk-mimo-secret", request.getHeader("Authorization"))
        }
    }
}
