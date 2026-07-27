package com.kzagent.kagent.desktop

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORDByReference
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

internal data class UserCommandAvailability(
    val available: Boolean,
    val installed: Boolean,
    val commandPath: Path?,
    val unavailableReason: String? = null,
)

internal data class UserCommandInstallResult(
    val commandPath: Path,
    val restartTerminalRequired: Boolean,
)

internal fun interface WindowsUserPathStore {
    fun addIfMissing(directory: Path): Boolean
}

/**
 * Installs a small user-owned launcher instead of copying application binaries.
 * The wrapper injects `chat` only for an empty invocation, allowing the packaged
 * executable itself to keep treating a no-argument desktop-icon launch as GUI.
 */
internal class UserCommandInstaller(
    private val osName: String = System.getProperty("os.name"),
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val localAppData: Path? = System.getenv("LOCALAPPDATA")?.let(Path::of),
    private val pathValue: String = System.getenv("PATH").orEmpty(),
    private val shell: String = System.getenv("SHELL").orEmpty(),
    private val packagedAppPath: Path? = System.getProperty("jpackage.app-path")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of),
    private val windowsUserPathStore: WindowsUserPathStore = RegistryWindowsUserPathStore,
) {
    fun availability(): UserCommandAvailability {
        val launcher = packagedAppPath?.toAbsolutePath()?.normalize()
            ?: return UserCommandAvailability(
                available = false,
                installed = false,
                commandPath = null,
                unavailableReason = "仅已安装的桌面应用支持安装 kza 命令。",
            )
        if (!Files.isRegularFile(launcher)) {
            return UserCommandAvailability(
                available = false,
                installed = false,
                commandPath = null,
                unavailableReason = "找不到当前桌面应用的启动程序：$launcher",
            )
        }

        val target = managedCommandPath()
        val existing = findCommandOnPath()
        val conflict = existing?.takeUnless(::isManagedCommand)
            ?: target.takeIf { Files.exists(it) && !isManagedCommand(it) }
        if (conflict != null) {
            return UserCommandAvailability(
                available = false,
                installed = false,
                commandPath = conflict,
                unavailableReason = "检测到其他来源的 kza 命令，未覆盖：$conflict",
            )
        }
        val effectiveTarget = existing?.takeIf(::isManagedCommand) ?: target
        return UserCommandAvailability(
            available = true,
            installed = Files.isRegularFile(effectiveTarget) && isManagedCommand(effectiveTarget),
            commandPath = effectiveTarget,
        )
    }

    fun install(): UserCommandInstallResult {
        val launcher = packagedAppPath?.toAbsolutePath()?.normalize()
            ?: throw IllegalStateException("仅已安装的桌面应用支持安装 kza 命令。")
        require(Files.isRegularFile(launcher)) {
            "找不到当前桌面应用的启动程序：$launcher"
        }

        val target = managedCommandPath()
        val existingOnPath = findCommandOnPath()
        if (existingOnPath != null && !isManagedCommand(existingOnPath)) {
            throw IllegalStateException("检测到其他来源的 kza 命令，未覆盖：$existingOnPath")
        }
        if (Files.exists(target) && !isManagedCommand(target)) {
            throw IllegalStateException("目标位置已存在非 KZAgent 管理的文件，未覆盖：$target")
        }

        // If an older KZAgent-managed command is already first on PATH, update it
        // in place so a newly written default target cannot remain shadowed.
        val commandPath = existingOnPath?.takeIf(::isManagedCommand) ?: target
        val script = if (isWindows()) windowsWrapper(launcher) else unixWrapper(launcher)
        atomicWrite(commandPath, script, executable = !isWindows())

        val binDirectory = commandPath.parent
        val alreadyOnPath = pathContains(binDirectory)
        if (!alreadyOnPath) {
            if (isWindows()) {
                windowsUserPathStore.addIfMissing(binDirectory)
            } else {
                ensureUnixPath(binDirectory)
            }
        }
        return UserCommandInstallResult(
            commandPath = commandPath,
            restartTerminalRequired = !alreadyOnPath,
        )
    }

    internal fun managedCommandPath(): Path = if (isWindows()) {
        val appData = localAppData ?: userHome.resolve("AppData").resolve("Local")
        appData.resolve("KZAgent").resolve("bin").resolve("kza.cmd")
    } else {
        userHome.resolve(".local").resolve("bin").resolve("kza")
    }

    internal fun unixWrapper(launcher: Path): String = """
        #!/bin/sh
        # $MANAGED_MARKER
        if [ "${'$'}#" -eq 0 ]; then
          set -- chat
        fi
        exec ${shellQuote(launcher.toString())} "${'$'}@"
    """.trimIndent() + "\n"

    internal fun windowsWrapper(launcher: Path): String {
        val escapedLauncher = launcher.toString().replace("%", "%%")
        return """
            @echo off
            rem $MANAGED_MARKER
            if "%~1"=="" (
              "$escapedLauncher" chat
            ) else (
              "$escapedLauncher" %*
            )
            exit /b %ERRORLEVEL%
        """.trimIndent() + "\r\n"
    }

    private fun findCommandOnPath(): Path? {
        val candidates = if (isWindows()) {
            listOf("kza.cmd", "kza.exe", "kza.bat", "kza")
        } else {
            listOf("kza")
        }
        return pathEntries().firstNotNullOfOrNull { directory ->
            candidates
                .asSequence()
                .map(directory::resolve)
                .firstOrNull { Files.isRegularFile(it) }
        }
    }

    private fun pathContains(directory: Path): Boolean {
        val normalized = directory.toAbsolutePath().normalize()
        return pathEntries().any {
            if (isWindows()) {
                it.toString().equals(normalized.toString(), ignoreCase = true)
            } else {
                it == normalized
            }
        }
    }

    private fun pathEntries(): List<Path> = pathValue
        .split(if (isWindows()) ';' else ':')
        .asSequence()
        .filter(String::isNotBlank)
        .mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        .toList()

    private fun isManagedCommand(path: Path): Boolean = runCatching {
        Files.isRegularFile(path) &&
            Files.readString(path, StandardCharsets.UTF_8).contains(MANAGED_MARKER)
    }.getOrDefault(false)

    private fun ensureUnixPath(binDirectory: Path) {
        val profileEntry = shellProfile()
        // Dotfile repositories often symlink shell profiles. Update the target
        // so installing kza does not replace and break the user's symlink.
        val profile = if (Files.isSymbolicLink(profileEntry)) {
            profileEntry.toRealPath()
        } else {
            profileEntry
        }
        val markerStart = "# >>> KZAgent kza PATH >>>"
        val current = if (Files.isRegularFile(profile)) {
            Files.readString(profile, StandardCharsets.UTF_8)
        } else {
            ""
        }
        if (current.contains(markerStart)) return

        val pathLine = if (shell.substringAfterLast('/').equals("fish", ignoreCase = true)) {
            "fish_add_path ${shellQuote(binDirectory.toString())}"
        } else {
            "export PATH=${shellQuote(binDirectory.toString())}:\"${'$'}PATH\""
        }
        val prefix = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        val updated = buildString {
            append(current)
            append(prefix)
            appendLine(markerStart)
            appendLine(pathLine)
            appendLine("# <<< KZAgent kza PATH <<<")
        }
        atomicWrite(profile, updated, executable = false)
    }

    private fun shellProfile(): Path = when (shell.substringAfterLast('/').lowercase()) {
        "zsh" -> userHome.resolve(".zshrc")
        "bash" -> userHome.resolve(".bashrc")
        "fish" -> userHome.resolve(".config").resolve("fish").resolve("config.fish")
        else -> userHome.resolve(".profile")
    }

    private fun atomicWrite(path: Path, content: String, executable: Boolean) {
        Files.createDirectories(path.parent)
        val existingPermissions = runCatching {
            Files.getPosixFilePermissions(path)
        }.getOrNull()
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
            )
            if (executable) {
                runCatching {
                    Files.setPosixFilePermissions(
                        temporary,
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE,
                        ),
                    )
                }
            } else if (existingPermissions != null) {
                runCatching {
                    Files.setPosixFilePermissions(temporary, existingPermissions)
                }
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun isWindows(): Boolean = osName.lowercase().contains("windows")

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val MANAGED_MARKER = "KZAgent managed command v1"
    }
}

private object RegistryWindowsUserPathStore : WindowsUserPathStore {
    override fun addIfMissing(directory: Path): Boolean {
        val key = "Environment"
        val valueName = "Path"
        val existing = if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, key, valueName)) {
            runCatching {
                Advapi32Util.registryGetExpandableStringValue(
                    WinReg.HKEY_CURRENT_USER,
                    key,
                    valueName,
                )
            }.recoverCatching {
                Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_CURRENT_USER,
                    key,
                    valueName,
                )
            }.getOrThrow()
        } else {
            ""
        }
        val entries = existing.split(';').filter(String::isNotBlank)
        if (entries.any { it.equals(directory.toString(), ignoreCase = true) }) return false
        val updated = (entries + directory.toString()).joinToString(";")
        Advapi32Util.registrySetExpandableStringValue(
            WinReg.HKEY_CURRENT_USER,
            key,
            valueName,
            updated,
        )
        broadcastEnvironmentChange()
        return true
    }

    private fun broadcastEnvironmentChange() {
        val environment = Memory(("Environment".length + 1L) * NativeWideCharSize)
        environment.setWideString(0, "Environment")
        User32.INSTANCE.SendMessageTimeout(
            WinUser.HWND_BROADCAST,
            WM_SETTINGCHANGE,
            WPARAM(0),
            LPARAM(Pointer.nativeValue(environment)),
            WinUser.SMTO_ABORTIFHUNG,
            5_000,
            DWORDByReference(),
        )
    }

    private const val WM_SETTINGCHANGE = 0x001A
    private const val NativeWideCharSize = 2L
}
