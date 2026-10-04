package com.kzagent.kagent.skill

import com.kzagent.kagent.config.SkillsConfig
import java.nio.file.Path

/**
 * Loads built-in skills from the classpath and user-installed skills from the
 * skills root directory, filters disabled ones, and produces the compact summary
 * injected into the system prompt.
 */
class SkillRegistry private constructor(
    private val skills: List<Skill>,
) {
    fun find(name: String): Skill? =
        skills.firstOrNull { it.manifest.name.equals(name.trim(), ignoreCase = true) }

    fun readSkillMd(skill: Skill): String = skill.readContent()

    /**
     * Builds the compact Markdown summary injected into the system prompt.
     * The skills root path is included so the model knows where to install skills.
     */
    fun summaries(skillsDir: Path): String {
        if (skills.isEmpty()) return ""
        val lines = buildList {
            add("## Installed skills")
            add(
                "The following skills are available. When a task matches a skill's " +
                    "`when_to_use`, call the `read_skill` tool with its name to load the " +
                    "full instructions before proceeding. Do not guess from the summary alone."
            )
            add("")
            add("User skills are installed under: $skillsDir")
            add("Built-in skills are read-only. After installation, refresh the desktop Skills page before the next turn, or restart the CLI session.")
            add("")
            for (skill in skills) {
                val m = skill.manifest
                val version = m.version?.let { " (v$it)" } ?: ""
                val builtin = if (skill.builtin) " [builtin]" else ""
                val whenPart = m.whenToUse?.let { " when: $it" } ?: ""
                add("- ${m.name}$version$builtin: ${m.description}$whenPart")
            }
        }
        return lines.joinToString("\n")
    }

    companion object {
        fun load(skillsDir: Path, config: SkillsConfig): SkillRegistry = SkillRegistry(
            (if (config.enabled) SkillCatalog.scan(skillsDir, config) else emptyList()).filter { it.active }.map {
                Skill(requireNotNull(it.manifest), requireNotNull(it.source), it.origin == SkillOrigin.BUILTIN)
            }
        )
    }
}
