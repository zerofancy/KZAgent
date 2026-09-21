package com.kzagent.kagent.skill

import com.kzagent.kagent.tools.ToolDefinition
import com.kzagent.kagent.tools.ToolResult
import com.kzagent.kagent.tools.objectSchema
import com.kzagent.kagent.tools.stringSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing required string argument: $name")

/**
 * The `read_skill` tool: loads the full SKILL.md content of an installed skill by name.
 */
fun SkillRegistry.readSkillTool(): ToolDefinition = ToolDefinition(
    name = "read_skill",
    description = "Load the full SKILL.md instructions of an installed skill by name. Call this when a task matches an installed skill's trigger (listed in the installed skills section of the system prompt). Do not guess from the summary alone.",
    parameters = objectSchema(
        properties = mapOf(
            "name" to stringSchema("Skill name from the installed skills list."),
        ),
        required = listOf("name"),
    ),
    requiresApproval = false,
    cost = 1,
) { args ->
    val name = args.requiredString("name")
    val skill = find(name) ?: return@ToolDefinition ToolResult.error("Skill not found: $name")
    ToolResult.ok(readSkillMd(skill))
}
