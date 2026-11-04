package com.kzagent.kagent.mcp

import com.kzagent.kagent.agent.*
import com.kzagent.kagent.cli.runMcpCommand
import com.kzagent.kagent.llm.*
import com.kzagent.kagent.tools.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun fixtureConfig(log: Path, version: String = "2025-11-25") = McpServerConfig(
    type = "stdio", enabled = true,
    command = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),
    args = listOf("-cp", Path.of(McpFixture::class.java.protectionDomain.codeSource.location.toURI()).toString(), McpFixture::class.java.name, log.toString(), version),
    callTimeoutSeconds = 1,
)

class McpTest {
    @Test fun importsAreDisabledAndDoNotOverwriteBrokenFiles(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-config")
        val store = McpConfigStore(dir.resolve("mcp.json"))
        assertTrue(store.load().mcpServers.isEmpty())
        val imported = store.parseImport("""{"mcpServers":{"browser":{"command":"node","args":["server.js"],"enabled":true}}}""")
        assertFalse(imported.mcpServers.getValue("browser").enabled)
        store.update { imported }
        val output = mutableListOf<String>()
        runMcpCommand(listOf("enable", "browser"), dir, store, output::add)
        assertTrue(store.load().mcpServers.getValue("browser").enabled)
        assertFails { store.parseImport("""{"mcpServers":{"browser":{"type":"stdio","command":"node","unknown":1}}}""") }
        Files.writeString(store.path, "broken")
        assertFails { store.update { McpConfig() } }
        assertEquals("broken", Files.readString(store.path))
    }

    @Test fun resolvesAndRedactsCredentials() {
        val config = McpServerConfig(type = "streamable_http", url = "https://example.com/mcp", headers = mapOf("Authorization" to "Bearer \${env:TOKEN}"))
        assertFails { config.resolve(Path.of("."), emptyMap()) }
        val resolved = config.resolve(Path.of("."), mapOf("TOKEN" to "private-value"))
        assertEquals("Bearer private-value", resolved.headers["Authorization"])
        assertFalse(resolved.redactor.redact("private-value").contains("private-value"))
        assertFails { config.copy(headers = mapOf("MCP-Protocol-Version" to "x")).validate("test") }
    }

    @Test fun stdioPreservesSchemasPaginatesFiltersTasksAndCloses(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-stdio")
        val log = dir.resolve("events")
        McpConnection("fixture", fixtureConfig(log), dir).use { connection ->
            val tools = connection.discover()
            assertEquals(3, tools.size)
            assertEquals(JsonPrimitive(false), tools[0].schema["additionalProperties"])
            assertTrue("oneOf" in tools[0].schema && "\$defs" in tools[0].schema)
            assertNotNull(tools.single { it.name == "task" }.unavailableReason)
            val result = connection.call("echo", JsonObject(emptyMap()))
            assertFalse(result.second)
            assertContains(result.first, "fixture-ok")
            assertContains(result.first, "structuredContent")
        }
        assertEquals(1, Files.readAllLines(log).count { it == "tools/call" })
        val pid = Files.readAllLines(log).first().substringAfter("PID:").toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun incompatibleProtocolIsRejected(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-version")
        McpConnection("fixture", fixtureConfig(dir.resolve("log"), "2026-07-28"), dir).use { connection ->
            assertFails { connection.discover() }
        }
    }

    @Test fun approvalDenialNeverDispatchesAndFailureIsIsolated(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-runtime")
        val log = dir.resolve("events")
        val store = McpConfigStore(dir.resolve("mcp.json"))
        store.update { McpConfig(mapOf("fixture" to fixtureConfig(log), "missing" to McpServerConfig(type = "stdio", command = "nonexistent-kzagent-mcp-command", enabled = true))) }
        McpRuntime(dir, AlwaysDenyPolicy, store).use { runtime ->
            val registry = runtime.prepareTurn(100_000)
            assertNotNull(registry.get("mcp_fixture_echo"))
            assertNull(registry.get("mcp_fixture_task"))
            assertContains(runtime.notice.orEmpty(), "missing")
            val denied = registry.get("mcp_fixture_echo")!!.handler(JsonObject(emptyMap()))
            assertTrue(denied.isError)
            assertFalse(Files.readAllLines(log).contains("tools/call"))
            store.update { current -> current.copy(mcpServers = current.mcpServers.mapValues { (id, cfg) -> if (id == "fixture") cfg.copy(disabledTools = listOf("echo")) else cfg }) }
            assertNull(runtime.prepareTurn(100_000).get("mcp_fixture_echo"))
        }
    }

    @Test fun timeoutCancelsAndDiscardsConnectionWithoutReplaying(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-timeout")
        val log = dir.resolve("events")
        McpConnection("fixture", fixtureConfig(log), dir).use { connection ->
            connection.discover()
            assertFails { connection.call("slow", JsonObject(emptyMap())) }
            assertFalse(connection.connected)
        }
        val events = Files.readAllLines(log)
        assertEquals(1, events.count { it == "tools/call" })
        assertTrue("notifications/cancelled" in events)
    }

    @Test fun agentCallsMcpAndPersistsRecoverableResults(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-agent")
        val store = McpConfigStore(dir.resolve("mcp.json"))
        store.update { McpConfig(mapOf("fixture" to fixtureConfig(dir.resolve("events")))) }
        McpRuntime(dir, AlwaysApprovePolicy, store).use { runtime ->
            val session = dir.resolve("session.jsonl")
            val model = object : ChatModel {
                override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply {
                    if (tools.isEmpty()) return AssistantReply("summary: MCP fixture / echo completed")
                    val result = messages.filterIsInstance<AgentMessage.Tool>().lastOrNull()
                    return if (result == null) {
                        assertTrue(tools.any { it["function"]?.jsonObject?.get("name") == JsonPrimitive("mcp_fixture_echo") })
                        AssistantReply(null, listOf(ModelToolCall("call", "mcp_fixture_echo", "{}")))
                    } else { assertContains(result.content, "fixture-ok"); AssistantReply("已完成") }
                }
            }
            val agent = CodingAgent(model, ToolRegistry(emptyList()), PromptBuilder(dir), SessionWriter(session), prepareTools = { runtime.prepareTurn(100_000) })
            val result = agent.runConversation("使用 MCP")
            assertEquals("已完成", result.answer)
            assertEquals(result.history, SessionReader(dir).loadFile(session).filter { it !is AgentMessage.System })
            val compressed = agent.compressHistory(result.history + List(8) { AgentMessage.User("recent") }, 2)
            assertIs<AgentMessage.Summary>(compressed.first())
        }
    }

    @Test fun interruptedCallsAreCompletedAndNotReplayedOnRestore() {
        val call = ModelToolCall("pending", "mcp_fixture_echo", "{}")
        val repaired = completeToolResults(listOf(AgentMessage.Assistant(null, listOf(call)), AgentMessage.User("继续")))
        assertIs<AgentMessage.Tool>(repaired[1])
        assertTrue((repaired[1] as AgentMessage.Tool).isError)
        assertEquals(repaired, completeToolResults(repaired))
    }

    @Test fun runtimeNoticesKeepTheHistoryPrefixAndToolPairs() {
        val system = AgentMessage.System("stable")
        val history = listOf(AgentMessage.User("old"), AgentMessage.Assistant(null, listOf(ModelToolCall("c", "t", "{}"))), AgentMessage.Tool("c", "t", "ok", false), AgentMessage.User("new"))
        val plain = buildModelContextMessages(system, history)
        val changed = buildModelContextMessages(system, history, runtimeNotice = "MCP offline")
        assertEquals(plain.take(4), changed.take(4))
        assertIs<AgentMessage.System>(changed[4])
        assertEquals(history.last(), changed.last())
    }

    @Test fun registryRejectsDuplicatesAndBinaryDoesNotLeakIntoHistory() {
        val tool = ToolDefinition("x", "x", JsonObject(emptyMap()), false) { ToolResult.ok("ok") }
        assertFails { ToolRegistry(listOf(tool, tool)) }
        val result = normalizeMcpResult("server", "tool", Json.parseToJsonElement("""{"content":[{"type":"image","mimeType":"image/png","data":"base64-secret"}]}""").jsonObject, McpRedactor())
        assertTrue(result.second)
        assertFalse(result.first.contains("base64-secret"))
    }
}

class McpConfigIsolationTest {
    @Test fun malformedServerDoesNotHideHealthyServersOrGetOverwritten(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-isolation")
        val store = McpConfigStore(dir.resolve("mcp.json"))
        val broken = """{"mcpServers":{"broken":{"type":"stdio","args":42},"disabled":{"type":"stdio","command":"node"}}}"""
        Files.writeString(store.path, broken)
        val loaded = store.load()
        assertTrue("broken" in loaded.invalidEntries)
        assertTrue("disabled" in loaded.mcpServers)
        McpRuntime(dir, AlwaysDenyPolicy, store).use { runtime ->
            runtime.prepareTurn(100000)
            assertEquals("已禁用", runtime.state.value["disabled"]?.status)
            assertEquals("配置不可用", runtime.state.value["broken"]?.status)
        }
        assertFails { store.update { McpConfig() } }
        assertEquals(broken, Files.readString(store.path))
    }

    @Test fun textClippingNeverSplitsStructuredJson() {
        val structured = buildJsonObject { put("value", "complete") }
        val result = buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "x".repeat(500)) }) })
            put("structuredContent", structured)
        }
        val output = normalizeMcpResult("s", "t", result, McpRedactor(), 200).first
        assertEquals(structured, Json.parseToJsonElement(output.substringAfter("structuredContent:\n")))
    }
}

class McpCancellationTest {
    @Test fun stoppingAnAgentPersistsAllToolResultsAndCancelsTheServer(): Unit = runBlocking {
        val dir = Files.createTempDirectory("mcp-stop")
        val events = dir.resolve("events")
        val store = McpConfigStore(dir.resolve("mcp.json"))
        store.update { McpConfig(mapOf("fixture" to fixtureConfig(events).copy(callTimeoutSeconds = 30))) }
        McpRuntime(dir, AlwaysApprovePolicy, store).use { runtime ->
            val file = dir.resolve("conversation.jsonl")
            val model = object : ChatModel {
                override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>) =
                    AssistantReply(null, listOf(ModelToolCall("slow", "mcp_fixture_slow", "{}"), ModelToolCall("pending", "mcp_fixture_echo", "{}")))
            }
            val agent = CodingAgent(model, ToolRegistry(emptyList()), PromptBuilder(dir), SessionWriter(file), prepareTools = { runtime.prepareTurn(100000) })
            val job = launch { agent.runConversation("run") }
            withTimeout(5000) {
                while (!Files.exists(events) || "tools/call" !in Files.readAllLines(events)) delay(10)
            }
            job.cancelAndJoin()
            val history = SessionReader(dir).loadFile(file)
            val results = history.filterIsInstance<AgentMessage.Tool>()
            assertEquals(setOf("slow", "pending"), results.map { it.toolCallId }.toSet())
            assertTrue(results.all { it.isError })
            assertContains(Files.readString(events), "notifications/cancelled")
            assertEquals(1, Files.readAllLines(events).count { it == "tools/call" })
        }
    }

    @Test fun mcpUsesExistingApprovalModesWithoutAnnotationBypass(): Unit = runBlocking {
        var humanCalls = 0
        var agentCalls = 0
        val human = ApprovalPolicy { humanCalls++; ApprovalResult(ApprovalDecision.DENY, ApprovalSource.HUMAN, "deny") }
        val approvalAgent = ApprovalAgent { agentCalls++; ApprovalResult(ApprovalDecision.ASK_USER, ApprovalSource.APPROVAL_AGENT, "ask") }
        val request = ApprovalRequest.McpToolCall("fixture", "node", "read", "{}", "read only", "{\"readOnlyHint\":true}", Path.of("."))
        assertFalse(ModeApprovalPolicy(ApprovalMode.MANUAL, human, approvalAgent).approve(request).allowed)
        assertEquals(0, agentCalls)
        assertFalse(ModeApprovalPolicy(ApprovalMode.AUTO, human, approvalAgent).approve(request).allowed)
        assertEquals(1, agentCalls)
        assertEquals(2, humanCalls)
        assertTrue(ModeApprovalPolicy(ApprovalMode.FULL, human, approvalAgent).approve(request).allowed)
        assertEquals(2, humanCalls)
    }
}

class McpCredentialErrorTest {
    @Test fun invalidConfigurationNeverEchoesItsCredentialValues(): Unit = runBlocking {
        val store = McpConfigStore(Files.createTempDirectory("mcp-secret").resolve("mcp.json"))
        val malformed = """{"mcpServers":{"remote":{"type":"streamable_http","headers":{"Authorization":"private_non_sk_value"},"enabled":INVALID}}}"""
        val imported = assertFails { store.parseImport(malformed) }
        assertFalse(imported.message.orEmpty().contains("private_non_sk_value"))
        Files.writeString(store.path, malformed)
        val isolated = store.load()
        assertTrue("remote" in isolated.invalidEntries)
        assertFalse(isolated.toString().contains("private_non_sk_value"))
        val broken = malformed.dropLast(1)
        Files.writeString(store.path, broken)
        val loaded = assertFails { store.load() }
        assertFalse(loaded.message.orEmpty().contains("private_non_sk_value"))
        assertEquals(broken, Files.readString(store.path))
    }
}

class McpHistoryMetadataTest {
    @Test fun recoveryKeepsTimestampsForRepeatedMessages() {
        val dir = Files.createTempDirectory("mcp-history-time")
        val file = dir.resolve("session.jsonl")
        Files.writeString(file, """
            {"role":"user","content":"继续","time":"2026-11-04T00:00:00Z"}
            {"role":"user","content":"继续","time":"2026-11-04T00:00:10Z"}
        """.trimIndent())
        val entries = SessionReader(dir).loadEntries(file)
        assertEquals(10_000L, entries[1].timestampMillis!! - entries[0].timestampMillis!!)
    }
}
