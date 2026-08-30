package com.kzagent.kagent.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.ModelDescriptor
import com.kzagent.kagent.config.ModelSelection
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField

@Composable
internal fun ModelSelector(
    selection: ModelSelection,
    models: List<ModelDescriptor>,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    onSelect: (ModelSelection) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Button(
        onClick = { visible = true },
        disabled = !enabled,
        modifier = modifier.height(32.dp).widthIn(max = 280.dp),
    ) {
        Text(
            "${selection.provider} / ${selection.modelId}  ▾",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (!visible) return
    val filtered = remember(models.toList(), selection, query) {
        filterModelCatalog(models, selection, query)
    }
    val scrollState = rememberScrollState()
    ContentDialog(
        title = "选择 Provider 与模型",
        visible = true,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索模型名称或 ID") },
                    singleLine = true,
                    isClearable = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            loading -> "正在加载在线模型目录..."
                            error != null -> "部分目录加载失败"
                            else -> "共 ${models.size} 个可用模型"
                        },
                        color = FluentTheme.colors.text.text.secondary,
                    )
                    Button(onClick = onRefresh, disabled = loading) { Text("刷新") }
                }
                error?.let {
                    Text(it, color = FluentTheme.colors.system.critical)
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 460.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(end = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val grouped = filtered.groupBy { it.providerName.ifBlank { it.provider } }
                        grouped.forEach { (providerName, providerModels) ->
                            if (providerModels.isNotEmpty()) {
                                Text(providerName, style = FluentTheme.typography.bodyStrong)
                                providerModels.forEach { model ->
                                    Button(
                                        onClick = {
                                            visible = false
                                            onSelect(model.toSelection())
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                if (model.provider == selection.provider && model.id == selection.modelId) {
                                                    "✓ ${model.displayName}"
                                                } else model.displayName,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (model.displayName != model.id) {
                                                Text(
                                                    model.id,
                                                    color = FluentTheme.colors.text.text.secondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (filtered.isEmpty()) Text("没有匹配的模型")
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        },
        primaryButtonText = "刷新目录",
        closeButtonText = "关闭",
        onButtonClick = { button ->
            if (button == ContentDialogButton.Primary) onRefresh() else visible = false
        },
    )
}

internal fun filterModelCatalog(
    models: List<ModelDescriptor>,
    selection: ModelSelection,
    query: String,
): List<ModelDescriptor> {
    val currentDescriptor = ModelDescriptor(
        provider = selection.provider,
        id = selection.modelId,
        contextWindowSize = selection.contextWindowSize,
        supportsToolChoice = selection.supportsToolChoice,
    )
    val allModels = if (models.any { it.provider == selection.provider && it.id == selection.modelId }) {
        models
    } else {
        listOf(currentDescriptor) + models
    }
    val normalizedQuery = query.trim().lowercase()
    return allModels.filter {
        normalizedQuery.isEmpty() || it.id.lowercase().contains(normalizedQuery) ||
            it.displayName.lowercase().contains(normalizedQuery)
    }
}
