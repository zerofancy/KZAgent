package com.kzagent.kagent.skill

import com.kzagent.kagent.config.SkillsConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SkillCatalogTest {
    private fun skill(root: Path, dir: String, name: String = dir): Path {
        val path = Files.createDirectories(root.resolve(dir))
        Files.writeString(path.resolve("SKILL.md"), "---\nname: $name\ndescription: test skill\n---\nbody")
        return path
    }

    @Test fun catalogPreservesDisabledBrokenAndShadowedEntries() {
        val root = Files.createTempDirectory("skills-catalog")
        val extra = Files.createTempDirectory("skills-extra")
        skill(root, "b", "same")
        skill(root, "a", "same")
        skill(extra, "same")
        skill(root, "installer-copy", "skill-installer")
        Files.createDirectory(root.resolve("broken"))
        val config = SkillsConfig(disabled = listOf("same"), extraDirectories = listOf(extra.toString()))
        val entries = SkillCatalog.scan(root, config)
        val same = entries.filter { it.manifest?.name == "same" }
        assertEquals(3, same.size)
        assertTrue(same.all { it.disabled && !it.active })
        assertNull(same.first().shadowedBy)
        assertEquals("a", same.first().directory?.fileName.toString())
        assertTrue(same.drop(1).all { it.shadowedBy == same.first().id })
        assertTrue(entries.any { it.diagnostic != null })
        assertEquals(SkillOrigin.BUILTIN, entries.single { it.active }.origin)
        assertNull(SkillRegistry.load(root, config).find("same"))
        val off = SkillCatalog.scan(root, config.copy(enabled = false))
        assertEquals(entries.size, off.size)
        assertTrue(off.none { it.active })
        assertNotNull(SkillRegistry.load(root, config.copy(disabled = emptyList())).find("same"))
    }

    @Test fun invalidRootDoesNotHideOtherSkills() {
        val root = Files.createTempDirectory("skills-roots")
        skill(root, "valid")
        val file = Files.createTempFile("not-a-directory", ".txt")
        val entries = SkillCatalog.scan(root, SkillsConfig(extraDirectories = listOf(file.toString())))
        assertTrue(entries.any { it.manifest?.name == "valid" && it.active })
        assertTrue(entries.any { it.origin == SkillOrigin.EXTRA && it.diagnostic != null })
    }

    @Test fun uninstallOnlyDeletesOwnedDirectChild() {
        val root = Files.createTempDirectory("skills-uninstall")
        val extra = Files.createTempDirectory("skills-external")
        val target = skill(root, "owned")
        Files.createDirectories(target.resolve("nested"))
        Files.writeString(target.resolve("nested/file.txt"), "data")
        val external = skill(extra, "external")
        val entries = SkillCatalog.scan(root, SkillsConfig(extraDirectories = listOf(extra.toString())))
        val owned = entries.single { it.manifest?.name == "owned" }
        entries.filter { it.origin != SkillOrigin.USER }.forEach {
            assertFailsWith<IllegalArgumentException> { SkillCatalog.uninstall(root, it) }
        }
        assertFailsWith<IllegalArgumentException> { SkillCatalog.uninstall(root, owned.copy(directory = external)) }
        SkillCatalog.uninstall(root, owned)
        assertFalse(Files.exists(target))
        assertTrue(Files.exists(external))
        assertTrue(SkillCatalog.scan(root, SkillsConfig()).none { it.manifest?.name == "owned" })
        assertFails { SkillCatalog.uninstall(root, owned) }
    }

    @Test fun linkCannotDeleteExternalTarget() {
        val root = Files.createTempDirectory("skills-links")
        val outside = Files.createTempDirectory("skills-link-target")
        skill(outside, "external")
        val link = root.resolve("link")
        // Creating symlinks needs Developer Mode or elevation on Windows.
        if (runCatching { Files.createSymbolicLink(link, outside.resolve("external")) }.isFailure) return
        val entry = SkillCatalog.scan(root, SkillsConfig()).single { it.origin == SkillOrigin.USER }
        assertFailsWith<IllegalArgumentException> { SkillCatalog.uninstall(root, entry) }
        assertTrue(Files.exists(outside.resolve("external/SKILL.md")))
    }
}
