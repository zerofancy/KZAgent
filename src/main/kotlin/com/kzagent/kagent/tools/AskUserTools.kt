package com.kzagent.kagent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class AskUserTools(private val prompter: UserQuestionPrompter) {
    fun registry() = ToolRegistry(listOf(ToolDefinition(
        name = "ask_user",
        description = "Ask the user one or more clarification questions. Each question costs 1 credit. Present only information needed to proceed; a skipped answer means the user declined that question.",
        parameters = objectSchema(mapOf("questions" to buildJsonObject {
            put("type", "array"); put("minItems", 1)
            put("items", objectSchema(mapOf(
                "question" to stringSchema("Question shown to the user."),
                "options" to buildJsonObject {
                    put("type", "array"); put("maxItems", 3)
                    put("items", objectSchema(mapOf(
                        "label" to stringSchema("Short option title."),
                        "description" to stringSchema("Explanation of this option."),
                    ), listOf("label", "description")))
                },
            ), listOf("question")))
        }), listOf("questions")),
        requiresApproval = false,
        cost = 1,
        costForArguments = { parseQuestions(it).size },
    ) { args ->
        val questions = parseQuestions(args)
        val answers = prompter.ask(questions)
        require(answers.size == questions.size) { "Question prompter returned an incomplete answer set." }
        ToolResult.ok(buildJsonObject {
            put("answers", buildJsonArray {
                answers.forEachIndexed { index, item -> add(buildJsonObject {
                    put("question_index", index + 1)
                    put("status", if (item.answer == null) "skipped" else "answered")
                    item.answer?.let { put("answer", it) }
                }) }
            })
        }.toString())
    }))

    private fun parseQuestions(args: kotlinx.serialization.json.JsonObject): List<UserQuestion> {
        val array = args["questions"] as? JsonArray ?: throw IllegalArgumentException("questions must be an array.")
        require(array.isNotEmpty()) { "questions must not be empty." }
        return array.mapIndexed { index, raw ->
            val item = raw.jsonObject
            val question = (item["question"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            require(question.isNotEmpty()) { "questions[$index].question is required." }
            val options = (item["options"] as? JsonArray).orEmpty()
            require(options.size <= 3) { "questions[$index].options must contain at most 3 items." }
            UserQuestion(question, options.mapIndexed { optionIndex, optionRaw ->
                val option = optionRaw.jsonObject
                val label = (option["label"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val description = (option["description"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                require(label.isNotEmpty() && description.isNotEmpty()) { "questions[$index].options[$optionIndex] requires label and description." }
                UserQuestionOption(label, description)
            })
        }
    }
}
