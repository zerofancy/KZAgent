package com.kzagent.kagent.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserCommandInstallerTest {
    @Test
    fun developmentRuntimeIsUnavailable() {
        val home = Files.createTempDirectory("kza-home")
        val installer = UserCommandInstaller(
            osName = "Mac OS X",
            userHome = home,
            pathValue = "",
            shell = "/bin/zsh",
            packagedAppPath = null,
        )

        val availability = installer.availability()

        assertFalse(availability.available)
        assertFalse(availability.installed)
        assertContains(availability.unavailableReason.orEmpty(), "已安装的桌面应用")
    }

    @Test
    fun unixInstallCreatesExecutableWrapperAndConfiguresShellPathIdempotently() {
        val root = Files.createTempDirectory("kza-unix")
        val home = root.resolve("home").also(Files::createDirectories)
        val launcher = root.resolve("KZAgent.app").resolve("Contents").resolve("MacOS").resolve("KZAgent")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, "launcher")
        val profile = home.resolve(".zshrc")
        Files.writeString(profile, "# existing profile\n")
        val installer = UserCommandInstaller(
            osName = "Mac OS X",
            userHome = home,
            pathValue = "/usr/bin:/bin",
            shell = "/bin/zsh",
            packagedAppPath = launcher,
        )

        val first = installer.install()
        val second = installer.install()

        val command = home.resolve(".local").resolve("bin").resolve("kza")
        assertEquals(command, first.commandPath)
        assertTrue(first.restartTerminalRequired)
        assertEquals(command, second.commandPath)
        val wrapper = Files.readString(command)
        assertEquals("# KZAgent managed command v1", wrapper.lineSequence().elementAt(1))
        assertContains(wrapper, "if [ \"${'$'}#\" -eq 0 ]")
        assertContains(wrapper, "set -- chat")
        assertContains(wrapper, "exec '${launcher}' \"${'$'}@\"")
        assertTrue(
            Files.getPosixFilePermissions(command).contains(PosixFilePermission.OWNER_EXECUTE),
        )
        val profileContent = Files.readString(profile)
        assertContains(profileContent, "# existing profile")
        assertEquals(1, Regex("KZAgent kza PATH >>>").findAll(profileContent).count())
        assertContains(profileContent, home.resolve(".local/bin").toString())
        assertTrue(installer.availability().installed)
    }

    @Test
    fun existingForeignCommandOnPathIsProtected() {
        val root = Files.createTempDirectory("kza-conflict")
        val home = root.resolve("home").also(Files::createDirectories)
        val launcher = root.resolve("KZAgent")
        Files.writeString(launcher, "launcher")
        val foreignBin = root.resolve("foreign-bin").also(Files::createDirectories)
        val foreignCommand = foreignBin.resolve("kza")
        Files.writeString(foreignCommand, "#!/bin/sh\nexit 0\n")
        val installer = UserCommandInstaller(
            osName = "Linux",
            userHome = home,
            pathValue = foreignBin.toString(),
            shell = "/bin/bash",
            packagedAppPath = launcher,
        )

        val error = assertFailsWith<IllegalStateException> {
            installer.install()
        }

        val availability = installer.availability()
        assertFalse(availability.available)
        assertEquals(foreignCommand, availability.commandPath)
        assertContains(availability.unavailableReason.orEmpty(), foreignCommand.toString())
        assertContains(error.message.orEmpty(), foreignCommand.toString())
        assertFalse(Files.exists(home.resolve(".local/bin/kza")))
    }

    @Test
    fun shellProfileSymlinkIsPreserved() {
        val root = Files.createTempDirectory("kza-profile-link")
        val home = root.resolve("home").also(Files::createDirectories)
        val dotfiles = root.resolve("dotfiles").also(Files::createDirectories)
        val profileTarget = dotfiles.resolve("zshrc")
        Files.writeString(profileTarget, "# shared profile\n")
        val profileLink = home.resolve(".zshrc")
        Files.createSymbolicLink(profileLink, profileTarget)
        val launcher = root.resolve("KZAgent")
        Files.writeString(launcher, "launcher")
        val installer = UserCommandInstaller(
            osName = "Mac OS X",
            userHome = home,
            pathValue = "/usr/bin",
            shell = "/bin/zsh",
            packagedAppPath = launcher,
        )

        installer.install()

        assertTrue(Files.isSymbolicLink(profileLink))
        assertContains(Files.readString(profileTarget), "KZAgent kza PATH")
    }

    @Test
    fun managedCommandAlreadyOnPathIsUpdatedInPlace() {
        val root = Files.createTempDirectory("kza-update")
        val home = root.resolve("home").also(Files::createDirectories)
        val oldBin = root.resolve("old-bin").also(Files::createDirectories)
        val command = oldBin.resolve("kza")
        Files.writeString(command, "#!/bin/sh\n# KZAgent managed command v1\nold\n")
        val launcher = root.resolve("new launcher")
        Files.writeString(launcher, "launcher")
        val installer = UserCommandInstaller(
            osName = "Linux",
            userHome = home,
            pathValue = oldBin.toString(),
            shell = "/bin/bash",
            packagedAppPath = launcher,
        )

        val result = installer.install()

        assertEquals(command, result.commandPath)
        assertFalse(result.restartTerminalRequired)
        assertContains(Files.readString(command), "'${launcher}'")
        assertFalse(Files.exists(home.resolve(".local/bin/kza")))
    }

    @Test
    fun windowsInstallUsesPerUserDirectoryAndUpdatesUserPath() {
        val root = Files.createTempDirectory("kza-windows")
        val home = root.resolve("home").also(Files::createDirectories)
        val localAppData = root.resolve("local-app-data").also(Files::createDirectories)
        val launcher = root.resolve("KZAgent.exe")
        Files.writeString(launcher, "launcher")
        val pathUpdates = mutableListOf<Path>()
        val installer = UserCommandInstaller(
            osName = "Windows 11",
            userHome = home,
            localAppData = localAppData,
            pathValue = "C:\\Windows\\System32",
            shell = "",
            packagedAppPath = launcher,
            windowsUserPathStore = WindowsUserPathStore {
                pathUpdates.add(it)
                true
            },
        )

        val result = installer.install()

        val expected = localAppData.resolve("KZAgent").resolve("bin").resolve("kza.cmd")
        assertEquals(expected, result.commandPath)
        assertTrue(result.restartTerminalRequired)
        assertEquals(listOf(expected.parent), pathUpdates)
        val wrapper = Files.readString(expected)
        assertContains(wrapper, "rem KZAgent managed command v1")
        assertContains(wrapper, "\"$launcher\" chat")
        assertContains(wrapper, "\"$launcher\" %*")
    }
}
