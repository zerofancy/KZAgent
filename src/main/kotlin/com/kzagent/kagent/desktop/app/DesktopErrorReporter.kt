package com.kzagent.kagent.desktop.app

import com.kzagent.kagent.config.SecretRedactor
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities

internal data class DesktopErrorReport(val details: String)

/** Keep error presentation outside the composition which may already have failed. */
internal class DesktopErrorReporter(
    private val present: (DesktopErrorReport, () -> Unit) -> Unit,
    private val dismiss: () -> Unit = {},
    private val dispatch: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val log: (Throwable) -> Unit = { desktopLog("uncaught desktop exception", it) },
) : AutoCloseable {
    private var closed = false
    private var showing = false
    private var lastDetails: String? = null
    private var lastLoggedDetails: String? = null
    private var previous: Thread.UncaughtExceptionHandler? = null
    private val handler = Thread.UncaughtExceptionHandler { thread, failure -> report(failure, thread.name) }

    @Synchronized
    fun install() {
        check(!closed)
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    fun report(failure: Throwable, threadName: String = Thread.currentThread().name) {
        // Normal coroutine cancellation is not a crash or an actionable error.
        if (failure is CancellationException) return
        val report = DesktopErrorReport(SecretRedactor.redact(
            "线程：$threadName\n\n${failure.stackTraceToString()}",
        ).take(65_536))
        val shouldPresent = synchronized(this) {
            if (closed || lastLoggedDetails == report.details) return
            // Rendering can fail every frame; duplicate disk writes must not stall
            // the same event thread that needs to display the error window.
            lastLoggedDetails = report.details
            if (showing || lastDetails == report.details) false else {
                showing = true
                lastDetails = report.details
                true
            }
        }
        runCatching { log(failure) }
        if (!shouldPresent) return
        try {
            dispatch show@{
                synchronized(this) { if (closed) return@show }
                try {
                    present(report) { synchronized(this) { showing = false } }
                } catch (presentationFailure: Throwable) {
                    // Never recursively invoke the global handler from its own error UI.
                    runCatching { log(presentationFailure) }
                    synchronized(this) { showing = false }
                }
            }
        } catch (dispatchFailure: Throwable) {
            runCatching { log(dispatchFailure) }
            synchronized(this) { showing = false }
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            if (Thread.getDefaultUncaughtExceptionHandler() === handler) {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
        }
        dispatch(dismiss)
    }
}
