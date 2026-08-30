package com.kzagent.kagent.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.ProviderKind
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Layer
import io.github.composefluent.component.Button
import io.github.composefluent.component.Text

@Composable
internal fun ProviderEditorCard(
    provider: ProviderEditor,
    isDefault: Boolean,
    onDefaultChanged: () -> Unit,
    onIdChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onRemove: () -> Unit,
    removable: Boolean,
) {
    Layer(
        modifier = Modifier.fillMaxWidth(),
        shape = FluentTheme.shapes.overlay,
        color = FluentTheme.colors.background.layer.default,
        border = BorderStroke(
            1.dp,
            if (isDefault) FluentTheme.colors.fillAccent.default else FluentTheme.colors.stroke.card.default,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(provider.name.ifBlank { "未命名 Provider" }, style = FluentTheme.typography.subtitle)
                if (removable) {
                    Button(onClick = onRemove) { Text("删除") }
                }
            }
            Text(
                "类型：${provider.kind.displayName}${if (isDefault) "  ·  默认" else ""}",
                style = FluentTheme.typography.caption,
                color = if (isDefault) FluentTheme.colors.fillAccent.default else FluentTheme.colors.text.text.secondary,
            )
            SettingsTextField(
                label = "Provider ID（唯一标识，用于持久化）",
                value = provider.id,
                onValueChange = onIdChange,
                placeholder = "deepseek",
            )
            SettingsTextField(
                label = "Provider 名称",
                value = provider.name,
                onValueChange = onNameChange,
                placeholder = "DeepSeek",
            )
            SettingsTextField(
                label = "API Key",
                value = provider.apiKey,
                onValueChange = onApiKeyChange,
                placeholder = if (provider.kind == ProviderKind.OPENROUTER) "sk-or-..." else "sk-...",
                visualTransformation = PasswordVisualTransformation(),
            )
            SettingsTextField(
                label = "Base URL",
                value = provider.baseUrl,
                onValueChange = onBaseUrlChange,
                placeholder = if (provider.kind == ProviderKind.OPENROUTER) {
                    "https://openrouter.ai/api/v1"
                } else {
                    "https://api.deepseek.com"
                },
            )
        }
    }
}
