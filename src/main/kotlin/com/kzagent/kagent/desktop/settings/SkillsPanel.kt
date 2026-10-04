package com.kzagent.kagent.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.kzagent.kagent.config.*
import com.kzagent.kagent.skill.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.*
import kotlinx.coroutines.*
import java.nio.file.Path

internal fun filterSkills(entries: List<SkillEntry>, query: String, origin: SkillOrigin?, status: String?): List<SkillEntry> =
    entries.filter { entry ->
        (origin == null || entry.origin == origin) && (status == null || entry.status == status) &&
            (query.isBlank() || "${entry.manifest?.name} ${entry.manifest?.description} ${entry.id}".contains(query.trim(), true))
    }

@Composable
internal fun SkillsPanel(
    config: SkillsConfig,
    configured: Boolean,
    busy: Boolean,
    save: suspend ((SkillsConfig) -> SkillsConfig) -> Unit,
    onRefresh: () -> Unit,
    onDeletingChanged: (Boolean) -> Unit,
    skillsRoot: Path = AppDataDir.skillsRoot(),
) {
    val root = skillsRoot
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<SkillEntry>()) }
    var revision by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf<SkillOrigin?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var directories by remember { mutableStateOf(false) }
    var extraPath by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SkillEntry?>(null) }
    val currentBusy by rememberUpdatedState(busy)
    LaunchedEffect(config, revision) {
        loading = true
        try { entries = withContext(Dispatchers.IO) { SkillCatalog.scan(root, config) } }
        finally { loading = false }
    }
    fun update(transform: (SkillsConfig) -> SkillsConfig) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try { save(transform) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = SecretRedactor.redact(e.message ?: "保存失败") }
            finally { saving = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Skills", style = FluentTheme.typography.title)
        Text("安装请在聊天中提出。修改在下一轮对话生效；当前任务继续执行。")
        if (!configured) Text("请先在设置中完成模型配置。")
        if (error != null) Text(error!!, color = FluentTheme.colors.system.critical)
        if (saving || loading) Text(if (saving) "正在保存…" else "正在加载…")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { update { it.copy(enabled = !it.enabled) } }, disabled = saving || !configured) {
                Text(if (config.enabled) "禁用全部" else "启用全部")
            }
            Button(onClick = { revision++; onRefresh() }, disabled = saving || loading) { Text("刷新") }
            Button(onClick = { directories = !directories }) { Text("加载目录") }
        }
        if (directories) {
            SkillScroll(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                Text("默认目录：$root")
                config.extraDirectories.forEach { path ->
                    Text(path)
                    Button(onClick = { update { it.copy(extraDirectories = it.extraDirectories - path) } }, disabled = saving) { Text("移除加载目录") }
                }
                TextField(value = extraPath, onValueChange = { extraPath = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, placeholder = { Text("额外目录绝对路径") })
                Button(disabled = saving || !configured || extraPath.isBlank(), onClick = {
                    try {
                        val path = Path.of(extraPath.trim())
                        require(path.isAbsolute) { "请输入绝对路径" }
                        val normalized = path.normalize().toString()
                        update { it.copy(extraDirectories = (it.extraDirectories + normalized).distinct()) }
                    } catch (e: Exception) { error = e.message }
                }) { Text("添加目录") }
                Text("移除仅停止扫描，不删除文件。")
            }
        }
        TextField(value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索名称或描述") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val origins = listOf(null) + SkillOrigin.entries
            val states = listOf(null, "已启用", "已禁用", "全局已禁用", "被同名项覆盖", "加载失败")
            ComboBox(items = origins.map { it?.label() ?: "全部来源" }, selected = origins.indexOf(origin),
                onSelectionChange = { index, _ -> origin = origins[index] })
            ComboBox(items = states.map { it ?: "全部状态" }, selected = states.indexOf(status),
                onSelectionChange = { index, _ -> status = states[index] })
        }
        val filtered = filterSkills(entries, query, origin, status)
        val detail = entries.find { it.id == selected }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 800.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide || detail == null) SkillScroll(Modifier.weight(1f).fillMaxHeight()) {
                    if (filtered.isEmpty()) Text("没有匹配的 skill")
                    filtered.forEach { entry ->
                        SettingsSection(entry.manifest?.name ?: entry.directory?.fileName?.toString() ?: "目录错误",
                            "${entry.origin.label()} · ${entry.status}${entry.manifest?.version?.let { " · v$it" }.orEmpty()}") {
                            Text(entry.manifest?.description ?: entry.diagnostic.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Button(onClick = { selected = entry.id }) { Text("查看详情") }
                        }
                    }
                }
                if (detail != null) SkillScroll(Modifier.weight(1f).fillMaxHeight()) {
                    Button(onClick = { selected = null }) { Text("返回列表") }
                    Text(detail.manifest?.name ?: "无法加载", style = FluentTheme.typography.subtitle)
                    Text(detail.id)
                    Text(detail.status)
                    detail.shadowedBy?.let { Text("优先加载：$it") }
                    detail.manifest?.whenToUse?.let { Text("触发条件：$it") }
                    detail.manifest?.source?.let { Text("来源：$it") }
                    detail.diagnostic?.let { Text(it) }
                    detail.manifest?.let { manifest ->
                        Button(disabled = saving || !configured, onClick = {
                            update { current -> current.copy(disabled = if (manifest.name in current.disabled)
                                current.disabled - manifest.name else (current.disabled + manifest.name).distinct()) }
                        }) { Text(if (detail.disabled) "启用此名称" else "禁用此名称") }
                    }
                    if (SkillCatalog.canUninstall(root, detail)) {
                        Button(disabled = busy || saving, onClick = { deleting = detail }) { Text("卸载") }
                        if (busy) Text("会话正在运行，结束后可卸载。")
                    }
                    SkillContent(detail, revision)
                }
            }
        }
    }
    deleting?.let { entry ->
        ContentDialog(title = "卸载 skill", visible = true,
            content = { Text("将删除 ${entry.manifest?.name ?: "skill"} 及其全部文件：\n${entry.directory}") },
            primaryButtonText = "卸载", closeButtonText = "取消", onButtonClick = { button ->
                deleting = null
                if (button == ContentDialogButton.Primary) {
                    saving = true
                    onDeletingChanged(true)
                    scope.launch {
                        try {
                            check(!currentBusy) { "会话正在运行，请稍后卸载" }
                            withContext(Dispatchers.IO) { SkillCatalog.uninstall(root, entry) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = SecretRedactor.redact(e.message ?: "卸载失败") }
                        finally { saving = false; revision++; onRefresh(); onDeletingChanged(false) }
                    }
                }
            })
    }
}

private fun SkillOrigin.label() = when (this) { SkillOrigin.BUILTIN -> "内置"; SkillOrigin.USER -> "用户"; SkillOrigin.EXTRA -> "额外目录" }

@Composable
private fun SkillContent(entry: SkillEntry, revision: Int) {
    var attempt by remember(entry.id) { mutableStateOf(0) }
    var content by remember(entry.id) { mutableStateOf("正在读取…") }
    LaunchedEffect(entry, revision, attempt) {
        content = try { withContext(Dispatchers.IO) { entry.readContent() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { "读取失败：${SecretRedactor.redact(e.message ?: "无效 skill")}" }
    }
    Button(onClick = { attempt++ }) { Text("重新读取") }
    SelectionContainer { Text(content) }
}

@Composable
private fun SkillScroll(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxWidth().padding(end = 16.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = LocalScrollbarStyle.current.copy(
                unhoverColor = FluentTheme.colors.text.text.secondary.copy(alpha = 0.5f),
                hoverColor = FluentTheme.colors.text.text.primary,
            ))
    }
}
