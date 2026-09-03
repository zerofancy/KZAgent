package com.kzagent.kagent.desktop

import androidx.compose.ui.awt.ComposePanel
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.config.SecretRedactor
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Image
import java.awt.desktop.AppReopenedListener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/**
 * Auto-detect Linux desktop DPI scaling factor so Compose Desktop matches the
 * system-level scale (GNOME / KDE).  Must be called before AWT/Swing initialise.
 *
 * Priority: `SKIKO_AWT_DPI_SCALE` env var > `GDK_SCALE` env var >
 * gsettings scaling-factor > 1.0 default.
 */
private fun applyLinuxDpiScale() {
    val osName = System.getProperty("os.name").lowercase()
    if (!osName.contains("linux")) return

    val envScale = System.getenv("SKIKO_AWT_DPI_SCALE")
        ?: System.getenv("GDK_SCALE")
    if (envScale != null) {
        val value = envScale.toDoubleOrNull()
        if (value != null && value > 0.0) {
            System.setProperty("skiko.awt.dpi.scale", value.toString())
            System.setProperty("sun.java2d.uiScale", value.toString())
            return
        }
    }

    val gsettingsScale = runCatching {
        val s = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "scaling-factor")
            .start().inputStream.bufferedReader().use { it.readText().trim() }
        Regex("""(\d+)$""").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
    }.getOrNull()
    if (gsettingsScale != null && gsettingsScale > 0.0) {
        System.setProperty("skiko.awt.dpi.scale", gsettingsScale.toString())
        System.setProperty("sun.java2d.uiScale", gsettingsScale.toString())
    }
}

fun runDesktopApp(
    initialWorkspace: Path,
    createStartupSession: Boolean,
) {
    applyLinuxDpiScale()
    System.setProperty("apple.awt.application.name", "KZAgent")
    System.setProperty("apple.awt.UIElement", "false")
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        desktopLog("uncaught exception: ${throwable.message ?: throwable}", throwable)
    }
    desktopLog("starting Swing host")
    if (openPackagedAppBeforeAwt()) {
        exitProcess(0)
    }
    val initialRequest = desktopLaunchRequest(initialWorkspace, createStartupSession)
    val instanceStart = DesktopSingleInstanceCoordinator.startOrForward(
        lockFile = AppDataDir.appDir().resolve("desktop-instance.lock"),
        request = initialRequest,
    )
    if (instanceStart is DesktopInstanceStart.Forwarded) {
        desktopLog("forwarded launch request to the existing GUI")
        return
    }
    val instanceCoordinator = (instanceStart as DesktopInstanceStart.Primary).coordinator
    val closed = CountDownLatch(1)
    val initialized = CountDownLatch(1)
    val windowShown = AtomicBoolean(false)
    val windowLifecycle = desktopWindowLifecycle(System.getProperty("os.name"))
    var startupFailure: Throwable? = null
    startPackagedAppFallbackWatchdog(windowShown) {
        instanceCoordinator.close()
    }
    SwingUtilities.invokeLater {
        try {
            desktopLog("creating JFrame")
            val loadingPanel = JPanel(BorderLayout()).apply {
                add(JLabel("KZAgent loading..."), BorderLayout.CENTER)
            }
            val frame = JFrame("KZAgent").apply {
                defaultCloseOperation = when (windowLifecycle) {
                    DesktopWindowLifecycle.KEEP_RUNNING -> WindowConstants.HIDE_ON_CLOSE
                    DesktopWindowLifecycle.EXIT_AFTER_CLOSE -> WindowConstants.DISPOSE_ON_CLOSE
                }
                minimumSize = Dimension(880, 600)
                preferredSize = Dimension(1120, 760)
                iconImage = loadAppIcon()
                contentPane.layout = BorderLayout()
                contentPane.add(loadingPanel, BorderLayout.CENTER)
                addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowClosed(e: java.awt.event.WindowEvent) {
                        closed.countDown()
                    }
                })
                pack()
            }
            desktopLog("JFrame created")
            installMacAppReopenHandler(frame, windowLifecycle)
            showWindowInForeground(frame)
            windowShown.set(true)
            desktopLog("JFrame visible")
            Timer(1_200) {
                frame.isAlwaysOnTop = false
            }.apply {
                isRepeats = false
                start()
            }
            desktopLog("creating ComposePanel")
            val panel = ComposePanel().apply {
                setContent {
                    KZAgentDesktopApp(
                        initialWorkspace = initialWorkspace,
                        createStartupSession = createStartupSession,
                        instanceCoordinator = instanceCoordinator,
                        activateWindow = {
                            SwingUtilities.invokeLater {
                                restoreWindowInForeground(frame)
                            }
                        },
                    )
                }
            }
            desktopLog("ComposePanel created")
            frame.contentPane.removeAll()
            frame.contentPane.add(panel, BorderLayout.CENTER)
            frame.contentPane.revalidate()
            frame.contentPane.repaint()
        } catch (throwable: Throwable) {
            val message = throwable.message ?: throwable::class.qualifiedName ?: throwable.toString()
            desktopLog("direct window failed: $message", throwable)
            instanceCoordinator.close()
            if (!openPackagedAppFallback("direct window failed")) {
                startupFailure = throwable
                closed.countDown()
            }
        } finally {
            initialized.countDown()
        }
    }
    try {
        initialized.await()
        startupFailure?.let { throw it }
        closed.await()
        desktopLog("closed")
    } finally {
        instanceCoordinator.close()
    }
}

internal enum class DesktopWindowLifecycle {
    KEEP_RUNNING,
    EXIT_AFTER_CLOSE,
}

/**
 * macOS applications conventionally stay alive after their last window closes. The
 * existing Compose content is therefore hidden and later restored from the Dock;
 * other desktop platforms retain their close-to-exit behavior.
 */
internal fun desktopWindowLifecycle(osName: String): DesktopWindowLifecycle =
    if (osName.lowercase().contains("mac")) {
        DesktopWindowLifecycle.KEEP_RUNNING
    } else {
        DesktopWindowLifecycle.EXIT_AFTER_CLOSE
    }

private fun installMacAppReopenHandler(
    window: JFrame,
    lifecycle: DesktopWindowLifecycle,
) {
    if (lifecycle != DesktopWindowLifecycle.KEEP_RUNNING) return

    runCatching {
        check(Desktop.isDesktopSupported()) { "Desktop API is not supported" }
        val desktop = Desktop.getDesktop()
        check(desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            "macOS application reopen events are not supported"
        }
        desktop.addAppEventListener(AppReopenedListener {
            EventQueue.invokeLater {
                desktopLog("Dock reopen requested")
                restoreWindowInForeground(window)
            }
        })
    }.onFailure {
        desktopLog("failed to install Dock reopen handler: ${it.message ?: it}", it)
    }
}

private fun loadAppIcon(): Image? = runCatching {
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("icons/kzagent.png")) {
        "Application icon resource was not found"
    }.use(ImageIO::read)
}.onFailure {
    desktopLog("failed to load application icon: ${it.message}", it)
}.getOrNull()

private fun startPackagedAppFallbackWatchdog(
    windowShown: AtomicBoolean,
    beforeFallback: () -> Unit,
) {
    if (System.getProperty("kzagent.allowOpenFallback") != "true") return
    Thread {
        Thread.sleep(5_000)
        if (windowShown.get()) return@Thread
        beforeFallback()
        if (!openPackagedAppFallback("window was not shown")) {
            desktopLog("window was not shown and packaged app fallback failed")
            exitProcess(1)
        }
    }.apply {
        isDaemon = true
        name = "kzagent-open-app-fallback"
        start()
    }
}

private fun openPackagedAppBeforeAwt(): Boolean {
    if (System.getProperty("kzagent.allowOpenFallback") != "true") return false
    val appPath = packagedAppPath()
    if (!Files.exists(appPath)) {
        desktopLog("packaged app fallback requested, but packaged app was not found at $appPath")
        return false
    }
    return runCatching {
        desktopLog("opening packaged app via LaunchServices before AWT: $appPath")
        val process = ProcessBuilder("open", "-n", "-a", appPath.toString())
            .directory(Path.of("").toAbsolutePath().normalize().toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(desktopLogPath().toFile()))
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            desktopLog("LaunchServices open did not exit within 5 seconds")
            return false
        }
        if (process.exitValue() != 0) {
            desktopLog("LaunchServices open failed with exit code ${process.exitValue()}")
            return false
        }
        true
    }.getOrElse {
        desktopLog("failed to open packaged app before AWT: ${it.message ?: it}", it)
        false
    }
}

private fun openPackagedAppFallback(reason: String): Boolean {
    if (System.getProperty("kzagent.allowOpenFallback") != "true") return false
    val appPath = packagedAppPath()
    if (!Files.exists(appPath)) {
        desktopLog("$reason, and packaged app was not found at $appPath")
        return false
    }
    return runCatching {
        desktopLog("$reason; opening packaged app via LaunchServices: $appPath")
        ProcessBuilder("open", "-n", "-a", appPath.toString())
            .directory(Path.of("").toAbsolutePath().normalize().toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(desktopLogPath().toFile()))
            .start()
        exitProcess(0)
    }.getOrElse {
        desktopLog("failed to open packaged app: ${it.message ?: it}", it)
        false
    }
}

private fun packagedAppPath(): Path =
    Path.of(
        System.getProperty("kzagent.packagedAppPath")
            ?: "build/compose/binaries/main/app/KZAgent.app",
    ).toAbsolutePath().normalize()

internal fun desktopLog(message: String, throwable: Throwable? = null) {
    val line = "${OffsetDateTime.now()} KZAgent desktop: ${SecretRedactor.redact(message)}"
    println(line)
    if (throwable != null) {
        // Emit the full (redacted) stack trace to stdout as well as to the log file so a
        // failure's root cause is visible directly in the terminal, not only in desktop.log.
        println(SecretRedactor.redact(throwable.stackTraceToString()))
    }
    val logPath = desktopLogPath()
    runCatching {
        logPath.parent?.let(Files::createDirectories)
        Files.writeString(
            logPath,
            buildString {
                appendLine(line)
                if (throwable != null) {
                    appendLine(SecretRedactor.redact(throwable.stackTraceToString()))
                }
            },
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}

private fun desktopLogPath(): Path =
    System.getProperty("kzagent.logPath")?.let(Path::of)
        ?: AppDataDir.appDir().resolve("desktop.log")

/**
 * Class initialization and coroutine wrappers often hide the actionable error
 * behind a class name. Prefer the deepest non-empty cause for the UI while the
 * complete stack trace remains available in desktop.log.
 */
internal fun runtimeErrorMessage(throwable: Throwable): String {
    val causes = generateSequence(throwable) { current ->
        current.cause?.takeUnless { it === current }
    }.toList()
    return causes
        .asReversed()
        .firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
        ?: throwable.toString()
}

private fun showWindowInForeground(window: java.awt.Window) {
    window.minimumSize = Dimension(880, 600)
    window.setLocationRelativeTo(null)
    window.isAlwaysOnTop = true
    restoreWindowInForeground(window)
}

private fun restoreWindowInForeground(window: java.awt.Window) {
    if (window is Frame && window.extendedState and Frame.ICONIFIED != 0) {
        window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
    }
    window.isVisible = true
    window.toFront()
    window.requestFocus()
    requestMacForeground()
    EventQueue.invokeLater {
        window.toFront()
        window.requestFocus()
        window.requestFocusInWindow()
        requestMacForeground()
    }
}

private fun requestMacForeground() {
    runCatching {
        val applicationClass = Class.forName("com.apple.eawt.Application")
        val application = applicationClass.getMethod("getApplication").invoke(null)
        applicationClass
            .getMethod("requestForeground", Boolean::class.javaPrimitiveType)
            .invoke(application, true)
    }
}
