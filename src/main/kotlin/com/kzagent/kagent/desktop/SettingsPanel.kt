package com.kzagent.kagent.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

@Composable
fun SettingsPanel(
    initialApiKey: String,
    initialBaseUrl: String,
    initialModel: String,
    initialContextWindowSize: Int,
    initialSensitivePathProtection: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var model by remember { mutableStateOf(initialModel) }
    var contextWindowSizeText by remember { mutableStateOf(initialContextWindowSize.toString()) }
    var sensitivePathProtection by remember { mutableStateOf(initialSensitivePathProtection) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

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

    fun doSave() {
        if (!validate()) return
        val config = AppConfig(
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
            model = model.trim(),
            sensitivePathProtection = sensitivePathProtection,
            contextWindowSize = contextWindowSizeText.toInt(),
        )
        ConfigWriter.save(config)
        saved = true
        onSave()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
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
            Spacer(Modifier.height(24.dp))

            // Error message
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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
    }
}
