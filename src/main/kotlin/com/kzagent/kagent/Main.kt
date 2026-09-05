package com.kzagent.kagent

import com.kzagent.kagent.cli.runCli
import com.kzagent.kagent.desktop.app.runDesktopApp
import com.kzagent.kagent.config.FileKitPaths
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    FileKitPaths.initialize()
    when (val request = LaunchModeResolver.resolve(args)) {
        is LaunchRequest.Desktop -> runDesktopApp(
            initialWorkspace = request.initialWorkspace,
            createStartupSession = request.createStartupSession,
        )
        is LaunchRequest.Cli -> {
            WindowsParentConsole.attachIfNeeded()
            exitProcess(runCli(request.args))
        }
    }
}

sealed interface LaunchRequest {
    data class Desktop(
        val initialWorkspace: Path,
        val createStartupSession: Boolean,
    ) : LaunchRequest

    data class Cli(val args: Array<String>) : LaunchRequest {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Cli

            if (!args.contentEquals(other.args)) return false

            return true
        }

        override fun hashCode(): Int {
            return args.contentHashCode()
        }
    }
}

object LaunchModeResolver {
    fun resolve(
        args: Array<String>,
        currentDirectory: Path = Path.of("").toAbsolutePath().normalize(),
        packagedAppPath: String? = System.getProperty("jpackage.app-path"),
    ): LaunchRequest {
        val workspace = currentDirectory.toAbsolutePath().normalize()
        return when {
            args.firstOrNull() == "app" -> LaunchRequest.Desktop(
                initialWorkspace = workspace,
                createStartupSession = true,
            )
            args.isEmpty() && !packagedAppPath.isNullOrBlank() -> LaunchRequest.Desktop(
                initialWorkspace = workspace,
                createStartupSession = false,
            )
            args.isEmpty() -> LaunchRequest.Cli(arrayOf("chat"))
            else -> LaunchRequest.Cli(args.copyOf())
        }
    }
}
