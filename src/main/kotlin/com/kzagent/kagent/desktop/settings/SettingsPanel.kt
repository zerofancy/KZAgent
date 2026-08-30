package com.kzagent.kagent.desktop


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ProviderConfig
import com.kzagent.kagent.config.ProviderKind
import com.kzagent.kagent.tools.ApprovalMode
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Layer
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.CheckBox
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.RadioButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField

internal data class UserCommandButtonPresentation(
    val text: String,
    val disabled: Boolean,
)

internal fun userCommandButtonPresentation(
    available: Boolean,
    installed: Boolean,
    installing: Boolean,
): UserCommandButtonPresentation = UserCommandButtonPresentation(
    text = when {
        installing -> "正在安装..."
        installed -> "重新安装 kza 命令"
        else -> "安装 kza 命令"
    },
    disabled = !available || installing,
)

@Composable
fun SettingsPanel(
    initialProviders: List<ProviderConfig>,
    initialDefaultModel: ModelSelection,
    initialContextWindowSize: Int,
    initialSensitivePathProtection: Boolean,
    initialUserPrompt: String,
    initialApprovalMode: ApprovalMode,
    availableModels: List<ModelDescriptor> = emptyList(),
    modelsLoading: Boolean = false,
    modelsError: String? = null,
    onRefreshModels: () -> Unit = {},
    saving: Boolean = false,
    saveError: String? = null,
    commandAvailable: Boolean = false,
    commandInstalled: Boolean = false,
    commandPath: String? = null,
    commandUnavailableReason: String? = null,
    commandInstalling: Boolean = false,
    commandInstallMessage: String? = null,
    commandInstallFailed: Boolean = false,
    onInstallCommand: () -> Unit = {},
    onSave: (AppConfig) -> Unit,
    onCancel: () -> Unit,
) {
    val commandButton = userCommandButtonPresentation(
        available = commandAvailable,
        installed = commandInstalled,
        installing = commandInstalling,
    )
    var providers by remember {
        mutableStateOf(
            initialProviders.ifEmpty { emptyList() }.map { ProviderEditor(it.id, it.name, it.kind, it.apiKey, it.baseUrl) },
        )
    }
    var defaultProvider by remember { mutableStateOf(initialDefaultModel.provider) }
    var defaultModelId by remember { mutableStateOf(initialDefaultModel.modelId) }
    var defaultModelContextWindowSize by remember { mutableStateOf(initialDefaultModel.contextWindowSize) }
    var defaultSupportsToolChoice by remember { mutableStateOf(initialDefaultModel.supportsToolChoice) }
    var contextWindowSizeText by remember { mutableStateOf(initialContextWindowSize.toString()) }
    var sensitivePathProtection by remember { mutableStateOf(initialSensitivePathProtection) }
    var userPrompt by remember { mutableStateOf(initialUserPrompt) }
    var approvalMode by remember { mutableStateOf(initialApprovalMode) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmFullMode by remember { mutableStateOf(false) }
    var showAddProvider by remember { mutableStateOf(false) }

    fun updateProvider(index: Int, transform: (ProviderEditor) -> ProviderEditor) {
        providers = providers.mapIndexed { i, item -> if (i == index) transform(item) else item }
        errorMessage = null
    }

    fun validate(): Boolean {
        val nonBlank = providers.filter { it.apiKey.isNotBlank() }
        if (nonBlank.isEmpty()) {
            errorMessage = "至少需要配置一个 Provider 的 API Key"
            return false
        }
        for (p in providers) {
            if (p.id.isBlank()) {
                errorMessage = "Provider ID 不能为空"
                return false
            }
            if (p.name.isBlank()) {
                errorMessage = "Provider 名称不能为空"
                return false
            }
            if (p.apiKey.isNotBlank() && p.baseUrl.isBlank()) {
                errorMessage = "Provider「${p.name.ifBlank { p.id }}」Base URL 不能为空"
                return false
            }
        }
        val ids = providers.map { it.id.trim() }
        if (ids.size != ids.toSet().size) {
            errorMessage = "Provider ID 不能重复"
            return false
        }
        if (defaultProvider !in ids) {
            errorMessage = "默认 Provider 尚未配置 API Key"
            return false
        }
        if (defaultModelId.isBlank()) {
            errorMessage = "默认模型 ID 不能为空"
            return false
        }
        val ctxSize = contextWindowSizeText.toIntOrNull()
        if (ctxSize == null || ctxSize <= 0) {
            errorMessage = "上下文窗口大小必须为正整数"
            return false
        }
        return true
    }

    fun submitConfig() {
        val config = AppConfig(
            providers = providers.map { it.toConfig() },
            defaultModel = ModelSelection(
                provider = defaultProvider,
                modelId = defaultModelId.trim(),
                contextWindowSize = defaultModelContextWindowSize ?: contextWindowSizeText.toInt(),
                supportsToolChoice = defaultSupportsToolChoice,
            ),
            sensitivePathProtection = sensitivePathProtection,
            contextWindowSize = contextWindowSizeText.toInt(),
            userPrompt = userPrompt.trim(),
            approvalMode = approvalMode,
        )
        onSave(config)
    }

    fun doSave() {
        if (!validate()) return
        if (initialApprovalMode != ApprovalMode.FULL && approvalMode == ApprovalMode.FULL) {
            confirmFullMode = true
        } else {
            submitConfig()
        }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FluentTheme.colors.background.layer.alt),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 880.dp)
                    .verticalScroll(scrollState)
                    .padding(start = 32.dp, top = 28.dp, end = 44.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("设置", style = FluentTheme.typography.titleLarge)
                    Text(
                        "配置模型连接、安全边界和 Agent 的长期行为。",
                        style = FluentTheme.typography.body,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                }

                SettingsSection(
                    title = "模型与 API",
                    description = "配置一个或多个 OpenAI 兼容 Provider。API Key 只保存在本机配置中。",
                ) {
                    providers.forEachIndexed { index, provider ->
                        ProviderEditorCard(
                            provider = provider,
                            isDefault = defaultProvider == provider.id,
                            onDefaultChanged = {
                                defaultProvider = provider.id
                                if (provider.kind == ProviderKind.DEEPSEEK && defaultModelId.startsWith("openrouter/")) {
                                    defaultModelId = AppConfig.DEFAULT_MODEL
                                } else if (provider.kind == ProviderKind.OPENROUTER && !defaultModelId.contains('/')) {
                                    defaultModelId = "openrouter/auto"
                                }
                                defaultModelContextWindowSize = null
                                defaultSupportsToolChoice = true
                                errorMessage = null
                            },
                            onIdChange = { value -> updateProvider(index) { it.copy(id = value) } },
                            onNameChange = { value -> updateProvider(index) { it.copy(name = value) } },
                            onApiKeyChange = { value -> updateProvider(index) { it.copy(apiKey = value) } },
                            onBaseUrlChange = { value -> updateProvider(index) { it.copy(baseUrl = value) } },
                            onRemove = {
                                val removing = providers[index]
                                providers = providers.filterIndexed { i, _ -> i != index }
                                if (defaultProvider == removing.id) {
                                    defaultProvider = providers.firstOrNull()?.id.orEmpty()
                                }
                                errorMessage = null
                            },
                            removable = providers.size > 1,
                        )
                    }
                    Button(onClick = { showAddProvider = true }) {
                        Text("添加 Provider")
                    }
                    Text("新会话与 CLI 默认 Provider", style = FluentTheme.typography.bodyStrong)
                    if (providers.isEmpty()) {
                        Text(
                            "请先添加至少一个 Provider。",
                            color = FluentTheme.colors.text.text.secondary,
                        )
                    } else {
                        providers.forEach { provider ->
                            val fullyConfigured = provider.apiKey.isNotBlank()
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = fullyConfigured) {
                                    defaultProvider = provider.id
                                    errorMessage = null
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = defaultProvider == provider.id,
                                    onClick = {
                                        defaultProvider = provider.id
                                        errorMessage = null
                                    }.takeIf { fullyConfigured },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("${provider.name}（${provider.id}）")
                            }
                        }
                    }
                    SettingsTextField(
                        label = "默认模型 ID（保存后可在对话页从在线目录选择）",
                        value = defaultModelId,
                        onValueChange = {
                            if (it != defaultModelId) {
                                defaultModelContextWindowSize = null
                                defaultSupportsToolChoice = true
                            }
                            defaultModelId = it
                            errorMessage = null
                        },
                        placeholder = "deepseek-v4-pro 或 openai/gpt-4.1",
                    )
                    Text("在线模型目录", style = FluentTheme.typography.bodyStrong)
                    ModelSelector(
                        selection = ModelSelection(
                            provider = defaultProvider,
                            modelId = defaultModelId.ifBlank { AppConfig.DEFAULT_MODEL },
                            contextWindowSize = defaultModelContextWindowSize,
                            supportsToolChoice = defaultSupportsToolChoice,
                        ),
                        models = availableModels,
                        loading = modelsLoading,
                        error = modelsError,
                        enabled = true,
                        onSelect = { selected ->
                            defaultProvider = selected.provider
                            defaultModelId = selected.modelId
                            defaultModelContextWindowSize = selected.contextWindowSize
                            defaultSupportsToolChoice = selected.supportsToolChoice
                            errorMessage = null
                        },
                        onRefresh = onRefreshModels,
                    )
                    SettingsTextField(
                        label = "上下文窗口大小",
                        value = contextWindowSizeText,
                        onValueChange = { contextWindowSizeText = it; errorMessage = null },
                        placeholder = "tokens",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                SettingsSection(
                    title = "安全与审批",
                    description = "决定 Agent 访问文件和运行命令时采用的保护策略。",
                ) {
                    CheckBox(
                        checked = sensitivePathProtection,
                        label = "敏感路径保护",
                        onCheckStateChange = { sensitivePathProtection = it },
                    )
                    Text(
                        "阻止直接访问密钥、系统配置等敏感位置。",
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("命令与外部读取审批", style = FluentTheme.typography.bodyStrong)
                    ApprovalMode.entries.forEach { mode ->
                        ApprovalModeOption(
                            mode = mode,
                            selected = approvalMode == mode,
                            onClick = { approvalMode = mode },
                        )
                    }
                }

                SettingsSection(
                    title = "用户提示词",
                    description = "附加在系统提示词之后的自定义规则，不受上下文压缩影响。",
                ) {
                    TextField(
                        value = userPrompt,
                        onValueChange = { userPrompt = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 240.dp),
                        placeholder = {
                            Text("例如：遵循 Kotlin 官方编码规范，优先使用不可变数据结构")
                        },
                        maxLines = 12,
                        isClearable = false,
                    )
                }

                SettingsSection(
                    title = "命令行",
                    description = "将 kza 安装为当前用户可用的全局命令，不修改系统级 PATH。",
                ) {
                    Text(
                        when {
                            commandPath != null && commandInstalled -> "当前安装位置：$commandPath"
                            commandUnavailableReason != null -> commandUnavailableReason
                            else -> "安装后可使用 kza、kza ask、kza chat 和 kza app。"
                        },
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                    Button(
                        onClick = onInstallCommand,
                        disabled = commandButton.disabled,
                    ) {
                        Text(commandButton.text)
                    }
                    commandInstallMessage?.let { message ->
                        InfoBar(
                            title = {
                                Text(if (commandInstallFailed) "安装失败" else "安装成功")
                            },
                            message = { Text(message) },
                            severity = if (commandInstallFailed) {
                                InfoBarSeverity.Critical
                            } else {
                                InfoBarSeverity.Success
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                (errorMessage ?: saveError)?.let { message ->
                    InfoBar(
                        title = { Text("无法保存设置") },
                        message = { Text(message) },
                        severity = InfoBarSeverity.Critical,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
            )
        }

        Layer(
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            color = FluentTheme.colors.background.layer.default,
            border = BorderStroke(1.dp, FluentTheme.colors.stroke.divider.default),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth()
                    .widthIn(max = 880.dp)
                    .padding(horizontal = 32.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onCancel) {
                    Text("返回")
                }
                Spacer(Modifier.width(8.dp))
                AccentButton(onClick = { if (!saving) doSave() }) {
                    Text(if (saving) "正在保存..." else "保存设置")
                }
            }
        }
    }

    if (showAddProvider) {
        AddProviderDialog(
            onDismiss = { showAddProvider = false },
            onAdd = { template ->
                showAddProvider = false
                val usedIds = providers.map { it.id.trim() }.toMutableSet()
                var candidate = slugify(template.kind.name)
                var n = 1
                while (candidate in usedIds) {
                    candidate = "${slugify(template.kind.name)}-$n"
                    n++
                }
                providers = providers + ProviderEditor(
                    id = candidate,
                    name = template.name,
                    kind = template.kind,
                    apiKey = "",
                    baseUrl = template.defaultBaseUrl,
                )
                errorMessage = null
            },
        )
    }

    ContentDialog(
        title = "确认全部放行",
        visible = confirmFullMode,
        content = {
            Text(
                "全部放行模式会以当前系统用户权限直接执行命令，并允许读取工作区外或敏感文件，" +
                    "不会再调用审批 Agent 或弹出人工确认。确定保存吗？",
            )
        },
        primaryButtonText = "我了解风险，继续",
        closeButtonText = "取消",
        onButtonClick = { button ->
            confirmFullMode = false
            if (button == ContentDialogButton.Primary) {
                submitConfig()
            }
        },
    )
}

private fun slugify(value: String): String = value.lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')




