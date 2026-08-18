package com.kzagent.kagent.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.tools.UserQuestion
import com.kzagent.kagent.tools.UserQuestionAnswer
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Text as FluentText
import io.github.composefluent.component.TextField as FluentTextField
import kotlinx.coroutines.delay

internal data class PendingUserQuestions(
    val questions: List<UserQuestion>,
    val complete: (List<UserQuestionAnswer>) -> Unit,
)

@Composable
internal fun UserQuestionDialog(pending: PendingUserQuestions) {
    var index by remember(pending) { mutableStateOf(0) }
    var draft by remember(pending, index) { mutableStateOf("") }
    val answers = remember(pending) { MutableList<UserQuestionAnswer?>(pending.questions.size) { null } }
    val question = pending.questions[index]
    fun advance(answer: String?) {
        answers[index] = UserQuestionAnswer(answer?.trim()?.takeIf { it.isNotEmpty() })
        if (index == pending.questions.lastIndex) pending.complete(answers.map { it ?: UserQuestionAnswer(null) })
        else index++
    }
    LaunchedEffect(pending, index) {
        delay(5 * 60 * 1000L)
        advance(null)
    }
    ContentDialog(
        title = "需要你的回答（${index + 1}/${pending.questions.size}）",
        visible = true,
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FluentText(question.question)
                question.options.forEach { option ->
                    Button(onClick = { draft = option.label }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.Start) {
                            FluentText(option.label)
                            FluentText(option.description)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                FluentTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    header = { FluentText("你的回答（可自由输入）") },
                    singleLine = false,
                )
            }
        },
        primaryButtonText = "提交",
        closeButtonText = "跳过",
        onButtonClick = { button ->
            if (button == ContentDialogButton.Primary) advance(draft) else advance(null)
        },
    )
}
