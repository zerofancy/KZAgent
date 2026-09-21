package com.kzagent.kagent.skill

import com.kzagent.kagent.config.SkillsConfig
import com.kzagent.kagent.tools.TextFileCodec
import java.nio.file.Files
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
            add("Built-in skills are read-only. Newly installed skills only appear in the list after a new session starts.")
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
        private const val SKILL_FILE = "SKILL.md"
        private const val BUILTIN_RESOURCE_ROOT = "/builtin-skills"
        private val NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

        fun load(skillsDir: Path, config: SkillsConfig): SkillRegistry {
            val all = mutableListOf<Skill>()
            all += loadBuiltinSkills(config)
            all += loadUserSkills(skillsDir, config)
            // De-duplicate by lowercase name: built-in skills win over user skills.
            val seen = linkedSetOf<String>()
            val deduped = all.filter { seen.add(it.manifest.name.lowercase()) }
            return SkillRegistry(deduped)
        }

        private fun loadBuiltinSkills(config: SkillsConfig): List<Skill> {
            if (!config.enabled) return emptyList()
            // The skill-installer skill is always available unless explicitly disabled.
            return loadBuiltinSkill("skill-installer")
                ?.takeIf { it.manifest.name !in config.disabled }
                ?.let(::listOf)
                ?: emptyList()
        }

        private fun loadBuiltinSkill(name: String): Skill? {
            val resourcePath = "$BUILTIN_RESOURCE_ROOT/$name/$SKILL_FILE"
            val stream = SkillRegistry::class.java.getResourceAsStream(resourcePath) ?: return null
            val content = stream.use { String(it.readBytes(), Charsets.UTF_8) }
            val manifest = SkillFrontmatterParser.parse(content) ?: return null
            return Skill(
                manifest = manifest,
                source = SkillMdSource.Classpath(resourcePath),
                builtin = true,
            )
        }

        private fun loadUserSkills(skillsDir: Path, config: SkillsConfig): List<Skill> {
            if (!config.enabled) return emptyList()

            val roots = buildList {
                add(skillsDir)
                config.extraDirectories.forEach { extra ->
                    runCatching { Path.of(extra).toAbsolutePath().normalize() }.getOrNull()
                        ?.let { add(it) }
                }
            }

            val skills = mutableListOf<Skill>()
            for (root in roots) {
                if (!Files.isDirectory(root)) continue
                Files.list(root).use { stream ->
                    stream.filter { Files.isDirectory(it) }.forEach { dir ->
                        loadSkillFromDir(dir, config)?.let { skills += it }
                    }
                }
            }
            return skills
        }

        private fun loadSkillFromDir(dir: Path, config: SkillsConfig): Skill? {
            val skillFile = dir.resolve(SKILL_FILE)
            if (!Files.isRegularFile(skillFile)) return null
            val content = runCatching { TextFileCodec.read(skillFile).text }.getOrNull() ?: return null
            val manifest = SkillFrontmatterParser.parse(content) ?: return null
            if (!NAME_PATTERN.matches(manifest.name)) return null
            if (manifest.name in config.disabled) return null
            return Skill(
                manifest = manifest,
                source = SkillMdSource.FileSystem(skillFile),
                builtin = false,
            )
        }
    }
}
