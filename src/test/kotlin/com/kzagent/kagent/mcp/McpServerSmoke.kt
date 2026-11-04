package com.kzagent.kagent.mcp

import com.kzagent.kagent.agent.*
import com.kzagent.kagent.cli.runMcpCommand
import com.kzagent.kagent.config.FileKitPaths
import com.kzagent.kagent.llm.*
import com.kzagent.kagent.tools.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Opt-in real-server verification. Not part of unit tests; accepts command/args without credentials. */
object McpServerSmoke {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        require(args.isNotEmpty()) { "Pass a stdio server command and arguments" }
        FileKitPaths.initialize()
        val workspace = Path.of("").toAbsolutePath()
        val store = McpConfigStore()
        val id = "open-browser-control"
        val registered = store.load().mcpServers[id]
        if (registered == null) runMcpCommand(listOf("add", id, "--stdio", "--") + args, workspace, store)
        else require(registered.command == args[0] && registered.args == args.drop(1)) { "Existing registration differs" }
        runMcpCommand(listOf("test", id), workspace, store)
        runMcpCommand(listOf("enable", id), workspace, store)
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/kzagent-mcp-smoke") { exchange ->
            val bytes = "<html><head><title>KZAgent MCP 验证</title></head><body><h1 id='proof'>KZAgent MCP verified</h1></body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            McpRuntime(workspace, AlwaysApprovePolicy, store).use { runtime ->
                val tools = runtime.prepareTurn(1_000_000)
                val required = listOf("browser_new_tab_group", "browser_get_page_info", "browser_execute_js")
                required.forEach { requireNotNull(tools.get("mcp_${id}_$it")) { "Missing tool $it: ${runtime.notice}" } }
                println("REGISTERED: ${runtime.state.value[id]?.tools?.size} tools; protocol ${runtime.state.value[id]?.protocol}")
                var created = false
                try {
                    val groupArgs = buildJsonObject { put("name", "KZAgent MCP 验证"); put("url", "http://127.0.0.1:${http.address.port}/kzagent-mcp-smoke") }
                    val group = tools.get("mcp_${id}_browser_new_tab_group")!!.handler(groupArgs)
                    check(!group.isError) { group.content }
                    created = true
                    val file = Files.createTempFile("kzagent-mcp-smoke-", ".jsonl")
                    var calls = 0
                    val model = object : ChatModel {
                        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
                            if (calls++ == 0) return AssistantReply(null, listOf(ModelToolCall("browser-proof", "mcp_${id}_browser_get_page_info", "{}")))
                            val result = messages.filterIsInstance<AgentMessage.Tool>().last()
                            check(!result.isError && result.content.contains("kzagent-mcp-smoke")) { result.content }
                            return AssistantReply("实际浏览器调用成功")
                        }
                    }
                    val agent = CodingAgent(model, tools, PromptBuilder(workspace), SessionWriter(file))
                    println("AGENT LOOP: ${agent.run("读取验证页面标题和地址")}")
                    val proof = tools.get("mcp_${id}_browser_execute_js")!!.handler(buildJsonObject { put("expression", "document.getElementById('proof').textContent") })
                    check(!proof.isError && proof.content.contains("KZAgent MCP verified")) { proof.content }
                    println("BROWSER DOM: KZAgent MCP verified")
                    check(SessionReader(file.parent).loadFile(file).any { it is AgentMessage.Tool && !it.isError })
                    Files.deleteIfExists(file)
                } finally {
                    if (created) {
                        val cleanup = tools.get("mcp_${id}_browser_execute_js")!!.handler(buildJsonObject { put("expression", "(setTimeout(() => window.close(), 50), 'closing KZAgent verification tab')") })
                        check(!cleanup.isError) { "Test tab cleanup failed: ${cleanup.content}" }
                        withTimeout(5000) {
                            while (true) {
                                delay(250)
                                val tabs = tools.get("mcp_${id}_browser_list_tabs")!!.handler(JsonObject(emptyMap()))
                                check(!tabs.isError) { tabs.content }
                                val remaining = Json.parseToJsonElement(tabs.content.substringAfter("\n\n")).jsonObject["tabs"]?.jsonArray
                                check(remaining != null) { "Missing tabs result" }
                                if (remaining.isEmpty()) break
                            }
                        }
                        println("CLEANUP: verified no tabs remain in the test session")
                    }
                }
            }
        } finally { http.stop(0) }
    }
}
