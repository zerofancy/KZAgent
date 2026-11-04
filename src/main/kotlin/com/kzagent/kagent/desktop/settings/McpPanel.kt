package com.kzagent.kagent.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.mcp.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Layer
import io.github.composefluent.component.*
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString

@Composable
internal fun McpPanel(workspace: Path, runtime: McpRuntime?, busy: Boolean) {
    val store = remember { McpConfigStore() }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(McpConfig()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<Map<String, McpServerState>>(emptyMap()) }
    val states = runtime?.state?.collectAsState()?.value.orEmpty()
    var editor by remember { mutableStateOf<Pair<String, McpServerConfig>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<McpRemoteTool?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        try { config = withContext(Dispatchers.IO) { store.load() }; error = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "MCP 配置读取失败，请修复 ${store.path}；原文件未被覆盖。" }
    }
    fun save(change: (McpConfig) -> McpConfig) {
        if (saving) return
        saving = true; error = null
        scope.launch {
            try { config = withContext(Dispatchers.IO) { store.update(change) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "保存失败，请检查配置和文件权限；草稿仍保留。" }
            finally { saving = false }
        }
    }
    fun test(id: String, server: McpServerConfig) {
        testing = id
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    McpConnection(id, server, workspace).use { connection ->
                        try { McpServerState("测试成功", connection.discover(), protocol = connection.protocol) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { McpServerState("测试失败", error = connection.redactor.redact(e.message ?: "连接失败")) }
                    }
                }
                testResults = testResults + (id to result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { testResults = testResults + (id to McpServerState("测试失败", error = "请检查配置与环境变量")) }
            finally { testing = null }
        }
    }
    McpScrollBox(Modifier.fillMaxSize().padding(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("MCP", style = FluentTheme.typography.title)
            Text("注册外部工具服务；更改在下一轮生效。测试连接会运行程序或访问服务，不调用业务工具。")
            Text("stdio 以当前用户身份运行并继承环境；工作目录与 roots 不是沙箱。远端仅收到连接元数据和工具参数。", style = FluentTheme.typography.caption)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }, disabled = saving) { Text("添加 Server") }
                Button(onClick = { importing = true }, disabled = saving) { Text("导入 JSON") }
                Button(onClick = { revision++ }) { Text("重新读取配置") }
                Button(onClick = { runtime?.requestRefresh(); revision++ }, disabled = runtime == null || busy) { Text("下轮刷新 / 重连当前会话") }
            }
            error?.let { Text(it, color = FluentTheme.colors.system.critical) }
            config.invalidEntries.forEach { (id, reason) -> Text("$id：$reason", color = FluentTheme.colors.system.critical) }
            if (config.mcpServers.isEmpty() && config.invalidEntries.isEmpty()) Text("尚未注册 MCP Server")
            config.mcpServers.toSortedMap().forEach { (id, server) ->
                Layer(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${server.displayName.ifBlank { id }} · $id · ${server.type}", style = FluentTheme.typography.subtitle)
                        Text(if (server.enabled) "已启用，下轮可用" else "已禁用")
                        Text("当前会话：${states[id]?.status ?: "未连接"} · 工具定义约 ${states[id]?.schemaTokens ?: 0} tokens")
                        (states[id]?.error)?.let { Text(it, color = FluentTheme.colors.system.critical) }
                        testResults[id]?.let { result ->
                            Text("上次测试：${result.status} · 协议 ${result.protocol} · 发现 ${result.tools.size} 个工具")
                            result.error?.let { Text(it, color = FluentTheme.colors.system.critical) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { save { it.copy(mcpServers = it.mcpServers + (id to server.copy(enabled = !server.enabled))) } }, disabled = saving) { Text(if (server.enabled) "禁用" else "启用") }
                            Button(onClick = { test(id, server) }, disabled = testing != null) { Text(if (testing == id) "测试中…" else "测试连接") }
                            Button(onClick = { editor = id to server }, disabled = saving) { Text("编辑") }
                            Button(onClick = { deleting = id }, disabled = saving) { Text("删除") }
                        }
                        val tools = testResults[id]?.tools ?: states[id]?.tools.orEmpty()
                        if (tools.isNotEmpty()) Text("发现 ${tools.size} · 启用 ${tools.count { it.name !in server.disabledTools && it.unavailableReason == null }} · 不可用 ${tools.count { it.unavailableReason != null }}")
                        tools.forEach { tool ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CheckBox(checked = tool.name !in server.disabledTools, enabled = !saving && tool.unavailableReason == null,
                                    onCheckStateChange = { enabled -> if (!saving && tool.unavailableReason == null) save { current ->
                                        val latest = current.mcpServers.getValue(id)
                                        current.copy(mcpServers = current.mcpServers + (id to latest.copy(disabledTools = if (enabled) latest.disabledTools - tool.name else (latest.disabledTools + tool.name).distinct())))
                                    } })
                                Text(tool.name + (tool.unavailableReason?.let { " · $it" } ?: ""), modifier = Modifier.weight(1f))
                                Button(onClick = { detail = tool }) { Text("详情") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding || editor != null) {
        val current = editor
        McpEditor(current?.first.orEmpty(), current?.second ?: McpServerConfig(type = "stdio"), current == null,
            onDismiss = { adding = false; editor = null },
            saveError = error, onSave = { id, server ->
                scope.launch {
                    saving = true
                    try {
                        config = withContext(Dispatchers.IO) { store.update { old ->
                            require(current != null || id !in old.mcpServers) { "Server ID 已存在" }
                            old.copy(mcpServers = old.mcpServers + (id to server))
                        } }
                        editor = null; adding = false; testResults = testResults - id
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "保存失败：ID 冲突或配置不可写，草稿已保留" }
                    finally { saving = false }
                }
            }, saving = saving)
    }
    if (importing) McpImportDialog(store, saving, error, onDismiss = { importing = false }, onImport = { imported, replace ->
        scope.launch {
            saving = true
            try {
                config = withContext(Dispatchers.IO) { store.update { old ->
                    McpConfig(old.mcpServers + imported.mcpServers.filterKeys { replace || it !in old.mcpServers })
                } }
                importing = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "导入保存失败，请先修复配置或文件权限；导入内容已保留" }
            finally { saving = false }
        }
    })
    deleting?.let { id -> ContentDialog(title = "删除 MCP Server", visible = true,
        content = { Text("移除 $id 的注册，下轮生效。程序与业务数据会保留。") },
        primaryButtonText = "删除", closeButtonText = "取消", onButtonClick = {
            if (it == ContentDialogButton.Primary) save { current -> current.copy(mcpServers = current.mcpServers - id) }
            deleting = null
        }) }
    detail?.let { tool -> ContentDialog(title = tool.name, visible = true,
        content = { McpScrollBox(Modifier.fillMaxWidth().heightIn(max = 420.dp)) { SelectionContainer { Text("${tool.description}\n\n${McpConfigStore.json.encodeToString(tool.schema)}\n\n${tool.annotations}") } } },
        primaryButtonText = "确定", closeButtonText = "关闭", onButtonClick = { detail = null }, size = DialogSize.Max) }
}

@Composable
private fun McpEditor(initialId: String, initial: McpServerConfig, adding: Boolean, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (String, McpServerConfig) -> Unit) {
    var id by remember { mutableStateOf(initialId) }
    var name by remember { mutableStateOf(initial.displayName) }
    var http by remember { mutableStateOf(initial.type == "streamable_http") }
    var command by remember { mutableStateOf(initial.command) }
    var args by remember { mutableStateOf(McpConfigStore.json.encodeToString(initial.args)) }
    var cwd by remember { mutableStateOf(initial.cwd) }
    var env by remember { mutableStateOf(McpConfigStore.json.encodeToString(initial.env)) }
    var url by remember { mutableStateOf(initial.url) }
    var headers by remember { mutableStateOf(McpConfigStore.json.encodeToString(initial.headers)) }
    var connectTimeout by remember { mutableStateOf(initial.connectTimeoutSeconds.toString()) }
    var callTimeout by remember { mutableStateOf(initial.callTimeoutSeconds.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    ContentDialog(title = if (adding) "添加 MCP Server" else "编辑 MCP Server", visible = true,
        content = { McpScrollBox(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Server ID（创建后固定）"); TextField(value = id, onValueChange = { id = it }, enabled = adding)
                Text("显示名称"); TextField(value = name, onValueChange = { name = it })
                CheckBox(checked = http, onCheckStateChange = { http = it }, label = "Streamable HTTP（未选则为 stdio）")
                if (http) {
                    Text("完整 MCP URL"); TextField(value = url, onValueChange = { url = it })
                    Text("Headers JSON，支持 \${env:NAME}（内容遮罩）"); TextField(value = headers, onValueChange = { headers = it }, visualTransformation = PasswordVisualTransformation())
                } else {
                    Text("可执行文件（不含参数）"); TextField(value = command, onValueChange = { command = it })
                    Text("参数 JSON 数组"); TextField(value = args, onValueChange = { args = it })
                    Text("工作目录（绝对路径或 \${workspace}）"); TextField(value = cwd, onValueChange = { cwd = it })
                    Text("环境变量 JSON，支持 \${env:NAME}（内容遮罩）"); TextField(value = env, onValueChange = { env = it }, visualTransformation = PasswordVisualTransformation())
                    Text("Windows .cmd/.bat 请显式使用 cmd.exe 参数；桌面 PATH 缺少程序时请填绝对路径。", style = FluentTheme.typography.caption)
                }
                Text("连接超时（秒）"); TextField(value = connectTimeout, onValueChange = { connectTimeout = it })
                Text("调用超时（秒）"); TextField(value = callTimeout, onValueChange = { callTimeout = it })
                (error ?: saveError)?.let { Text(it, color = FluentTheme.colors.system.critical) }
            }
        } }, primaryButtonText = if (saving) "保存中…" else "保存", closeButtonText = "取消", onButtonClick = { action ->
            if (saving) return@ContentDialog
            if (action != ContentDialogButton.Primary) onDismiss() else try {
                val server = initial.copy(displayName = name, type = if (http) "streamable_http" else "stdio",
                    command = if (http) "" else command, args = if (http) emptyList() else McpConfigStore.json.decodeFromString(args),
                    cwd = cwd, env = if (http) emptyMap() else McpConfigStore.json.decodeFromString(env), url = if (http) url else "",
                    headers = if (http) McpConfigStore.json.decodeFromString(headers) else emptyMap(),
                    connectTimeoutSeconds = connectTimeout.toInt(), callTimeoutSeconds = callTimeout.toInt())
                server.validate(id); onSave(id, server)
            } catch (_: Exception) { error = "请检查 ID、端点、JSON 和超时字段" }
        }, size = DialogSize.Max)
}

@Composable
private fun McpImportDialog(store: McpConfigStore, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onImport: (McpConfig, Boolean) -> Unit) {
    var text by remember { mutableStateOf("{\"mcpServers\":{}}") }
    var replace by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ContentDialog(title = "导入 MCP JSON", visible = true, content = {
        McpScrollBox(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("新增项均禁用。冲突项默认跳过；勾选后替换并禁用。建议只使用环境变量引用，不粘贴密钥。")
                TextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                CheckBox(checked = replace, onCheckStateChange = { replace = it }, label = "替换冲突 ID")
                (error ?: saveError)?.let { Text(it, color = FluentTheme.colors.system.critical) }
            }
        }
    }, primaryButtonText = "导入", closeButtonText = "取消", onButtonClick = {
        if (it != ContentDialogButton.Primary) onDismiss()
        else if (!saving) try { onImport(store.parseImport(text), replace) } catch (_: Exception) { error = "导入失败：请检查字段、type 与 ID；不支持字段会被拒绝" }
    }, size = DialogSize.Max)
}

@Composable
private fun McpScrollBox(modifier: Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Box(Modifier.fillMaxWidth().padding(end = 14.dp).verticalScroll(scroll)) { content() }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
