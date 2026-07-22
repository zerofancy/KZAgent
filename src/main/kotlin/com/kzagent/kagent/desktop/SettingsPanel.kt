package com.kzagent.kagent.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ConfigWriter
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

@Composable
fun SettingsPanel(
    initialApiKey: String,
    initialBaseUrl: String,
    initialModel: String,
    initialContextWindowSize: Int,
    initialSensitivePathProtection: Boolean,
    initialUserPrompt: String,
    initialApprovalMode: ApprovalMode,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var model by remember { mutableStateOf(initialModel) }
    var contextWindowSizeText by remember { mutableStateOf(initialContextWindowSize.toString()) }
    var sensitivePathProtection by remember { mutableStateOf(initialSensitivePathProtection) }
    var userPrompt by remember { mutableStateOf(initialUserPrompt) }
    var approvalMode by remember { mutableStateOf(initialApprovalMode) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var confirmFullMode by remember { mutableStateOf(false) }

    fun validate(): Boolean {
        if (apiKey.isBlank()) {
            errorMessage = "API Key 不能为空"
            return false
        }
        if (baseUrl.isBlank()) {
            errorMessage = "Base URL 不能为空"
            return false
        }
        if (model.isBlank()) {
            errorMessage = "模型名称不能为空"
            return false
        }
        val ctxSize = contextWindowSizeText.toIntOrNull()
        if (ctxSize == null || ctxSize <= 0) {
            errorMessage = "上下文窗口大小必须为正整数"
            return false
        }
        return true
    }

    fun persistConfig() {
        val config = AppConfig(
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
            model = model.trim(),
            sensitivePathProtection = sensitivePathProtection,
            contextWindowSize = contextWindowSizeText.toInt(),
            userPrompt = userPrompt.trim(),
            approvalMode = approvalMode,
        )
        ConfigWriter.save(config)
        saved = true
        onSave()
    }

    fun doSave() {
        if (!validate()) return
        if (requiresFullModeConfirmation(initialApprovalMode, approvalMode)) {
            confirmFullMode = true
        } else {
            persistConfig()
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
                    description = "连接 DeepSeek 兼容的 Chat Completions 服务。API Key 只保存在本机配置中。",
                ) {
                    SettingsTextField(
                        label = "DeepSeek API Key",
                        value = apiKey,
                        onValueChange = { apiKey = it; errorMessage = null },
                        placeholder = "sk-...",
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    SettingsTextField(
                        label = "Base URL",
                        value = baseUrl,
                        onValueChange = { baseUrl = it; errorMessage = null },
                        placeholder = "https://api.deepseek.com",
                    )
                    SettingsTextField(
                        label = "模型",
                        value = model,
                        onValueChange = { model = it; errorMessage = null },
                        placeholder = "deepseek-chat",
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

                errorMessage?.let { message ->
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
                AccentButton(onClick = { doSave() }) {
                    Text(if (saved) "已保存" else "保存设置")
                }
            }
        }
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
                persistConfig()
            }
        },
    )
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Layer(
        modifier = Modifier.fillMaxWidth(),
        shape = FluentTheme.shapes.overlay,
        color = FluentTheme.colors.background.layer.default,
        border = BorderStroke(1.dp, FluentTheme.colors.stroke.card.default),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = FluentTheme.typography.subtitle)
                Text(
                    description,
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        header = { Text(label, style = FluentTheme.typography.bodyStrong) },
        placeholder = { Text(placeholder) },
    )
}

@Composable
private fun ApprovalModeOption(
    mode: ApprovalMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val title = when (mode) {
        ApprovalMode.AUTO -> "自动审批（默认）"
        ApprovalMode.MANUAL -> "手动审批"
        ApprovalMode.FULL -> "全部放行"
    }
    val description = when (mode) {
        ApprovalMode.AUTO -> "安全操作自动放行，其余由审批 Agent 或人工判断。"
        ApprovalMode.MANUAL -> "所有命令和受保护文件读取都由人工确认。"
        ApprovalMode.FULL -> "以当前系统用户权限直接执行，并允许读取工作区外文件。"
    }
    val borderColor = if (selected) {
        FluentTheme.colors.fillAccent.default
    } else {
        FluentTheme.colors.stroke.card.default
    }

    Layer(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = FluentTheme.shapes.control,
        color = if (selected) {
            FluentTheme.colors.background.layer.alt
        } else {
            FluentTheme.colors.background.layer.default
        },
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = FluentTheme.typography.bodyStrong)
                Text(
                    description,
                    style = FluentTheme.typography.caption,
                    color = if (mode == ApprovalMode.FULL) {
                        FluentTheme.colors.system.critical
                    } else {
                        FluentTheme.colors.text.text.secondary
                    },
                )
            }
        }
    }
}
