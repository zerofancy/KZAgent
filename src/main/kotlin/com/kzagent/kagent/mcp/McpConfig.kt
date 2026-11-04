package com.kzagent.kagent.mcp

import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.config.SecretRedactor
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Transient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class McpConfig(
    val mcpServers: Map<String, McpServerConfig> = emptyMap(),
    @Transient val invalidEntries: Map<String, String> = emptyMap(),
)

@Serializable
data class McpServerConfig(
    val displayName: String = "",
    val type: String,
    val enabled: Boolean = false,
    val command: String = "",
    val args: List<String> = emptyList(),
    val cwd: String = "\${workspace}",
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val disabledTools: List<String> = emptyList(),
    val connectTimeoutSeconds: Int = 15,
    val callTimeoutSeconds: Int = 60,
) {
    fun validate(id: String) {
        require(Regex("[a-z][a-z0-9-]{0,23}").matches(id)) { "Server ID 需为 1–24 位小写字母、数字或连字符，以字母开头" }
        require(connectTimeoutSeconds in 1..120 && callTimeoutSeconds in 1..3600) { "连接超时范围 1–120 秒，调用超时范围 1–3600 秒" }
        when (type) {
            "stdio" -> {
                require(command.isNotBlank() && url.isEmpty() && headers.isEmpty()) { "stdio 需要 command，不能设置 url/headers" }
                require(cwd == "\${workspace}" || Path.of(cwd).isAbsolute) { "cwd 必须为绝对路径或 \${workspace}" }
            }
            "streamable_http" -> {
                val uri = runCatching { URI(url) }.getOrNull() ?: error("HTTP 端点格式无效")
                require(uri.host != null && uri.userInfo == null && uri.fragment == null) { "需要不含 userinfo/fragment 的完整 HTTP 端点" }
                require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")) { "远端必须使用 HTTPS，本地 loopback 可用 HTTP" }
                require(command.isEmpty() && args.isEmpty() && env.isEmpty()) { "HTTP 不能设置 command/args/env" }
                val reserved = setOf("accept", "content-type", "mcp-session-id", "mcp-protocol-version", "host", "content-length")
                require(headers.keys.none { it.lowercase() in reserved }) { "不能覆盖 MCP 协议 Header" }
            }
            else -> error("不支持的 MCP type：$type")
        }
    }

    fun resolve(workspace: Path, environment: Map<String, String> = System.getenv()): ResolvedMcpConfig {
        val values = mutableListOf<String>()
        fun expand(value: String): String = Regex("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}").replace(value) {
            val name = it.groupValues[1]
            val resolved = environment[name] ?: error("缺少环境变量：$name")
            if (resolved.isNotEmpty()) values += resolved
            resolved
        }
        val resolvedEnv = env.mapValues { expand(it.value) }
        val resolvedHeaders = headers.mapValues { expand(it.value) }
        // Literal credentials need the same redaction as environment references.
        values += resolvedEnv.values.filter { it.isNotEmpty() }
        values += resolvedHeaders.values.filter { it.isNotEmpty() }
        resolvedHeaders.values.filter { it.startsWith("Bearer ", true) }.forEach { values += it.substring(7) }
        return ResolvedMcpConfig(this, if (cwd == "\${workspace}") workspace else Path.of(cwd), resolvedEnv, resolvedHeaders, McpRedactor(values))
    }
}

data class ResolvedMcpConfig(val config: McpServerConfig, val cwd: Path, val env: Map<String, String>, val headers: Map<String, String>, val redactor: McpRedactor)

class McpRedactor(values: List<String> = emptyList()) {
    private val secrets = values.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    fun redact(text: String): String = SecretRedactor.redact(secrets.fold(text) { result, secret -> result.replace(secret, "***REDACTED***") })
}

/** Independent of model configuration so MCP can be managed before configuring a provider. */
class McpConfigStore(val path: Path = AppDataDir.appDir().resolve("mcp.json")) {
    companion object {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        private val writeMutex = Mutex()
    }
    fun load(): McpConfig = try {
        if (!Files.exists(path)) McpConfig() else {
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        require(root.keys == setOf("mcpServers")) { "需要 mcpServers 配置对象" }
        val servers = mutableMapOf<String, McpServerConfig>()
        val errors = mutableMapOf<String, String>()
        root.getValue("mcpServers").jsonObject.forEach { (id, value) ->
            try { servers[id] = json.decodeFromJsonElement<McpServerConfig>(value) }
            catch (_: Exception) { errors[id] = "配置字段无效，请修复 mcp.json 中的该条目" }
        }
        McpConfig(servers, errors)
        }
    } catch (_: Exception) {
        // Serialization errors can echo the input document, including literal credentials.
        error("MCP 配置读取失败，请检查 mcp.json 格式和权限；原文件已保留")
    }
    fun encode(config: McpConfig): String = json.encodeToString(config)
    suspend fun update(transform: (McpConfig) -> McpConfig): McpConfig = writeMutex.withLock {
        val current = load()
        require(current.invalidEntries.isEmpty()) { "存在不可解析条目，请先修复 mcp.json；原文件会保留" }
        val next = transform(current)
        next.mcpServers.forEach { (id, server) -> server.validate(id) }
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "mcp-", ".tmp")
        try {
            Files.writeString(temporary, encode(next))
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
        next
    }
    fun parseImport(text: String): McpConfig = try {
        val root = json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("mcpServers")) { "导入需要 mcpServers 对象" }
        val servers = root.getValue("mcpServers").jsonObject.mapValues { (id, entry) ->
            val obj = entry.jsonObject
            val type = obj["type"] ?: when {
                "command" in obj && "url" !in obj -> JsonPrimitive("stdio")
                "url" in obj && "command" !in obj -> JsonPrimitive("streamable_http")
                else -> error("$id：无法推断 type")
            }
            json.decodeFromJsonElement<McpServerConfig>(JsonObject(obj + ("type" to type))).copy(enabled = false).also { it.validate(id) }
        }
        McpConfig(servers)
    } catch (_: Exception) {
        error("MCP 导入失败，请检查 mcpServers、ID、type 和字段类型；不支持未知字段")
    }
}
