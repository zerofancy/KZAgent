package com.kzagent.kagent.skill

import com.kzagent.kagent.config.SkillsConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillFrontmatterParserTest {
    @Test
    fun parsesFlatScalarFields() {
        val content = """
            ---
            name: pdf
            description: Handle PDF tasks
            when_to_use: user uploads a PDF
            version: "1.0.0"
            source: https://example.com/skills
            ---
            # PDF Skill
            body here
        """.trimIndent()

        val manifest = SkillFrontmatterParser.parse(content)

        assertNotNull(manifest)
        assertEquals("pdf", manifest.name)
        assertEquals("Handle PDF tasks", manifest.description)
        assertEquals("user uploads a PDF", manifest.whenToUse)
        assertEquals("1.0.0", manifest.version)
        assertEquals("https://example.com/skills", manifest.source)
    }

    @Test
    fun returnsNullWithoutNameOrDescription() {
        assertNull(SkillFrontmatterParser.parse("no frontmatter"))
        assertNull(SkillFrontmatterParser.parse("---\ndescription: only desc\n---\nbody"))
        assertNull(SkillFrontmatterParser.parse("---\nname: only-name\n---\nbody"))
    }

    @Test
    fun stripsQuotedValuesAndIgnoresComments() {
        val content = """
            ---
            # a comment
            name: 'pdf'
            description: "Handle PDFs"
            ---
            body
        """.trimIndent()

        val manifest = SkillFrontmatterParser.parse(content)

        assertNotNull(manifest)
        assertEquals("pdf", manifest.name)
        assertEquals("Handle PDFs", manifest.description)
    }
}

class SkillRegistryTest {
    private fun config(
        enabled: Boolean = true,
        disabled: List<String> = emptyList(),
    ) = SkillsConfig(enabled = enabled, disabled = disabled)

    @Test
    fun builtinSkillInstallerIsAlwaysPresent() {
        val skillsDir = Files.createTempDirectory("kagent-skill-builtin")
        val registry = SkillRegistry.load(skillsDir, config())

        val installer = registry.find("skill-installer")
        assertNotNull(installer)
        assertTrue(installer.builtin)
        assertTrue(registry.readSkillMd(installer).contains("# Skill Installer"))
    }

    @Test
    fun loadsUserSkillFromDirectory() {
        val skillsDir = Files.createTempDirectory("kagent-skill-user")
        val pdfDir = Files.createDirectories(skillsDir.resolve("pdf"))
        Files.writeString(
            pdfDir.resolve("SKILL.md"),
            """
            ---
            name: pdf
            description: Handle PDF tasks
            version: "1.2.0"
            ---
            # PDF Skill
            body
            """.trimIndent(),
        )

        val registry = SkillRegistry.load(skillsDir, config())

        val pdf = registry.find("pdf")
        assertNotNull(pdf)
        assertEquals("pdf", pdf.manifest.name)
        assertEquals("1.2.0", pdf.manifest.version)
        assertTrue(!pdf.builtin)
        assertTrue(registry.readSkillMd(pdf).contains("# PDF Skill"))
    }

    @Test
    fun skipsDisabledAndInvalidSkills() {
        val skillsDir = Files.createTempDirectory("kagent-skill-skip")
        val valid = Files.createDirectories(skillsDir.resolve("valid"))
        Files.writeString(
            valid.resolve("SKILL.md"),
            "---\nname: valid\ndescription: ok\n---\nbody",
        )
        val badName = Files.createDirectories(skillsDir.resolve("Bad Name"))
        Files.writeString(
            badName.resolve("SKILL.md"),
            "---\nname: Bad Name\ndescription: bad\n---\nbody",
        )
        val missingFm = Files.createDirectories(skillsDir.resolve("nofm"))
        Files.writeString(missingFm.resolve("SKILL.md"), "no frontmatter")

        val registry = SkillRegistry.load(skillsDir, config(disabled = listOf("valid")))

        assertNull(registry.find("valid"))
        assertNull(registry.find("Bad Name"))
        assertNull(registry.find("nofm"))
        // builtin still present
        assertNotNull(registry.find("skill-installer"))
    }

    @Test
    fun summariesIncludeRootPathAndEntries() {
        val skillsDir = Files.createTempDirectory("kagent-skill-summary")
        val pdfDir = Files.createDirectories(skillsDir.resolve("pdf"))
        Files.writeString(
            pdfDir.resolve("SKILL.md"),
            "---\nname: pdf\ndescription: Handle PDFs\nwhen_to_use: upload PDF\n---\nbody",
        )

        val registry = SkillRegistry.load(skillsDir, config())
        val summary = registry.summaries(skillsDir)

        assertTrue(summary.contains("## Installed skills"))
        assertTrue(summary.contains(skillsDir.toString()))
        assertTrue(summary.contains("- pdf:"))
        assertTrue(summary.contains("Handle PDFs"))
        assertTrue(summary.contains("skill-installer"))
    }

    @Test
    fun disabledGlobalSwitchSkipsUserSkills() {
        val skillsDir = Files.createTempDirectory("kagent-skill-disabled")
        val pdfDir = Files.createDirectories(skillsDir.resolve("pdf"))
        Files.writeString(
            pdfDir.resolve("SKILL.md"),
            "---\nname: pdf\ndescription: x\n---\nbody",
        )

        val registry = SkillRegistry.load(skillsDir, config(enabled = false))

        assertNull(registry.find("pdf"))
        // When disabled, even built-in skill-installer is skipped per loadBuiltinSkills.
        assertNull(registry.find("skill-installer"))
    }
}
