package com.kzagent.kagent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.composefluent.component.AccentButton as FluentAccentButton
import io.github.composefluent.component.Text as FluentText

@Composable
internal fun Composer(input: String, isBusy: Boolean, enabled: Boolean, onInputChange: (String) -> Unit,
    onSend: () -> Unit, onTerminate: () -> Unit) {
    val isMacOs = remember { System.getProperty("os.name").lowercase().contains("mac") }
    var fieldValue by remember { mutableStateOf(TextFieldValue(input, TextRange(input.length))) }
    LaunchedEffect(input) {
        if (input != fieldValue.text) fieldValue = TextFieldValue(input, TextRange(input.length))
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it; onInputChange(it.text) },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 68.dp, max = 148.dp).onPreviewKeyEvent { event ->
                    when (resolveComposerKeyAction(event.key == Key.Enter, event.isCtrlPressed, event.isMetaPressed,
                        isMacOs, event.type, isBusy)) {
                        ComposerKeyAction.InsertLineBreak -> {
                            val updatedValue = insertLineBreak(fieldValue)
                            fieldValue = updatedValue
                            onInputChange(updatedValue.text)
                            true
                        }
                        ComposerKeyAction.Send -> { onSend(); true }
                        ComposerKeyAction.Terminate -> { onTerminate(); true }
                        ComposerKeyAction.Consume -> true
                        ComposerKeyAction.PassThrough -> false
                    }
                },
                placeholder = { Text("向 KZAgent 描述任务…") }, maxLines = 6, shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.width(10.dp))
            if (isBusy) {
                Button(onClick = onTerminate,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
                    Text("终止")
                }
            } else {
                FluentAccentButton(onClick = onSend, disabled = !enabled || input.isBlank(),
                    modifier = Modifier.height(40.dp).widthIn(min = 76.dp)) { FluentText("发送  ↑") }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(if (isMacOs) "Enter 发送  ·  Command+Enter 换行" else "Enter 发送  ·  Ctrl+Enter 换行",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
}

internal enum class ComposerKeyAction { InsertLineBreak, Send, Terminate, Consume, PassThrough }

internal fun resolveComposerKeyAction(isEnter: Boolean, isCtrlPressed: Boolean, isMetaPressed: Boolean,
    isMacOs: Boolean, eventType: KeyEventType, isBusy: Boolean): ComposerKeyAction = when {
    !isEnter -> ComposerKeyAction.PassThrough
    (if (isMacOs) isMetaPressed else isCtrlPressed) ->
        if (eventType == KeyEventType.KeyDown) ComposerKeyAction.InsertLineBreak else ComposerKeyAction.Consume
    isCtrlPressed -> ComposerKeyAction.PassThrough
    eventType != KeyEventType.KeyDown -> ComposerKeyAction.Consume
    isBusy -> ComposerKeyAction.Terminate
    else -> ComposerKeyAction.Send
}

internal fun insertLineBreak(value: TextFieldValue): TextFieldValue {
    val selectionStart = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val selectionEnd = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val updatedText = value.text.replaceRange(selectionStart, selectionEnd, "\n")
    return value.copy(text = updatedText, selection = TextRange(selectionStart + 1), composition = null)
}
