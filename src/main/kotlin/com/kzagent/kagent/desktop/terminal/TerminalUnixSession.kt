package com.kzagent.kagent.desktop.terminal

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/** POSIX session membership survives reparenting; never include the host application's session. */
internal object TerminalUnixSession {
    private interface LibC : Library {
        fun getsid(pid: Int): Int
    }
    private val libc: LibC by lazy { Native.load(Platform.C_LIBRARY_NAME, LibC::class.java) }

    fun capture(pid: Long?): Int? {
        if (pid == null || Platform.isWindows()) return null
        return runCatching {
            libc.getsid(pid.toInt()).takeIf { session ->
                session > 0 && session != libc.getsid(ProcessHandle.current().pid().toInt())
            }
        }.getOrNull()
    }

    fun members(session: Int): List<ProcessHandle> = runCatching {
        ProcessHandle.allProcesses().use { processes ->
            processes.filter { process ->
                process.pid() != ProcessHandle.current().pid() && libc.getsid(process.pid().toInt()) == session
            }.toList()
        }
    }.getOrDefault(emptyList())
}
