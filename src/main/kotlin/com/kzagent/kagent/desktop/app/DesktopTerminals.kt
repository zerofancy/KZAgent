package com.kzagent.kagent.desktop.app

import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.desktop.terminal.BossTermTerminalHandle
import com.kzagent.kagent.desktop.terminal.TerminalProcessPlatform
import com.kzagent.kagent.desktop.terminal.TerminalSettingsProfile
import java.nio.file.Path

internal fun createDesktopTerminal(workspace: Path): BossTermTerminalHandle {
    val settings = TerminalSettingsProfile.initialize(AppDataDir.appDir().resolve("terminal"))
    val platform = TerminalProcessPlatform(excludedEnvironmentKeys = setOf(
        "DEEPSEEK_API_KEY", "OPENROUTER_API_KEY", "MIMOCODE_API_KEY", "OPENAI_API_KEY",
    ))
    return try { BossTermTerminalHandle(workspace, settings, platform) }
    catch (error: Exception) { platform.close(); throw error }
}
