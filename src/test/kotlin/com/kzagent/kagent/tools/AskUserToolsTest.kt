package com.kzagent.kagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AskUserToolsTest {
    @Test
    fun returnsSelectedTextAndSkippedAnswersInOrder() = runBlocking {
        val registry = AskUserTools(UserQuestionPrompter {
            listOf(UserQuestionAnswer("快速"), UserQuestionAnswer(null))
        }).registry()
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("question", "如何继续？")
                    put("options", buildJsonArray {
                        add(buildJsonObject { put("label", "快速"); put("description", "快速路径") })
                    })
                })
                add(buildJsonObject { put("question", "是否部署？") })
            })
        }
        val tool = registry.get("ask_user")!!
        assertEquals(2, tool.costForArguments(args))
        val result = tool.handler(args)
        assertFalse(result.isError)
        val answers = Json.parseToJsonElement(result.content).jsonObject["answers"]!!.jsonArray
        assertEquals("answered", answers[0].jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("快速", answers[0].jsonObject["answer"]!!.jsonPrimitive.content)
        assertEquals("skipped", answers[1].jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun rejectsMoreThanThreeOptionsBeforePrompting() = runBlocking {
        var prompted = false
        val tool = AskUserTools(UserQuestionPrompter { prompted = true; emptyList() }).registry().get("ask_user")!!
        val args = buildJsonObject {
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("question", "选择")
                    put("options", buildJsonArray {
                        repeat(4) { add(buildJsonObject { put("label", "$it"); put("description", "$it") }) }
                    })
                })
            })
        }
        val result = runCatching { tool.costForArguments(args) }
        assertTrue(result.isFailure)
        assertFalse(prompted)
    }
}
