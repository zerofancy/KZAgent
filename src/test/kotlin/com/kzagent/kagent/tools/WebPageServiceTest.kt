package com.kzagent.kagent.tools

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import java.net.InetAddress
import java.nio.charset.Charset
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPageServiceTest {
    @Test
    fun staticHtmlIsCleanedAndExtractedByIsolatedSubagent() = runBlocking {
        val html = """
            <!doctype html><html><head><title>Example Page</title><style>.x{}</style></head>
            <body>
              <nav>Navigation noise</nav>
              <main><h1>Useful heading</h1><p>Useful body</p>
                <a href="/next">Next page</a></main>
              <script>RAW_SCRIPT_MUST_NOT_LEAK</script>
            </body></html>
        """.trimIndent()
        val model = CapturingModel(AssistantReply("# Useful heading\n\nUseful body"))
        val service = WebPageService(
            extractor = WebContentExtractor(model),
            transport = FakeTransport(html.toByteArray(), "text/html; charset=utf-8"),
        )

        val result = service.fetch("https://example.com/article")

        assertFalse(result.isError)
        assertContains(result.content, "Title: Example Page")
        assertContains(result.content, "Extraction mode: subagent")
        assertContains(result.content, "# Useful heading")
        assertContains(result.content, "[Next page](https://example.com/next)")
        assertFalse(result.content.contains("RAW_SCRIPT_MUST_NOT_LEAK"))
        assertEquals(emptyList(), model.lastTools)
        assertEquals(2, model.lastMessages.size)
        val extractorInput = (model.lastMessages.last() as AgentMessage.User).content
        assertFalse(extractorInput.contains("RAW_SCRIPT_MUST_NOT_LEAK"))
        assertFalse(extractorInput.contains("Navigation noise"))
        assertContains((model.lastMessages.first() as AgentMessage.System).content, "untrusted")
    }

    @Test
    fun failedSubagentFallsBackToVisibleTextWithoutReturningHtml() = runBlocking {
        val model = object : ChatModel {
            override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
                error("model unavailable")
            }
        }
        val html = "<html><body><main><p>Fallback body</p></main><script>secret()</script></body></html>"
        val service = WebPageService(
            WebContentExtractor(model),
            FakeTransport(html.toByteArray(), "text/html"),
        )

        val result = service.fetch("https://example.com")

        assertFalse(result.isError)
        assertContains(result.content, "Extraction mode: deterministic fallback")
        assertContains(result.content, "Fallback body")
        assertFalse(result.content.contains("<main>"))
        assertFalse(result.content.contains("secret()"))
    }

    @Test
    fun extractorInputAndOutputAreBounded() = runBlocking {
        val oversizedOutput = "o".repeat(WebContentExtractor.MAX_EXTRACTED_CONTENT_CHARS + 100)
        val model = CapturingModel(AssistantReply(oversizedOutput))
        val text = "i".repeat(WebContentExtractor.MAX_EXTRACTOR_INPUT_CHARS + 100)
        val service = WebPageService(
            WebContentExtractor(model),
            FakeTransport(text.toByteArray(), "text/plain; charset=utf-8"),
        )

        val result = service.fetch("https://example.com/large.txt")

        val input = (model.lastMessages.last() as AgentMessage.User).content
        assertTrue(input.length < WebContentExtractor.MAX_EXTRACTOR_INPUT_CHARS + 500)
        val extractedSection = result.content.substringAfter("## Extracted content\n")
        assertEquals(WebContentExtractor.MAX_EXTRACTED_CONTENT_CHARS, extractedSection.length)
    }

    @Test
    fun detectsJavascriptShellAndSupportsDeclaredLegacyCharset() = runBlocking {
        val gb18030 = Charset.forName("GB18030")
        val html = """
            <html><head><meta charset="gb18030"><title>中文标题</title></head>
            <body><div id="root"></div><noscript>请开启 JavaScript</noscript></body></html>
        """.trimIndent()
        val page = FetchedWebPage(
            requestedUrl = "https://example.com",
            finalUrl = "https://example.com",
            statusCode = 200,
            contentType = "text/html",
            mediaType = "text/html".toMediaType(),
            body = html.toByteArray(gb18030),
        )

        val parsed = WebPageParser.parse(page)

        assertEquals("中文标题", parsed.title)
        assertContains(parsed.dynamicPageWarning.orEmpty(), "JavaScript-rendered")
    }

    @Test
    fun jsonXmlAndPlainTextAreAcceptedWhileBinaryIsRejected() {
        assertTrue(WebPageParser.isSupportedContentType("application/json".toMediaType(), "{}".toByteArray()))
        assertTrue(WebPageParser.isSupportedContentType("application/rss+xml".toMediaType(), "<rss/>".toByteArray()))
        assertTrue(WebPageParser.isSupportedContentType("text/plain".toMediaType(), "hello".toByteArray()))
        assertFalse(WebPageParser.isSupportedContentType("image/png".toMediaType(), byteArrayOf(0, 1, 2)))
    }

    @Test
    fun publicAddressPolicyBlocksPrivateReservedAndLocalAddresses() {
        val blocked = listOf(
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "198.51.100.1",
            "224.0.0.1",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
        )
        blocked.forEach { literal ->
            assertFalse(PublicWebAddressPolicy.isPublicAddress(InetAddress.getByName(literal)), literal)
        }
        assertTrue(PublicWebAddressPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(PublicWebAddressPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        assertTrue(PublicWebAddressPolicy.isLocalHostname("localhost"))
        assertTrue(PublicWebAddressPolicy.isLocalHostname("service.internal"))
        assertFalse(PublicWebAddressPolicy.isLocalHostname("example.com"))
    }

    @Test
    fun publicOnlyDnsRejectsAnyHostWithANonPublicAnswer() {
        val dns = PublicOnlyDns(
            StaticDns(listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))),
        )

        val error = runCatching { dns.lookup("example.com") }.exceptionOrNull()

        assertTrue(error is java.net.UnknownHostException)
        assertContains(error.message.orEmpty(), "Blocked non-public")
    }

    @Test
    fun productionTransportBlocksLiteralLoopbackAddress() = runBlocking {
        val error = runCatching {
            OkHttpWebPageTransport().fetch("http://127.0.0.1:9/private")
        }.exceptionOrNull()

        assertTrue(error is WebPageException)
        assertContains(error.message.orEmpty(), "Blocked non-public")
    }

    @Test
    fun transportFollowsBoundedRedirectsAndRejectsOversizedOrBinaryBodies() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
            server.enqueue(MockResponse().setBody("done").addHeader("Content-Type", "text/plain"))
            val permissiveDns = StaticDns(listOf(InetAddress.getByName("127.0.0.1")))
            val transport = OkHttpWebPageTransport(dns = permissiveDns, maxResponseBytes = 10)

            val redirected = transport.fetch(serverUrl(server, "/start"))
            assertEquals(200, redirected.statusCode)
            assertTrue(redirected.finalUrl.endsWith("/final"))

            server.enqueue(MockResponse().setBody("01234567890").addHeader("Content-Type", "text/plain"))
            val tooLarge = runCatching { transport.fetch(serverUrl(server, "/large")) }.exceptionOrNull()
            assertTrue(tooLarge is WebPageException)
            assertContains(tooLarge.message.orEmpty(), "size limit")

            server.enqueue(MockResponse().setBody("png").addHeader("Content-Type", "image/png"))
            val binary = runCatching { transport.fetch(serverUrl(server, "/image")) }.exceptionOrNull()
            assertTrue(binary is WebPageException)
            assertContains(binary.message.orEmpty(), "Unsupported")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun transportRejectsRedirectLoopsAndHonorsCallTimeout() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(6) {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/loop"))
            }
            val permissiveDns = StaticDns(listOf(InetAddress.getByName("127.0.0.1")))
            val transport = OkHttpWebPageTransport(dns = permissiveDns)
            val redirectError = runCatching { transport.fetch(serverUrl(server, "/loop")) }.exceptionOrNull()
            assertTrue(redirectError is WebPageException)
            assertContains(redirectError.message.orEmpty(), "5 redirects")

            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val shortTimeoutTransport = OkHttpWebPageTransport(
                dns = permissiveDns,
                callTimeout = Duration.ofMillis(100),
            )
            val timeoutError = runCatching {
                shortTimeoutTransport.fetch(serverUrl(server, "/timeout"))
            }.exceptionOrNull()
            assertTrue(timeoutError is java.io.InterruptedIOException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun redirectToLocalhostIsRejectedBeforeFollowUpRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", "http://localhost/private"),
            )
            val permissiveDns = StaticDns(listOf(InetAddress.getByName("127.0.0.1")))
            val transport = OkHttpWebPageTransport(dns = permissiveDns)

            val error = runCatching { transport.fetch(serverUrl(server, "/redirect")) }.exceptionOrNull()

            assertTrue(error is WebPageException)
            assertContains(error.message.orEmpty(), "Blocked non-public host")
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun localToolRegistryExposesBoundedWebFetchSchemaAndCost() {
        val dir = java.nio.file.Files.createTempDirectory("kagent-web-tool-registry")
        val service = WebPageService(
            WebContentExtractor(CapturingModel(AssistantReply("content"))),
            FakeTransport("content".toByteArray(), "text/plain"),
        )
        val registry = LocalTools(
            pathGuard = PathGuard(dir),
            approvalPolicy = AlwaysApprovePolicy,
            webPageService = service,
        ).registry()

        val tool = registry.get("fetch_web_page")!!

        assertEquals(5, tool.cost)
        assertFalse(tool.requiresApproval)
        assertContains(tool.description, "cannot execute JavaScript")
        assertContains(tool.parameters.toString(), "\"url\"")
    }

    @Test
    fun nonSuccessStatusBecomesToolErrorWithoutCallingSubagent() = runBlocking {
        val model = CapturingModel(AssistantReply("should not be used"))
        val service = WebPageService(
            WebContentExtractor(model),
            FakeTransport("missing".toByteArray(), "text/plain", status = 404),
        )

        val result = service.fetch("https://example.com/missing")

        assertTrue(result.isError)
        assertContains(result.content, "HTTP 404")
        assertTrue(model.lastMessages.isEmpty())
    }

    private class FakeTransport(
        private val bytes: ByteArray,
        private val contentType: String,
        private val status: Int = 200,
    ) : WebPageTransport {
        override suspend fun fetch(rawUrl: String): FetchedWebPage = FetchedWebPage(
            requestedUrl = rawUrl,
            finalUrl = rawUrl,
            statusCode = status,
            contentType = contentType,
            mediaType = contentType.toMediaType(),
            body = bytes,
        )
    }

    private class CapturingModel(private val reply: AssistantReply) : ChatModel {
        var lastMessages: List<AgentMessage> = emptyList()
        var lastTools: List<JsonObject>? = null

        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
            lastMessages = messages
            lastTools = tools
            return reply
        }
    }

    private class StaticDns(private val addresses: List<InetAddress>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses
    }

    private fun serverUrl(server: MockWebServer, path: String): String = server.url(path)
        .newBuilder()
        .host("web-test.example")
        .build()
        .toString()
}
