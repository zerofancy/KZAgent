package com.kzagent.kagent.skill

import com.kzagent.kagent.tools.TextFileCodec
import java.nio.file.Path

/**
 * Parsed frontmatter metadata of a SKILL.md file.
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val whenToUse: String? = null,
    val version: String? = null,
    val source: String? = null,
)

/**
 * Where the SKILL.md content comes from. User skills live on disk under the
 * skills root; built-in skills are bundled on the classpath.
 */
sealed interface SkillMdSource {
    /** A file on disk under the user skills directory. */
    data class FileSystem(val path: Path) : SkillMdSource

    /** A classpath resource bundled inside the application. */
    data class Classpath(val resource: String) : SkillMdSource
}

data class Skill(
    val manifest: SkillManifest,
    val source: SkillMdSource,
    val builtin: Boolean = false,
) {
    /** Reads the full SKILL.md content (frontmatter + body) for read_skill. */
    fun readContent(): String = when (source) {
        is SkillMdSource.FileSystem -> TextFileCodec.read(source.path).text
        is SkillMdSource.Classpath -> {
            val stream = Skill::class.java.getResourceAsStream(source.resource)
                ?: error("Built-in skill resource not found: ${source.resource}")
            stream.use { String(it.readBytes(), Charsets.UTF_8) }
        }
    }
}

/**
 * Minimal YAML frontmatter parser. Only supports flat `key: value` scalar pairs
 * between `---` fences; no nested structures, lists, or anchors.
 */
object SkillFrontmatterParser {
    private val FENCE = Regex("""^---\s*$""", RegexOption.MULTILINE)

    fun parse(content: String): SkillManifest? {
        val first = FENCE.find(content) ?: return null
        val second = FENCE.find(content, first.range.last + 1) ?: return null
        val frontmatter = content.substring(first.range.last + 1, second.range.first).trim()

        val fields = mutableMapOf<String, String>()
        for (line in frontmatter.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val sep = trimmed.indexOf(':')
            if (sep <= 0) continue
            val key = trimmed.substring(0, sep).trim()
            val value = trimmed.substring(sep + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (key.isNotEmpty()) fields[key] = value
        }

        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = fields["description"]?.takeIf { it.isNotBlank() } ?: return null
        return SkillManifest(
            name = name,
            description = description,
            whenToUse = fields["when_to_use"],
            version = fields["version"],
            source = fields["source"],
        )
    }
}
