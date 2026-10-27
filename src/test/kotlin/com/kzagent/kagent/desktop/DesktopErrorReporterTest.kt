package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.app.DesktopErrorReport
import com.kzagent.kagent.desktop.app.DesktopErrorReporter
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopErrorReporterTest {
    @Test
    fun `reports linkage errors with redacted cause and stack trace on the UI dispatcher`() {
        val queue = mutableListOf<() -> Unit>()
        val reports = mutableListOf<DesktopErrorReport>()
        DesktopErrorReporter(present = { report, _ -> reports += report }, dispatch = queue::add, log = {}).use {
            it.report(VerifyError("bad frame sk-12345678901234567890"), "worker")
            assertTrue(reports.isEmpty())
            queue.removeAt(0).invoke()
            assertTrue(reports.single().details.contains("VerifyError"))
            assertTrue(reports.single().details.contains("worker"))
            assertFalse(reports.single().details.contains("sk-12345678901234567890"))
            assertTrue(reports.single().details.contains("***REDACTED***"))
        }
    }

    @Test
    fun `coalesces failures and ignores repeated dismissed errors and cancellation`() {
        var shown = 0
        var dismiss: () -> Unit = {}
        DesktopErrorReporter(present = { _, close -> shown++; dismiss = close }, dispatch = { it() }, log = {}).use {
            val failure = IllegalStateException("failed")
            it.report(failure)
            it.report(IllegalArgumentException("another failure"))
            assertEquals(1, shown)
            dismiss()
            it.report(failure)
            it.report(CancellationException())
            assertEquals(1, shown)
            it.report(IllegalArgumentException("new failure"))
            assertEquals(2, shown)
        }
    }

    @Test
    fun `closing before queued presentation prevents late window and restores handler`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val queue = mutableListOf<() -> Unit>()
        var shown = false
        val reporter = DesktopErrorReporter(present = { _, _ -> shown = true }, dispatch = queue::add, log = {})
        try {
            reporter.install()
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), RuntimeException("test"))
            reporter.close()
            queue.toList().forEach { it() }
            assertFalse(shown)
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
        } finally { reporter.close(); Thread.setDefaultUncaughtExceptionHandler(previous) }
    }

    @Test
    fun `a recurring render failure is logged and presented only once`() {
        var logs = 0
        var windows = 0
        DesktopErrorReporter(present = { _, _ -> windows++ }, dispatch = { it() }, log = { logs++ }).use {
            val failure = VerifyError("recurring frame failure")
            repeat(100) { _ -> it.report(failure) }
            assertEquals(1, logs)
            assertEquals(1, windows)
        }
    }

    @Test
    fun `presentation and log failures do not recursively escape the reporter`() {
        DesktopErrorReporter(present = { _, _ -> error("broken UI") }, dispatch = { it() }, log = { error("broken log") }).use {
            it.report(RuntimeException("test"))
        }
    }
}
