package com.kzagent.kagent.llm

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.ProviderConfig
import com.kzagent.kagent.config.ProviderId
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
            val config = AppConfig(
                deepSeek = ProviderConfig("sk-test-secret", server.url("/").toString()),
                defaultModel = ModelSelection(ProviderId.DEEPSEEK, "deepseek-v4-pro", 123_456),
                contextWindowSize = 123_456,
            )

            val models = ModelCatalogService().loadProvider(config, ProviderId.DEEPSEEK)

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
            val config = AppConfig(
                openRouter = ProviderConfig("sk-or-test-secret", server.url("/api/v1").toString()),
                defaultModel = ModelSelection(ProviderId.OPENROUTER, "vendor/agent"),
            )

            val models = ModelCatalogService().loadProvider(config, ProviderId.OPENROUTER)

            assertEquals(listOf("vendor/agent"), models.map { it.id })
            assertEquals(200_000, models.single().contextWindowSize)
            assertTrue(models.single().supportsToolChoice)
            val request = server.takeRequest()
            assertEquals("/api/v1/models?supported_parameters=tools", request.path)
            assertEquals("Bearer sk-or-test-secret", request.getHeader("Authorization"))
            assertFalse(request.body.readUtf8().contains("sk-or-test-secret"))
        }
    }
}
