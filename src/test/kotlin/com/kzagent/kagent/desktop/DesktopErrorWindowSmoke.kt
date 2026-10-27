package com.kzagent.kagent.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.window.WindowExceptionHandler
import com.kzagent.kagent.desktop.app.DesktopErrorReporter
import com.kzagent.kagent.desktop.app.DesktopErrorReport
import com.kzagent.kagent.desktop.app.DesktopErrorWindow
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Explicit GUI smoke entry point; ordinary unit tests never open intentional crash windows. */
@OptIn(ExperimentalComposeUiApi::class, ComposeToolingApi::class)
object DesktopErrorWindowSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val shown = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val exitCount = AtomicInteger()
        val errors = DesktopErrorWindow { exitCount.incrementAndGet(); exited.countDown() }
        val reporter = DesktopErrorReporter(
            present = { report, dismiss -> errors.show(report, dismiss); shown.countDown() },
            dismiss = errors::close, log = {},
        )
        var host: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                host = JFrame("异常注入测试").apply {
                    setSize(800, 600)
                    contentPane.add(ComposePanel().apply {
                        exceptionHandler = WindowExceptionHandler { reporter.report(it) }
                        setContent { throw VerifyError("Injected rendering failure sk-12345678901234567890") }
                    })
                    isVisible = true
                }
            }
            check(shown.await(10, TimeUnit.SECONDS)) { "Error window did not open after Compose failed" }
            // Semantics can exist before Fluent's enter animation paints its first frame.
            Thread.sleep(700)
            val robot = Robot()
            var copied = false
            var closed = false
            var compactReadyAt = Long.MAX_VALUE
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!closed && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait {
                    val dialog = Window.getWindows().filterIsInstance<JDialog>().firstOrNull { it.isShowing }
                    val panel = dialog?.contentPane?.components?.filterIsInstance<ComposePanel>()?.firstOrNull()
                    val allNodes = panel?.semanticsOwners?.flatMap { nodes(it.rootSemanticsNode) }.orEmpty()
                    fun button(label: String) = allNodes.lastOrNull { node ->
                        node.config.getOrNull(SemanticsActions.OnClick) != null && nodes(node).any {
                            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
                        }
                    }
                    if (!copied) button("复制错误信息")?.let {
                        val output = Path.of("build", "error-window-preview.png")
                        Files.createDirectories(output.parent)
                        val screenshot = robot.createScreenCapture(dialog!!.bounds)
                        val buttonX = (it.boundsInRoot.left + dialog.insets.left + 10).toInt()
                        val buttonY = (it.boundsInRoot.center.y + dialog.insets.top).toInt()
                        check(screenshot.getRGB(buttonX, buttonY) != screenshot.getRGB(0, 0)) {
                            "Error window has semantics but its action button is not painted"
                        }
                        ImageIO.write(screenshot, "png", output.toFile())
                        ImageIO.write(robot.createScreenCapture(java.awt.Rectangle(Toolkit.getDefaultToolkit().screenSize)), "png",
                            Path.of("build", "error-window-screen-preview.png").toFile())
                        it.config[SemanticsActions.OnClick].action!!.invoke()
                        val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
                        check("VerifyError" in text && "***REDACTED***" in text && "sk-12345678901234567890" !in text)
                        copied = true
                        dialog.setSize(480, 360)
                        compactReadyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
                    }
                    if (copied && System.nanoTime() >= compactReadyAt) button("关闭应用")?.let {
                        check(it.boundsInRoot.bottom + dialog!!.insets.top + 10 < dialog.height) {
                            "Error actions are clipped after shrinking the window"
                        }
                        ImageIO.write(robot.createScreenCapture(dialog.bounds), "png",
                            Path.of("build", "error-window-compact-preview.png").toFile())
                        robot.mouseMove(
                            (dialog.x + dialog.insets.left + it.boundsInRoot.center.x).toInt(),
                            (dialog.y + dialog.insets.top + it.boundsInRoot.center.y).toInt(),
                        )
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                        closed = true
                    }
                }
                if (!closed) Thread.sleep(50)
            }
            check(copied && exited.await(1, TimeUnit.SECONDS)) { "Fluent error actions did not work" }
            println("GATE uncaught Compose error / independent Fluent window / redacted copy / close: PASS")
            SwingUtilities.invokeAndWait {
                errors.show(DesktopErrorReport("Original redacted crash details")) {}
            }
            SwingUtilities.invokeAndWait {
                val dialog = Window.getWindows().filterIsInstance<JDialog>().first { it.isShowing }
                val panel = dialog.contentPane.components.filterIsInstance<ComposePanel>().single()
                panel.setContent { throw LinkageError("Injected failure inside the error renderer") }
            }
            var nativeClosed = false
            var fallbackReadyAt = Long.MAX_VALUE
            val fallbackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!nativeClosed && System.nanoTime() < fallbackDeadline) {
                SwingUtilities.invokeAndWait {
                    val dialog = Window.getWindows().filterIsInstance<JDialog>().firstOrNull { it.isShowing }
                    val components = dialog?.let { components(it.contentPane) }.orEmpty()
                    val detail = components.filterIsInstance<JTextArea>().firstOrNull()
                    if (detail != null && fallbackReadyAt == Long.MAX_VALUE) {
                        fallbackReadyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
                    }
                    if (detail != null && System.nanoTime() >= fallbackReadyAt) {
                        check(detail.text == "Original redacted crash details")
                        components.filterIsInstance<JButton>().first { it.text == "复制错误信息" }.doClick()
                        check(Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) == detail.text)
                        ImageIO.write(robot.createScreenCapture(dialog!!.bounds), "png",
                            Path.of("build", "error-window-fallback-preview.png").toFile())
                        components.filterIsInstance<JButton>().first { it.text == "关闭应用" }.doClick()
                        nativeClosed = true
                    }
                }
                if (!nativeClosed) Thread.sleep(50)
            }
            check(nativeClosed && exitCount.get() == 2) { "Native error fallback did not work" }
            println("GATE failed error renderer / native fallback / copy / close: PASS")
        } finally {
            reporter.close()
            SwingUtilities.invokeAndWait { errors.close(); host?.dispose() }
        }
    }

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun components(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + if (it is Container) components(it) else emptyList()
    }
}
