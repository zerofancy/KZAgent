package com.kzagent.kagent

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.win32.W32APIOptions
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * jpackage uses a GUI subsystem executable for the Windows desktop launcher.
 * When that launcher is called by kza.cmd in a terminal, reconnect the JVM
 * streams to the parent console without making normal GUI launches show a
 * console window.
 */
internal object WindowsParentConsole {
    fun attachIfNeeded(osName: String = System.getProperty("os.name")) {
        if (!osName.lowercase().contains("windows")) return
        runCatching {
            if (!ConsoleKernel32.INSTANCE.AttachConsole(ATTACH_PARENT_PROCESS)) return
            System.setIn(FileInputStream("CONIN$"))
            System.setOut(
                PrintStream(
                    FileOutputStream("CONOUT$"),
                    true,
                    StandardCharsets.UTF_8,
                ),
            )
            System.setErr(
                PrintStream(
                    FileOutputStream("CONOUT$"),
                    true,
                    StandardCharsets.UTF_8,
                ),
            )
        }
    }

    private interface ConsoleKernel32 : Library {
        fun AttachConsole(processId: Int): Boolean

        companion object {
            val INSTANCE: ConsoleKernel32 = Native.load(
                "kernel32",
                ConsoleKernel32::class.java,
                W32APIOptions.DEFAULT_OPTIONS,
            )
        }
    }

    private const val ATTACH_PARENT_PROCESS = -1
}
