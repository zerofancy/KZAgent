package com.kzagent.kagent.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.tools.ApprovalMode
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Layer
import io.github.composefluent.component.RadioButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField

@Composable
internal fun SettingsSection(
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
internal fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
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
internal fun ApprovalModeOption(
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
