package com.kzagent.poc

import ai.rever.bossterm.compose.session.TerminalSessionEngine
import ai.rever.bossterm.compose.settings.TerminalSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.kzagent.kagent.desktop.KZAgentFluentTheme
import com.kzagent.kagent.desktop.ui.SessionTerminalPanel
import io.github.composefluent.component.Button
import io.github.composefluent.component.Text
import com.kzagent.kagent.desktop.terminal.BossTermTerminalHandle
import com.kzagent.kagent.desktop.terminal.SessionTerminalManager
import com.kzagent.kagent.desktop.terminal.TerminalSettingsProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Files

/** A disposable home isolates BossTerm's singleton settings from the user's real files. */
fun main(args: Array<String>) {
    val sandbox = Files.createTempDirectory("kzagent-bossterm-poc-")
    System.setProperty("user.home", sandbox.toString())
    System.setProperty("bossterm.settings.dir", sandbox.resolve("terminal-settings").toString())
    TerminalSettingsProfile.initialize(sandbox.resolve("terminal-settings"))
    application {
        Window(onCloseRequest = ::exitApplication, title = "KZAgent · BossTerm PoC") {
            val manager = remember { SessionTerminalManager(factory = { BossTermTerminalHandle(it, isolatedSettings()) }, installShutdownHook = false) }
            val state = manager.stateFor("poc")
            DisposableEffect(manager) {
                onDispose { manager.close() }
            }
            LaunchedEffect(manager) {
                manager.toggle("poc", sandbox)
                val terminal = state.handle as BossTermTerminalHandle
                if ("--smoke" in args) {
                    try {
                        withTimeout(15_000) {
                            terminal.engine.state.first { it is TerminalSessionEngine.State.Connected }
                            val pid = terminal.engine.processHandle?.getPid()
                            delay(300)
                            state.visible = false
                            delay(300)
                            state.visible = true
                            check(pid == terminal.engine.processHandle?.getPid())
                            terminal.view.writeUserInput("echo POC-renderer-ready; exit 7\r")
                            terminal.engine.state.first { it is TerminalSessionEngine.State.Exited }
                            check(terminal.engine.exitCode == 7)
                            println("GATE UI/Fluent-render-hide-restore: PASS")
                        }
                    } finally { exitApplication() }
                }
            }
            KZAgentFluentTheme {
                Column(Modifier.fillMaxSize()) {
                    Button(onClick = { manager.toggle("poc", sandbox) }) {
                        Text(if (state.visible) "折叠终端（保留 shell）" else "恢复终端")
                    }
                    if (state.visible) {
                        SessionTerminalPanel(state, sandbox, manager, autoFocus = false,
                            onCollapse = { state.visible = false }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

internal fun isolatedSettings(): TerminalSettings = com.kzagent.kagent.desktop.terminal.terminalSettingsProfile()
