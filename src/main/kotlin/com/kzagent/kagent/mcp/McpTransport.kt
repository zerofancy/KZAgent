package com.kzagent.kagent.mcp

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.Function
import reactor.core.publisher.Mono

internal val mcpProtocolVersions = listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")

/** Owns the process as well as streams, allowing a bounded shutdown on every runtime exit. */
internal class McpStdioTransport(private val resolved: ResolvedMcpConfig, private val mapper: McpJsonMapper) : McpClientTransport, AutoCloseable {
    private val closed = AtomicBoolean()
    private val readers = Executors.newFixedThreadPool(2) { task -> Thread(task, "kzagent-mcp-stdio") }
    @Volatile private var process: Process? = null
    @Volatile private var errorHandler = Consumer<Throwable> { }
    @Volatile var stderr: String = ""; private set
    override fun protocolVersions(): List<String> = mcpProtocolVersions
    override fun setExceptionHandler(handler: Consumer<Throwable>) { errorHandler = handler }
    override fun connect(handler: Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>): Mono<Void> = Mono.fromRunnable {
        check(!closed.get()) { "连接已关闭" }
        require(Files.isDirectory(resolved.cwd)) { "MCP 工作目录不存在" }
        val child = ProcessBuilder(listOf(resolved.config.command) + resolved.config.args)
            .directory(resolved.cwd.toFile()).apply { environment().putAll(resolved.env) }.start()
        process = child
        if (closed.get()) { child.destroyForcibly(); error("连接已关闭") }
        readers.submit {
            try {
                child.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (!closed.get()) {
                        val line = reader.readLine() ?: break
                        require(line.length <= 8 * 1024 * 1024) { "MCP 消息过大" }
                        val message = McpSchema.deserializeJsonRpcMessage(mapper, line)
                        handler.apply(Mono.just(message)).subscribe({}, { if (!closed.get()) errorHandler.accept(it) })
                    }
                }
                if (!closed.get()) errorHandler.accept(IllegalStateException("MCP 进程已断开；$stderr"))
            } catch (e: Exception) { if (!closed.get()) errorHandler.accept(IllegalStateException("MCP stdio 协议读取失败；$stderr")) }
        }
        readers.submit {
            try { child.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { stderr = resolved.redactor.redact(it).take(1000) }
            } } catch (_: Exception) { /* Closing the process closes the reader. */ }
        }
    }
    override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> = Mono.fromRunnable {
        check(!closed.get()) { "连接已关闭" }
        val stream = requireNotNull(process).outputStream
        synchronized(stream) { stream.write((mapper.writeValueAsString(message) + "\n").toByteArray(Charsets.UTF_8)); stream.flush() }
    }
    override fun <T : Any?> unmarshalFrom(data: Any, typeRef: TypeRef<T>): T = mapper.convertValue(data, typeRef)
    override fun closeGracefully(): Mono<Void> = Mono.fromRunnable { close() }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        process?.let { child ->
            val descendants = child.descendants().use { it.toList() }
            runCatching { child.outputStream.close() }
            if (!child.waitFor(2, TimeUnit.SECONDS)) {
                child.destroy()
                if (!child.waitFor(1, TimeUnit.SECONDS)) child.destroyForcibly()
            }
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            runCatching { child.inputStream.close() }; runCatching { child.errorStream.close() }
        }
        readers.shutdownNow()
    }
}

/** Preserve metadata absent from the SDK Tool record, and identify calls for cancellation. */
internal class McpTrackingTransport(private val delegate: McpClientTransport, private val diagnostic: (Throwable) -> String) : McpClientTransport {
    private val lists = ConcurrentHashMap.newKeySet<Any>()
    val taskSupport = ConcurrentHashMap<String, String>()
    @Volatile var callId: Any? = null; private set
    @Volatile var dispatched: Boolean = false; private set
    @Volatile var dirty: Boolean = false
    @Volatile var failed: Boolean = false
    fun beginCall() { callId = null; dispatched = false }
    override fun protocolVersions(): List<String> = delegate.protocolVersions()
    override fun setExceptionHandler(handler: Consumer<Throwable>) {
        delegate.setExceptionHandler { failed = true; handler.accept(IllegalStateException(diagnostic(it))) }
    }
    override fun connect(handler: Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>): Mono<Void> = delegate.connect { incoming ->
        incoming.flatMap { message ->
            if (message is McpSchema.JSONRPCNotification && message.method() == "notifications/tools/list_changed") {
                dirty = true
                Mono.empty()
            } else {
                if (message is McpSchema.JSONRPCResponse && lists.remove(message.id())) {
                    val result = message.result() as? Map<*, *>
                    (result?.get("tools") as? List<*>)?.forEach { value ->
                        val tool = value as? Map<*, *> ?: return@forEach
                        val execution = tool["execution"] as? Map<*, *>
                        taskSupport[tool["name"].toString()] = execution?.get("taskSupport")?.toString() ?: "forbidden"
                    }
                }
                handler.apply(Mono.just(message))
            }
        }
    }
    override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> {
        if (message is McpSchema.JSONRPCRequest) {
            if (message.method() == "tools/list") lists += message.id()
            if (message.method() == "tools/call") { callId = message.id(); dispatched = true }
        }
        return delegate.sendMessage(message)
    }
    fun cancelCall(): Mono<Void> = callId?.let { id ->
        delegate.sendMessage(McpSchema.JSONRPCNotification("notifications/cancelled", mapOf("requestId" to id, "reason" to "KZAgent cancelled")))
    } ?: Mono.empty()
    override fun <T : Any?> unmarshalFrom(data: Any, typeRef: TypeRef<T>): T = delegate.unmarshalFrom(data, typeRef)
    override fun closeGracefully(): Mono<Void> { failed = true; lists.clear(); return delegate.closeGracefully() }
    override fun close() { failed = true; lists.clear(); delegate.close() }
}
