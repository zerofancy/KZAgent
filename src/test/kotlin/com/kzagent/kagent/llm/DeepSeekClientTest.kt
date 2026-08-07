package com.kzagent.kagent.llm

import com.kzagent.kagent.config.AppConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekClientTest {
    @Test
    fun scopedInstructionIsSentAsSystemMessageWithSourceAndScope() {
        val message = AgentMessage.ScopedInstruction(
            sourcePath = "src/AGENTS.md",
            scopePath = "src",
            content = "source guidance",
        )

        val json = message.toDeepSeekJson()
        val content = json["content"].toString()

        assertEquals("\"system\"", json["role"].toString())
        assertTrue("src/AGENTS.md" in content)
        assertTrue("Applies to: src" in content)
        assertTrue("source guidance" in content)
    }

    @Test
    fun streamingClientSendsExpectedRequestAndMapsResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"choices":[{"delta":{"content":"hello "}}]}

                        data: {"choices":[{"delta":{"content":"world","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]}}],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}

                        data: [DONE]
                        """.trimIndent(),
                    ),
            )
            val client = DeepSeekClient(testConfig(server))
            val tool = buildJsonObject { put("type", "function") }

            val partials = mutableListOf<String>()
            val reply = client.chatStreaming(
                messages = listOf(AgentMessage.User("hello")),
                tools = listOf(tool),
                onPartialContent = partials::add,
            )

            assertEquals(listOf("hello ", "world"), partials)
            assertEquals("hello world", reply.content)
            assertEquals("read_file", reply.toolCalls.single().name)
            assertEquals(12, reply.promptTokens)
            assertEquals(15, reply.totalTokens)
            val request = server.takeRequest()
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer sk-network-test-secret", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("deepseek-test", body["model"]?.jsonPrimitive?.content)
            assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
            assertTrue(body["stream"]?.jsonPrimitive?.content == "true")
            assertEquals("user", body["messages"]?.jsonArray?.single()?.jsonObject
                ?.get("role")?.jsonPrimitive?.content)
            assertEquals(1, body["tools"]?.jsonArray?.size)
        }
    }

    @Test
    fun httpErrorsAreBoundedRedactedAndNotRetried() = runBlocking {
        MockWebServer().use { server ->
            val secret = "sk-abcdefghijklmnopqrstuvwxyz"
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setBody("$secret ${"x".repeat(20_000)}"),
            )
            val error = assertFailsWith<DeepSeekException> {
                DeepSeekClient(testConfig(server)).chat(listOf(AgentMessage.User("hello")), emptyList())
            }

            assertEquals(503, error.statusCode)
            assertFalse(error.message.orEmpty().contains(secret))
            assertContains(error.message.orEmpty(), "***REDACTED***")
            assertContains(error.message.orEmpty(), "[truncated]")
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", "/redirected"),
            )

            val error = assertFailsWith<DeepSeekException> {
                DeepSeekClient(testConfig(server)).chat(listOf(AgentMessage.User("hello")), emptyList())
            }

            assertEquals(307, error.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun emptyAndMalformedSuccessfulResponsesFailClearly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[]}\n\ndata: [DONE]\n"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(204),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":\n\ndata: [DONE]\n"),
            )
            val client = DeepSeekClient(testConfig(server))
            val messages = listOf(AgentMessage.User("hello"))

            assertContains(
                assertFailsWith<DeepSeekException> { client.chat(messages, emptyList()) }.message.orEmpty(),
                "no choices",
            )
            assertContains(
                assertFailsWith<DeepSeekException> { client.chat(messages, emptyList()) }.message.orEmpty(),
                "ended before [DONE]",
            )
            assertContains(
                assertFailsWith<DeepSeekException> { client.chat(messages, emptyList()) }.message.orEmpty(),
                "malformed streaming data",
            )
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun cancellingCoroutineCancelsStreamingCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                DeepSeekClient(testConfig(server)).chat(listOf(AgentMessage.User("hello")), emptyList())
            }
            assertTrue(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) != null)

            withTimeout(5_000) {
                request.cancelAndJoin()
            }

            assertTrue(request.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    private fun testConfig(server: MockWebServer): AppConfig = AppConfig(
        apiKey = "sk-network-test-secret",
        baseUrl = server.url("/").toString(),
        model = "deepseek-test",
    )
}
