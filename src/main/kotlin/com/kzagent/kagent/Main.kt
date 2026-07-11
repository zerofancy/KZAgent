package com.kzagent.kagent

import com.kzagent.kagent.cli.runCli
import com.kzagent.kagent.desktop.runDesktopApp
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (LaunchModeResolver.resolve(args)) {
        LaunchMode.Desktop -> runDesktopApp()
        LaunchMode.Cli -> exitProcess(runCli(args))
    }
}

enum class LaunchMode {
    Desktop,
    Cli,
}

object LaunchModeResolver {
    fun resolve(args: Array<String>): LaunchMode {
        return if (args.isEmpty()) {
            LaunchMode.Desktop
        } else {
            LaunchMode.Cli
        }
    }
}
