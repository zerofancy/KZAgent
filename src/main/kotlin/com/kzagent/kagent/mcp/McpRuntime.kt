package com.kzagent.kagent.mcp

import com.kzagent.kagent.tools.*
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/** Connection state is per runtime/session; temporary tests never publish global online status. */
data class McpServerState(val status: String, val tools: List<McpRemoteTool> = emptyList(), val error: String? = null, val protocol: String = "", val schemaTokens: Int = 0)

class McpRuntime(
    private val workspace: Path,
    private val approvalPolicy: ApprovalPolicy,
    private val store: McpConfigStore = McpConfigStore(),
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val mutex = Mutex()
    private var config: McpConfig? = null
    private val connections = mutableMapOf<String, McpConnection>()
    private val aliases = mutableMapOf<Pair<String, String>, String>()
    private val mutableState = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val state: StateFlow<Map<String, McpServerState>> = mutableState
    var notice: String? = null; private set
    @Volatile private var refreshRequested = false
    fun requestRefresh() { refreshRequested = true }

    suspend fun prepareTurn(contextWindowSize: Int): ToolRegistry = mutex.withLock {
        check(!closed.get()) { "MCP runtime 已关闭" }
        notice = null
        val next = try { withContext(Dispatchers.IO) { store.load() } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            notice = "MCP 配置读取失败；内置工具仍可使用。请在 MCP 页面修复配置。"
            withContext(Dispatchers.IO) { closeConnections() }
            mutableState.value = mapOf("配置" to McpServerState("配置不可用", error = notice))
            return@withLock ToolRegistry(emptyList())
        }
        if (next != config || refreshRequested) {
            withContext(Dispatchers.IO) { closeConnections() }
            mutableState.value = emptyMap()
            config = next; refreshRequested = false
        }
        val limit = Semaphore(4)
        val prepared = supervisorScope {
            next.mcpServers.toSortedMap().map { (id, server) -> async {
                limit.withPermit {
                    if (!server.enabled) return@withPermit id to McpServerState("已禁用")
                    var connection = synchronized(connections) { connections[id] }
                    try {
                        server.validate(id)
                        if (connection == null || !connection.connected) {
                            connection?.let { withContext(Dispatchers.IO) { it.close() } }
                            connection = withContext(Dispatchers.IO) { McpConnection(id, server, workspace) }
                            synchronized(connections) { connections[id] = connection }
                            if (closed.get()) { connection.close(); error("MCP runtime 已关闭") }
                        } else if (!connection.dirty) {
                            return@withPermit id to (mutableState.value[id] ?: McpServerState("未连接"))
                        }
                        val tools = connection.discover()
                        id to McpServerState("已连接", tools, protocol = connection.protocol)
                    } catch (e: TimeoutCancellationException) {
                        withContext(NonCancellable + Dispatchers.IO) { connection?.close() }
                        currentCoroutineContext().ensureActive()
                        id to McpServerState("连接失败", error = "MCP 连接或发现超时")
                    } catch (e: CancellationException) {
                        withContext(NonCancellable + Dispatchers.IO) { connection?.close() }
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.IO) { connection?.close() }
                        val message = connection?.redactor?.redact(e.message ?: "连接失败")
                            ?: e.message?.takeIf { it.startsWith("缺少环境变量：") }
                            ?: "配置或连接不可用，请检查字段和环境变量"
                        val status = when {
                            message.contains("401") || message.contains("403") -> "需要凭据或权限"
                            message.contains("protocol", true) || message.contains("协议不兼容") -> "协议不兼容"
                            else -> "连接失败"
                        }
                        id to McpServerState(status, error = message.take(4000))
                    }
                }
            } }.awaitAll().toMap() + next.invalidEntries.mapValues { McpServerState("配置不可用", error = it.value) }
        }
        mutableState.value = prepared
        val definitions = mutableListOf<ToolDefinition>()
        prepared.toSortedMap().forEach { (id, status) ->
            val server = next.mcpServers[id] ?: return@forEach
            if (status.status != "已连接") return@forEach
            val connection = synchronized(connections) { connections[id] } ?: return@forEach
            val used = aliases.filterKeys { it.first == id }.values.toMutableSet()
            val valid = Regex("[A-Za-z0-9_-]+")
            // Reserve direct names first so generated tN aliases never steal a real tool name.
            status.tools.sortedBy { it.name }.forEach { tool ->
                val key = id to tool.name
                val direct = "mcp_${id}_${tool.name}"
                if (key !in aliases && valid.matches(tool.name) && direct.length <= 64 && direct !in used) {
                    aliases[key] = direct; used += direct
                }
            }
            status.tools.sortedBy { it.name }.forEach { tool ->
                val name = aliases.getOrPut(id to tool.name) {
                    var n = 1
                    while ("mcp_${id}_t$n" in used) n++
                    "mcp_${id}_t$n".also { used += it }
                }
                if (tool.name in server.disabledTools || tool.unavailableReason != null) return@forEach
                definitions += ToolDefinition(
                    name = name,
                    description = connection.redactor.redact("MCP ${server.displayName.ifBlank { id }} / ${tool.name}: ${tool.description}"),
                    parameters = tool.schema,
                    requiresApproval = true,
                    sanitizeArguments = connection.redactor::redact,
                    handler = { args ->
                        val approval = approvalPolicy.approve(ApprovalRequest.McpToolCall(
                            serverId = id, endpoint = if (server.type == "stdio") server.command else server.url,
                            toolName = tool.name, arguments = connection.redactor.redact(args.toString()),
                            description = connection.redactor.redact(tool.description), annotations = tool.annotations,
                            workspace = workspace,
                        ))
                        if (!approval.allowed) ToolResult.error("MCP $id/${tool.name}：审批已拒绝，未派发", approval.source)
                        else try {
                            val (content, error) = connection.call(tool.name, args)
                            ToolResult(content, error, approvalSource = approval.source)
                        } catch (e: TimeoutCancellationException) {
                            markDisconnected(id)
                            ToolResult.error("MCP $id/${tool.name}：调用超时；已尝试取消，完成情况未知，请勿自动重试", approval.source)
                        } catch (e: CancellationException) { markDisconnected(id); throw e }
                        catch (e: Exception) { markDisconnected(id); ToolResult.error(connection.redactor.redact(e.message ?: "MCP 调用失败").take(20_000), approval.source) }
                    },
                )
            }
        }
        mutableState.value = prepared.mapValues { (id, status) ->
            status.copy(schemaTokens = ToolRegistry(definitions.filter { it.name.startsWith("mcp_${id}_") }).estimatedSchemaTokens())
        }
        val registry = ToolRegistry(definitions)
        val budget = minOf(16_000, contextWindowSize / 10)
        val unavailable = prepared.filterValues { it.error != null }.keys
        notice = if (unavailable.isNotEmpty()) "MCP Server 不可用：${unavailable.joinToString()}。不要猜测调用结果；其他工具仍可使用。" else null
        if (registry.estimatedSchemaTokens() > budget) {
            notice = "MCP 工具定义超出本轮预算 $budget tokens，已暂停本轮 MCP 工具。请在 MCP 页面禁用部分工具后重试。"
            return@withLock ToolRegistry(emptyList())
        }
        registry
    }

    private fun markDisconnected(id: String) {
        mutableState.value = mutableState.value.mapValues { (key, state) -> if (key == id) state.copy(status = "已断开", error = "下轮将尝试重连；不会重放未确认的调用") else state }
    }
    private fun closeConnections() {
        val owned = synchronized(connections) { connections.values.toList().also { connections.clear() } }
        owned.forEach { runCatching { it.close() } }
    }
    override fun close() { if (closed.compareAndSet(false, true)) closeConnections() }
}
