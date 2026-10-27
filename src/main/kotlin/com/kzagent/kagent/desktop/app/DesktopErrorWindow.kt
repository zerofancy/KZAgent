package com.kzagent.kagent.desktop.app

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowExceptionHandler
import com.kzagent.kagent.desktop.KZAgentFluentTheme
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.DialogSize
import io.github.composefluent.component.Text
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.WindowConstants
import javax.swing.SwingUtilities

/** Separate native host and Compose root: the main window's failed Recomposer is never reused. */
internal class DesktopErrorWindow(private val onCloseApplication: () -> Unit) : AutoCloseable {
    private var window: JDialog? = null

    @OptIn(ExperimentalComposeUiApi::class)
    fun show(report: DesktopErrorReport, onDismiss: () -> Unit) {
        val dialog = JDialog(null as Frame?, "KZAgent — 未处理错误", false)
        window = dialog
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.minimumSize = Dimension(480, 360)
        dialog.size = Dimension(720, 600)
        dialog.setLocationRelativeTo(null)
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                if (window === dialog) window = null
                onDismiss()
            }
        })
        val copy = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(report.details), null) }
        val exit = { dialog.dispose(); onCloseApplication() }
        var fallbackShown = false
        fun fallback() {
            if (fallbackShown || window !== dialog) return
            fallbackShown = true
            // A broken renderer/native library can also prevent Fluent from rendering.
            // Reuse the native host with plain Swing content as the last reporting boundary.
            dialog.contentPane.removeAll()
            dialog.contentPane.layout = BorderLayout(12, 12)
            dialog.contentPane.add(JLabel("发生未处理错误，请复制错误信息并关闭应用后重新启动。"), BorderLayout.NORTH)
            dialog.contentPane.add(JScrollPane(JTextArea(report.details).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                caretPosition = 0
            }, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER)
            dialog.contentPane.add(JPanel().apply {
                add(JButton("复制错误信息").apply { addActionListener { runCatching { copy() } } })
                add(JButton("关闭应用").apply { addActionListener { exit() } })
                add(JButton("关闭提示").apply { addActionListener { dialog.dispose() } })
            }, BorderLayout.SOUTH)
            dialog.revalidate()
            dialog.repaint()
        }
        try {
            // Error reporting must not wait for an enter animation after another
            // composition has failed; paint the complete dialog on its first frame.
            val panel = ComposePanel(coroutineContext = object : MotionDurationScale {
                override val scaleFactor = 0f
            }).apply {
                exceptionHandler = WindowExceptionHandler { SwingUtilities.invokeLater { fallback() } }
                setContent {
                    KZAgentFluentTheme {
                        ErrorContent(report, copy = { runCatching { copy() } },
                            exit = exit, dismiss = { dialog.dispose() })
                    }
                }
            }
            // Compose 1.12 popups need a following parent frame after native host
            // changes. Drive that frame explicitly, without a persistent repaint loop.
            fun refresh(frames: Int = 3) {
                SwingUtilities.invokeLater {
                    if (window === dialog && dialog.isDisplayable && !fallbackShown) {
                        runCatching { panel.renderImmediately() }.onFailure { fallback() }
                        if (frames > 1) refresh(frames - 1)
                    }
                }
            }
            dialog.addComponentListener(object : ComponentAdapter() {
                override fun componentShown(e: ComponentEvent) = refresh()
                override fun componentResized(e: ComponentEvent) = refresh()
            })
            dialog.contentPane.add(panel)
            dialog.isVisible = true
            panel.renderImmediately()
            refresh()
        } catch (_: Throwable) {
            fallback()
            dialog.isVisible = true
        }
        dialog.toFront()
    }

    override fun close() { window?.dispose(); window = null }
}

@Composable
private fun ErrorContent(report: DesktopErrorReport, copy: () -> Unit, exit: () -> Unit, dismiss: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(FluentTheme.colors.background.layer.default)) {
        val detailsHeight = (maxHeight - 260.dp).coerceIn(60.dp, 280.dp)
        ContentDialog(title = "发生未处理错误", visible = true, size = DialogSize.Max,
            content = {
                Text("应用可能无法继续正常工作。请复制错误信息，并关闭应用后重新启动。")
                val scroll = rememberScrollState()
                Box(Modifier.fillMaxWidth().heightIn(max = detailsHeight).padding(top = 12.dp)) {
                    SelectionContainer(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 14.dp)) {
                        Text(report.details, style = FluentTheme.typography.caption)
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }, primaryButtonText = "复制错误信息", secondaryButtonText = "关闭应用", closeButtonText = "关闭提示",
            onButtonClick = {
                when (it) {
                    ContentDialogButton.Primary -> copy()
                    ContentDialogButton.Secondary -> exit()
                    ContentDialogButton.Close -> dismiss()
                }
            })
    }
}
