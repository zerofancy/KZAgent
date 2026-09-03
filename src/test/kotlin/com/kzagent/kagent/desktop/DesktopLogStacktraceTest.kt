package com.kzagent.kagent.desktop

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers the desktop logger's console behavior: when an exception is passed to
 * [desktopLog], the full (redacted) stack trace must also be printed to stdout,
 * not only written to the log file, so root causes are visible in the terminal.
 */
class DesktopLogStacktraceTest {

    private val originalOut = System.out
    private val originalLogPath = System.getProperty("kzagent.logPath")

    @BeforeTest
    fun setUp() {
        val tempDir = Files.createTempDirectory("kzagent-desktop-log-test")
        System.setProperty("kzagent.logPath", tempDir.resolve("desktop.log").toString())
    }

    @AfterTest
    fun tearDown() {
        if (originalLogPath != null) {
            System.setProperty("kzagent.logPath", originalLogPath)
        } else {
            System.clearProperty("kzagent.logPath")
        }
        System.setOut(originalOut)
    }

    @Test
    fun stdoutPrintsFullStackTraceWhenThrowableIsPresent() {
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            desktopLog("failure happened", RuntimeException("boom"))
        } finally {
            System.setOut(originalOut)
        }
        val output = captured.toString(Charsets.UTF_8)

        assertTrue(
            output.contains("KZAgent desktop: failure happened"),
            "expected the one-line message, got: $output",
        )
        assertTrue(
            output.contains("java.lang.RuntimeException: boom"),
            "expected stack trace header, got: $output",
        )
        assertTrue(
            output.contains("at com.kzagent.kagent.desktop"),
            "expected at least one stack frame line, got: $output",
        )
    }

    @Test
    fun stdoutPrintsOnlyMessageWhenNoThrowable() {
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            desktopLog("plain message")
        } finally {
            System.setOut(originalOut)
        }
        val output = captured.toString(Charsets.UTF_8)

        assertTrue(
            output.contains("KZAgent desktop: plain message"),
            "expected the message line, got: $output",
        )
        assertTrue(
            !output.contains("at com.kzagent.kagent.desktop"),
            "no stack trace expected without a throwable, got: $output",
        )
    }
}
