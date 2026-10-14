package com.kzagent.kagent.desktop.terminal

import ai.rever.bossterm.compose.settings.SettingsManager
import ai.rever.bossterm.compose.settings.TerminalSettings
import java.nio.file.Path

internal fun terminalSettingsProfile() = TerminalSettings(
    aiAssistantsEnabled = false, mcpEnabled = false, mcpAutoAttachInstalled = false,
    daemonEnabled = false, sessionSharingEnabled = false, fileLoggingEnabled = false,
    notifyOnCommandComplete = false, clipboardOsc52Enabled = false,
    clipboardOsc52AllowRead = false, clipboardOsc52AllowWrite = false,
    autoInjectShellIntegration = false, commandPaletteEnabled = false,
    commandBlocksEnabled = false, historySearchEnabled = false, allowKittyFileTransfers = false,
    globalHotkeyEnabled = false, audibleBell = false, showScrollbar = true,
    scrollbarAlwaysVisible = true, bufferMaxLines = 10_000,
)

/** The renderer uses a process singleton; redirect it before its first initialization. */
internal object TerminalSettingsProfile {
    private var directory: Path? = null

    @Synchronized
    fun initialize(path: Path): TerminalSettings {
        if (directory == null) {
            System.setProperty("bossterm.settings.dir", path.toAbsolutePath().toString())
            directory = path
            SettingsManager.instance.updateSettings(terminalSettingsProfile())
        }
        return SettingsManager.instance.settings.value
    }

    fun updateAppearance(foreground: String, background: String, selection: String) {
        check(directory != null)
        val current = SettingsManager.instance.settings.value
        val updated = current.copy(defaultForeground = foreground, defaultBackground = background, selectionColor = selection)
        if (current != updated) SettingsManager.instance.updateSettings(updated)
    }
}
