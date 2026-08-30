package com.kzagent.kagent.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.ProviderTemplate
import com.kzagent.kagent.config.ProviderTemplates
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Layer
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.Text

@Composable
internal fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (ProviderTemplate) -> Unit,
) {
    ContentDialog(
        title = "添加 Provider",
        visible = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "选择要添加的 Provider 类型。自定义类型使用 OpenAI 兼容的 /models 与 /chat/completions 接口。",
                    color = FluentTheme.colors.text.text.secondary,
                )
                ProviderTemplates.all.forEach { template ->
                    Layer(
                        modifier = Modifier.fillMaxWidth().clickable { onAdd(template) },
                        shape = FluentTheme.shapes.control,
                        color = FluentTheme.colors.background.layer.alt,
                        border = BorderStroke(1.dp, FluentTheme.colors.stroke.card.default),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(template.name, style = FluentTheme.typography.bodyStrong)
                            Text(
                                if (template.defaultBaseUrl.isNotBlank()) {
                                    template.defaultBaseUrl
                                } else {
                                    "自定义 OpenAI 兼容端点"
                                },
                                color = FluentTheme.colors.text.text.secondary,
                                style = FluentTheme.typography.caption,
                            )
                        }
                    }
                }
            }
        },
        primaryButtonText = "关闭",
        closeButtonText = "取消",
        onButtonClick = { onDismiss() },
    )
}
