package com.kzagent.kagent.cli

import com.kzagent.kagent.mcp.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun runMcpCommand(args: List<String>, workspace: Path, store: McpConfigStore = McpConfigStore(), output: (String) -> Unit = ::println) {
    val command = args.firstOrNull() ?: "list"
    val id = args.getOrNull(1)
    suspend fun change(transform: (McpConfig) -> McpConfig) { withContext(Dispatchers.IO) { store.update(transform) } }
    when (command) {
        "list" -> {
            output("MCP 配置：${store.path}")
            store.load().invalidEntries.forEach { (name, reason) -> output("$name\t配置不可用\t$reason") }
            store.load().mcpServers.forEach { (name, config) -> output("$name\t${config.type}\t${if (config.enabled) "启用" else "禁用"}") }
        }
        "add" -> {
            requireNotNull(id) { "需要 Server ID" }
            val server = when (args.getOrNull(2)) {
                "--stdio" -> {
                    require(args.getOrNull(3) == "--" && args.size >= 5) { "用法：mcp add ID --stdio -- COMMAND [ARGS]" }
                    McpServerConfig(type = "stdio", command = args[4], args = args.drop(5))
                }
                "--http" -> { require(args.size == 4) { "用法：mcp add ID --http URL" }; McpServerConfig(type = "streamable_http", url = args[3]) }
                else -> error("需要 --stdio 或 --http")
            }
            server.validate(id)
            change { current -> require(id !in current.mcpServers) { "ID 已存在，请通过 MCP 页面编辑" }; current.copy(mcpServers = current.mcpServers + (id to server)) }
            output("已添加 $id（禁用）；测试成功后使用 mcp enable $id 启用")
        }
        "enable", "disable", "remove" -> {
            requireNotNull(id) { "需要 Server ID" }
            change { current ->
                val server = current.mcpServers[id] ?: error("Server 不存在：$id")
                current.copy(mcpServers = if (command == "remove") current.mcpServers - id else current.mcpServers + (id to server.copy(enabled = command == "enable")))
            }
            output("已${when (command) { "enable" -> "启用"; "disable" -> "禁用"; else -> "移除" }} $id，下轮生效")
        }
        "import" -> {
            val path = Path.of(requireNotNull(id) { "需要 JSON 文件路径" })
            val imported = store.parseImport(Files.readString(path))
            change { current ->
                val duplicate = current.mcpServers.keys.intersect(imported.mcpServers.keys)
                require(duplicate.isEmpty()) { "存在冲突 ID：${duplicate.joinToString()}；请通过 MCP 页面选择替换或跳过" }
                current.copy(mcpServers = current.mcpServers + imported.mcpServers)
            }
            output("已导入 ${imported.mcpServers.size} 个 Server（禁用）")
        }
        "test", "tools" -> {
            requireNotNull(id) { "需要 Server ID" }
            val server = store.load().mcpServers[id] ?: error("Server 不存在：$id")
            server.validate(id)
            output("测试 $id：${server.type}；将实际启动程序或连接服务，不调用业务工具")
            withContext(Dispatchers.IO) {
                McpConnection(id, server, workspace).use { connection ->
                    try {
                        val tools = connection.discover()
                        output("连接成功；协议 ${connection.protocol}；发现 ${tools.size} 个工具")
                        tools.forEach { tool -> output("${tool.name}\t${tool.unavailableReason ?: if (tool.name in server.disabledTools) "已禁用" else "可用"}") }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error(connection.redactor.redact(e.message ?: "连接失败")) }
                }
            }
        }
        else -> error("MCP 命令：list | add | import | test | tools | enable | disable | remove")
    }
}
