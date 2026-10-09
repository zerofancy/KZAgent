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

    @Test fun uninstallAllowsAncestorAliasAndNormalizesEntryPath() {
        val workspace = Files.createTempDirectory("skills-ancestor-alias").toRealPath()
        val realParent = Files.createDirectory(workspace.resolve("real"))
        val alias = workspace.resolve("alias")
        if (runCatching { Files.createSymbolicLink(alias, realParent) }.isFailure) return
        val root = Files.createDirectory(alias.resolve("skills"))
        val target = skill(root, "owned")
        Files.createDirectories(target.resolve("nested"))
        Files.writeString(target.resolve("nested/file.txt"), "data")
        val sibling = skill(root, "sibling")
        val owned = SkillCatalog.scan(root, SkillsConfig()).single { it.manifest?.name == "owned" }

        SkillCatalog.uninstall(root, owned.copy(directory = target.resolve(".")))

        assertFalse(Files.exists(target))
        assertTrue(Files.exists(root))
        assertTrue(Files.exists(sibling.resolve("SKILL.md")))
    }

    @Test fun linkedSkillRootCannotBeUninstalled() {
        val workspace = Files.createTempDirectory("skills-root-link").toRealPath()
        val realRoot = Files.createDirectory(workspace.resolve("real"))
        val target = skill(realRoot, "owned")
        val alias = workspace.resolve("alias")
        if (runCatching { Files.createSymbolicLink(alias, realRoot) }.isFailure) return
        val owned = SkillCatalog.scan(alias, SkillsConfig()).single { it.origin == SkillOrigin.USER }

        assertFailsWith<IllegalArgumentException> { SkillCatalog.uninstall(alias, owned) }
        assertTrue(Files.exists(target.resolve("SKILL.md")))
    }

    @Test fun linkToSiblingSkillCannotBeUninstalled() {
        val root = Files.createTempDirectory("skills-sibling-link").toRealPath()
        val target = skill(root, "owned")
        val link = root.resolve("link")
        if (runCatching { Files.createSymbolicLink(link, target) }.isFailure) return
        val entry = SkillCatalog.scan(root, SkillsConfig()).single { it.directory == link }

        assertFailsWith<IllegalArgumentException> { SkillCatalog.uninstall(root, entry) }
        assertTrue(Files.exists(target.resolve("SKILL.md")))
    }

    @Test fun nestedLinksCannotDeleteExternalContent() {
        for (directoryLink in listOf(true, false)) {
            val root = Files.createTempDirectory("skills-nested-link").toRealPath()
            val outside = Files.createTempDirectory("skills-nested-external").toRealPath()
            val externalFile = Files.writeString(outside.resolve("keep.txt"), "keep")
            val target = skill(root, "owned")
            val link = target.resolve("link")
            val destination = if (directoryLink) outside else externalFile
            if (runCatching { Files.createSymbolicLink(link, destination) }.isFailure) return
            val owned = SkillCatalog.scan(root, SkillsConfig()).single { it.origin == SkillOrigin.USER }

            assertFailsWith<IllegalArgumentException> { SkillCatalog.uninstall(root, owned) }
            assertEquals("keep", Files.readString(externalFile))
            assertTrue(Files.isSymbolicLink(link))
        }
    }
}
