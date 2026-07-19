package com.kzagent.kagent.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.ConfigWriter
import com.kzagent.kagent.tools.ApprovalMode

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
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(start = 32.dp, top = 32.dp, end = 44.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                Text(
                    "设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(24.dp))

                // API Key
                Text("DeepSeek API Key", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key (sk-...)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(16.dp))

                // Base URL
                Text("Base URL", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))

                // Model
                Text("模型", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))

                // Context Window Size
                Text("上下文窗口大小", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = contextWindowSizeText,
                    onValueChange = { contextWindowSizeText = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("上下文窗口大小 (tokens)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(16.dp))

                // Sensitive Path Protection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = sensitivePathProtection,
                        onCheckedChange = { sensitivePathProtection = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("敏感路径保护", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(20.dp))

                Text("命令与外部读取审批", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                ApprovalMode.entries.forEach { mode ->
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = approvalMode == mode,
                            onClick = { approvalMode = mode },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mode == ApprovalMode.FULL) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))

                // User Prompt
                Text("用户提示词", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "附加在系统提示词之后的自定义规则（如编码规范、执行偏好等），不受上下文压缩影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = userPrompt,
                    onValueChange = { userPrompt = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 240.dp),
                    label = { Text("例如：遵循 Kotlin 官方编码规范，优先使用不可变数据结构") },
                    maxLines = 12,
                )
                Spacer(Modifier.height(20.dp))

                // Error message
                errorMessage?.let { msg ->
                    ErrorText(msg)
                    Spacer(Modifier.height(12.dp))
                }

                // Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { doSave() }) {
                        Text(if (saved) "已保存 ✓" else "保存")
                    }
                    OutlinedButton(onClick = onCancel) {
                        Text("返回")
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp),
            )
        }
    }

    if (confirmFullMode) {
        AlertDialog(
            onDismissRequest = { confirmFullMode = false },
            title = { Text("确认全部放行") },
            text = {
                Text(
                    "全部放行模式会以当前系统用户权限直接执行命令，并允许读取工作区外或敏感文件，" +
                        "不会再调用审批 Agent 或弹出人工确认。确定保存吗？",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmFullMode = false
                    persistConfig()
                }) {
                    Text("我了解风险，继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFullMode = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ErrorText(message: String) {
    SelectionContainer {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}
