package com.kzagent.kagent.mcp

import io.modelcontextprotocol.client.McpAsyncClient
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import tools.jackson.databind.json.JsonMapper

class McpConnection(val id: String, val config: McpServerConfig, workspace: Path) : AutoCloseable {
    private val resolved = config.resolve(workspace)
    val redactor = resolved.redactor
    private val mapper = JacksonMcpJsonMapper(JsonMapper.builder().build())
    private val executor = if (config.type == "streamable_http") Executors.newCachedThreadPool() else null
    private val transport: McpTrackingTransport
    private val client: McpAsyncClient
    private val closed = AtomicBoolean()
    private val callMutex = Mutex()
    val dirty: Boolean get() = transport.dirty
    val connected: Boolean get() = !closed.get() && !transport.failed
    var protocol: String = ""; private set

    init {
        config.validate(id)
        val raw = if (config.type == "stdio") McpStdioTransport(resolved, mapper) else {
            val uri = URI(config.url)
            val origin = URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString()
            HttpClientStreamableHttpTransport.builder(origin)
                .endpoint(uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: ""))
                .jsonMapper(mapper)
                .clientBuilder(HttpClient.newBuilder().executor(executor).followRedirects(HttpClient.Redirect.NEVER))
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds.toLong()))
                .supportedProtocolVersions(mcpProtocolVersions.filter { it != "2024-11-05" })
                .addHttpRequestCustomizer { builder, _, _, _, _ -> resolved.headers.forEach { (name, value) -> builder.header(name, value) } }
                .build()
        }
        transport = McpTrackingTransport(raw, ::diagnostic)
        client = McpClient.async(transport)
            .clientInfo(McpSchema.Implementation.builder("KZAgent", "0.1.0").build())
            .requestTimeout(Duration.ofSeconds(config.callTimeoutSeconds.toLong()))
            .initializationTimeout(Duration.ofSeconds(config.connectTimeoutSeconds.toLong()))
            .roots(McpSchema.Root(workspace.toUri().toString(), workspace.fileName?.toString() ?: "workspace"))
            .build()
    }

    suspend fun discover(): List<McpRemoteTool> = try { withTimeout(config.connectTimeoutSeconds * 1000L) {
        check(connected) { "MCP 连接已关闭" }
        if (protocol.isEmpty()) {
            val initialized = client.initialize().awaitSingle()
            protocol = initialized.protocolVersion()
            require(protocol in transport.protocolVersions()) { "协议不兼容：$protocol" }
        }
        if (client.serverCapabilities.tools() == null) return@withTimeout emptyList()
        val tools = mutableListOf<McpRemoteTool>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = client.listTools(cursor).awaitSingle()
            tools += page.tools().map { tool ->
                val schema = Json.parseToJsonElement(mapper.writeValueAsString(tool.inputSchema())).jsonObject
                val reason = when {
                    transport.taskSupport[tool.name()] == "required" -> "需要 Tasks，首版不支持"
                    schema["type"] != null && schema["type"] != JsonPrimitive("object") -> "参数 schema 根类型必须为 object"
                    else -> null
                }
                McpRemoteTool(tool.name(), tool.description().orEmpty(), JsonObject(schema + ("type" to JsonPrimitive("object"))),
                    tool.annotations()?.let { mapper.writeValueAsString(it) }.orEmpty(), reason)
            }
            cursor = page.nextCursor()?.takeIf { it.isNotEmpty() }
            require(cursor == null || cursors.add(cursor)) { "tools/list 返回重复分页 cursor" }
        } while (cursor != null)
        require(tools.map { it.name }.distinct().size == tools.size) { "Server 返回了重名工具" }
        transport.dirty = false
        tools
    } } catch (e: CancellationException) { throw e }
    catch (e: Exception) { throw IllegalStateException(diagnostic(e)) }

    private fun diagnostic(error: Throwable): String = redactor.redact(
        generateSequence(error) { it.cause }.take(8).mapNotNull {
            if (it is McpHttpClientTransportAuthorizationException) "HTTP ${it.responseInfo.statusCode()}：${it.message}"
            else it.message
        }.distinct().joinToString("；")
    ).take(4000)

    suspend fun call(name: String, arguments: JsonObject): Pair<String, Boolean> = callMutex.withLock {
        check(connected) { "MCP 服务已断开，未派发" }
        transport.beginCall()
        try {
            val args = mapper.readValue(arguments.toString(), object : TypeRef<Map<String, Any>>() {})
            val result = withTimeout(config.callTimeoutSeconds * 1000L) {
                client.callTool(McpSchema.CallToolRequest.builder(name).arguments(args).build()).awaitSingle()
            }
            normalizeMcpResult(id, name, Json.parseToJsonElement(mapper.writeValueAsString(result)).jsonObject, redactor)
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                withTimeoutOrNull(1000) { runCatching { transport.cancelCall().awaitSingleOrNull() } }
                close()
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                withTimeoutOrNull(1000) { runCatching { transport.cancelCall().awaitSingleOrNull() } }
                close()
            }
            // Never reuse an uncertain call or replay it after reconnecting.
            throw IllegalStateException(redactor.redact("MCP $id/$name：${diagnostic(e)}；${if (transport.dispatched) "已派发，若无业务结果则完成情况未知，请勿自动重试" else "未派发"}"))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { runCatching { client.closeGracefully().block(Duration.ofSeconds(1)) } }
        finally {
            runCatching { transport.closeGracefully().block(Duration.ofSeconds(4)) }
            executor?.shutdownNow()
        }
    }
}

data class McpRemoteTool(val name: String, val description: String, val schema: JsonObject, val annotations: String = "", val unavailableReason: String? = null)

internal fun normalizeMcpResult(id: String, name: String, result: JsonObject, redactor: McpRedactor, maxChars: Int = 20_000): Pair<String, Boolean> {
    val parts = mutableListOf("[MCP server=$id tool=$name]")
    var supported = false
    result["content"]?.jsonArray.orEmpty().forEach { item ->
        val obj = item.jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "text" -> { supported = true; parts += obj["text"]?.jsonPrimitive?.content.orEmpty() }
            "resource_link" -> { supported = true; parts += "${obj["name"] ?: "资源"} ${obj["uri"]} ${obj["mimeType"] ?: ""}" }
            "resource" -> {
                val resource = obj["resource"]?.jsonObject
                supported = supported || resource?.get("text") != null
                parts += "${resource?.get("uri")} ${resource?.get("mimeType") ?: ""}\n${resource?.get("text")?.jsonPrimitive?.content ?: "[blob 尚未展示]"}"
            }
            else -> parts += "[${obj["type"]} ${obj["mimeType"] ?: ""}：此内容尚未展示]"
        }
    }
    val text = redactor.redact(parts.joinToString("\n\n"))
    val structured = result["structuredContent"]?.let { supported = true; redactor.redact(it.toString()) }
    val suffix = structured?.let {
        if (it.length + 32 <= maxChars / 2) "\n\nstructuredContent:\n$it"
        else "\n[structuredContent 已省略，原始长度 ${it.length}]"
    }.orEmpty()
    val available = (maxChars - suffix.length).coerceAtLeast(0)
    val clipped = if (text.length <= available) text else {
        val marker = "\n[已裁剪，原始长度 ${text.length}]"
        text.take((available - marker.length).coerceAtLeast(0)) + marker
    }
    return (clipped + suffix) to (result["isError"]?.jsonPrimitive?.booleanOrNull == true || !supported && result["content"]?.jsonArray?.isNotEmpty() == true)
}
