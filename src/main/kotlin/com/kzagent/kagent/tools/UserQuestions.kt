package com.kzagent.kagent.tools

data class UserQuestion(val question: String, val options: List<UserQuestionOption> = emptyList())
data class UserQuestionOption(val label: String, val description: String)
data class UserQuestionAnswer(val answer: String?)

fun interface UserQuestionPrompter {
    suspend fun ask(questions: List<UserQuestion>): List<UserQuestionAnswer>
}
