package com.kzagent.kagent.mcp

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import kotlin.test.*

class McpHttpTest {
    @Test fun streamableHttpAcceptsJsonAndSseAndPreservesEndpoint() = runBlocking {
        for (sse in listOf(false, true)) {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "GET") return MockResponse().setResponseCode(405)
                        if (request.method == "DELETE") return MockResponse().setResponseCode(200)
                        assertEquals("/custom/mcp?tenant=test", request.path)
                        assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
                        val message = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val id = message["id"] ?: return MockResponse().setResponseCode(202)
                        val result = when (message["method"]?.jsonPrimitive?.content) {
                            "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"http-fixture","version":"1"}}"""
                            "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object","additionalProperties":false}}]}"""
                            else -> """{"content":[{"type":"text","text":"http-ok"}]}"""
                        }
                        val body = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
                        return MockResponse().setHeader("Mcp-Session-Id", "fixture-session")
                            .setHeader("Content-Type", if (sse) "text/event-stream" else "application/json")
                            .setBody(if (sse) "event: message\ndata: $body\n\n" else body)
                    }
                }
                server.start()
                val config = McpServerConfig(type = "streamable_http", url = server.url("/custom/mcp?tenant=test").toString(), headers = mapOf("Authorization" to "Bearer fixture-token"))
                McpConnection("http", config, Files.createTempDirectory("mcp-http")).use { connection ->
                    assertEquals(1, connection.discover().size)
                    assertContains(connection.call("echo", JsonObject(emptyMap())).first, "http-ok")
                }
            }
        }
    }
}

class McpHttpFailureTest {
    @Test fun authorizationFailureShowsTheHttpStatus(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.method == "DELETE") MockResponse().setResponseCode(200) else MockResponse().setResponseCode(401)
            }
            server.start()
            val dir = Files.createTempDirectory("mcp-auth")
            val store = McpConfigStore(dir.resolve("mcp.json"))
            store.update { McpConfig(mapOf("auth" to McpServerConfig(type = "streamable_http", url = server.url("/mcp").toString(), enabled = true))) }
            McpRuntime(dir, com.kzagent.kagent.tools.AlwaysDenyPolicy, store).use { runtime ->
                runtime.prepareTurn(100000)
                assertContains(runtime.state.value.getValue("auth").error.orEmpty(), "401")
            }
        }
    }

    @Test fun expiredSessionNeverReplaysBusinessCalls(): Unit = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") return MockResponse().setResponseCode(405)
                    if (request.method == "DELETE") return MockResponse().setResponseCode(200)
                    val message = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val id = message["id"] ?: return MockResponse().setResponseCode(202)
                    val result = when (message["method"]?.jsonPrimitive?.content) {
                        "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"expired","version":"1"}}"""
                        "tools/list" -> """{"tools":[{"name":"write","inputSchema":{"type":"object"}}]}"""
                        else -> { calls.incrementAndGet(); return MockResponse().setResponseCode(404) }
                    }
                    return MockResponse().setHeader("Mcp-Session-Id", "expired-session").setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                }
            }
            server.start()
            val config = McpServerConfig(type = "streamable_http", url = server.url("/mcp").toString(), callTimeoutSeconds = 2)
            McpConnection("expired", config, Files.createTempDirectory("mcp-expired")).use { connection ->
                connection.discover()
                assertFails { connection.call("write", JsonObject(emptyMap())) }
                assertFalse(connection.connected)
                assertEquals(1, calls.get())
            }
        }
    }
}
