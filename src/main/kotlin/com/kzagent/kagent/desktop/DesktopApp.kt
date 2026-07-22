package com.kzagent.kagent.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.AppConfigLoader
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.config.ConfigWriter
import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.agent.estimateContextTokens
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.ApprovalDecision
import com.kzagent.kagent.tools.ApprovalMode
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ApprovalRequest
import com.kzagent.kagent.tools.ApprovalResult
import com.kzagent.kagent.tools.ApprovalSource
import com.kzagent.kagent.tools.actionLabel
import com.kzagent.kagent.tools.details
import com.kzagent.kagent.tools.ToolResult
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.composefluent.component.Icon as FluentIcon
import io.github.composefluent.component.MenuFlyoutContainer
import io.github.composefluent.component.MenuFlyoutItem
import io.github.composefluent.component.MenuItem
import io.github.composefluent.component.NavigationDisplayMode
import io.github.composefluent.component.NavigationView
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.SideNavHeader
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.menuItemSeparator
import io.github.composefluent.component.rememberNavigationState
import io.github.composefluent.component.AccentButton as FluentAccentButton
import io.github.composefluent.component.Text as FluentText
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.Document
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.MoreHorizontal
import io.github.composefluent.icons.regular.Rename
import io.github.composefluent.icons.regular.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.coroutines.resume
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

fun runDesktopApp() {
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
    val closed = CountDownLatch(1)
    val initialized = CountDownLatch(1)
    val windowShown = AtomicBoolean(false)
    val windowLifecycle = desktopWindowLifecycle(System.getProperty("os.name"))
    var startupFailure: Throwable? = null
    startPackagedAppFallbackWatchdog(windowShown)
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
                    KZAgentDesktopApp(initialWorkspace = Path.of("").toAbsolutePath().normalize())
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
            if (!openPackagedAppFallback("direct window failed")) {
                startupFailure = throwable
                closed.countDown()
            }
        } finally {
            initialized.countDown()
        }
    }
    initialized.await()
    startupFailure?.let { throw it }
    closed.await()
    desktopLog("closed")
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

private fun startPackagedAppFallbackWatchdog(windowShown: AtomicBoolean) {
    if (System.getProperty("kzagent.allowOpenFallback") != "true") return
    Thread {
        Thread.sleep(5_000)
        if (windowShown.get()) return@Thread
        openPackagedAppFallback("window was not shown")
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

private fun desktopLog(message: String, throwable: Throwable? = null) {
    val line = "${OffsetDateTime.now()} KZAgent desktop: $message"
    println(line)
    val logPath = desktopLogPath()
    runCatching {
        logPath.parent?.let(Files::createDirectories)
        Files.writeString(
            logPath,
            buildString {
                appendLine(line)
                if (throwable != null) {
                    appendLine(throwable.stackTraceToString())
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

@Composable
private fun KZAgentDesktopApp(initialWorkspace: Path) {
    var input by remember { mutableStateOf("") }
    val pendingApprovals = remember { mutableStateListOf<PendingApproval>() }
    var showDeleteConfirmIndex by remember { mutableStateOf(-1) }
    var showRenameDialogIndex by remember { mutableStateOf(-1) }
    var renameText by remember { mutableStateOf("") }
    var showCompressConfirm by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var savedConfig by remember { mutableStateOf<AppConfig?>(null) }
    val scope = rememberCoroutineScope()

    // Check configuration on startup; if API key is missing, open settings
    LaunchedEffect(Unit) {
        runCatching { AppConfigLoader.load() }
            .onSuccess { savedConfig = it }
            .onFailure {
                savedConfig = null
                showSettings = true
            }
    }

    // Helper to derive safe default values for settings panel
    fun settingsDefaults(): Pair<String, String> {
        val existing = savedConfig
        return if (existing != null) {
            existing.apiKey to existing.baseUrl
        } else {
            // Try to load partial config to prefill what's available
            runCatching {
                val cfg = AppConfigLoader.load()
                cfg.apiKey to cfg.baseUrl
            }.getOrDefault("" to AppConfig.DEFAULT_BASE_URL)
        }
    }

    val approvalPolicy = ApprovalPolicy { request ->
        suspendCancellableCoroutine { continuation ->
            lateinit var approval: PendingApproval
            approval = PendingApproval(request) { allowed ->
                pendingApprovals.remove(approval)
                if (continuation.isActive) {
                    continuation.resume(
                        ApprovalResult(
                            decision = if (allowed) ApprovalDecision.ALLOW else ApprovalDecision.DENY,
                            source = ApprovalSource.HUMAN,
                            reason = if (allowed) "用户已批准。" else "用户已拒绝。",
                        ),
                    )
                }
            }
            continuation.invokeOnCancellation { pendingApprovals.remove(approval) }
            pendingApprovals.add(approval)
        }
    }

    val sessionManager = remember(savedConfig) {
        SessionManager(approvalPolicy).also { it.loadOrCreate(initialWorkspace) }
    }

    // When settings are saved, reset session runtimes so they pick up new config
    fun onSettingsSaved() {
        savedConfig = runCatching { AppConfigLoader.load() }.getOrNull()
        // Clear all runtimes so they will be recreated with new config
        sessionManager.sessions.forEach { session ->
            session.currentJob?.cancel()
            session.runtime = null
            session.error = null
            session.status = "正在加载..."
        }
        showSettings = false
    }

    fun onApprovalModeChanged(mode: ApprovalMode) {
        val current = savedConfig ?: return
        if (current.approvalMode == mode) return
        ConfigWriter.save(current.copy(approvalMode = mode))
        onSettingsSaved()
    }

    // Auto-collapse tool messages when a session becomes idle
    LaunchedEffect(sessionManager.sessions.map { it.isBusy }) {
        sessionManager.sessions.forEach { session ->
            if (!session.isBusy) {
                session.messages.indices.forEach { i ->
                    if (session.messages[i].collapsible && !session.messages[i].collapsed) {
                        session.messages[i] = session.messages[i].copy(collapsed = true)
                    }
                }
            }
        }
    }

    fun createObserver(session: SessionData): AgentObserver {
        return object : AgentObserver {
            override suspend fun onModelRequest(turn: Int) {
                session.status = "请求模型（第 ${turn} 轮）..."
            }
            override suspend fun onAssistantMessage(content: String) {
                session.messages.add(DisplayMessage("assistant", content))
            }
            override suspend fun onToolCallStarted(name: String, argsJson: String) {
                val summary = formatToolCallSummary(name, argsJson)
                session.messages.add(DisplayMessage("tool_call", summary, collapsible = true, collapsed = false))
                session.status = when (name) {
                    "run_command" -> when (savedConfig?.approvalMode) {
                        com.kzagent.kagent.tools.ApprovalMode.MANUAL -> "等待命令审批..."
                        com.kzagent.kagent.tools.ApprovalMode.AUTO -> "正在自动审批命令..."
                        com.kzagent.kagent.tools.ApprovalMode.FULL -> "执行命令..."
                        null -> "正在自动审批命令..."
                    }
                    "read_file" -> when (savedConfig?.approvalMode) {
                        com.kzagent.kagent.tools.ApprovalMode.FULL -> "读取文件..."
                        else -> "检查文件读取权限..."
                    }
                    "fetch_web_page" -> "正在获取并解析网页..."
                    else -> "执行工具：$name"
                }
            }
            override suspend fun onToolResult(name: String, result: ToolResult) {
                session.messages.add(DisplayMessage("tool_result", result.content, collapsible = true, collapsed = false))
                session.status = when (result.approvalSource) {
                    ApprovalSource.STATIC_RULE -> "静态规则已放行：$name"
                    ApprovalSource.APPROVAL_AGENT ->
                        if (result.isError) "审批 Agent 已拒绝：$name" else "审批 Agent 已放行：$name"
                    ApprovalSource.HUMAN ->
                        if (result.isError) "人工已拒绝：$name" else "人工已批准：$name"
                    ApprovalSource.FULL_MODE -> "全部放行：$name"
                    null -> if (result.isError) "工具返回错误：$name" else "工具完成：$name"
                }
            }
        }
    }

    // Ensure active session has a runtime
    val activeSession = sessionManager.activeSession()
    LaunchedEffect(sessionManager, activeSession.id, activeSession.workspace, activeSession.runtime) {
        val session = sessionManager.activeSession()
        session.status = "正在加载..."
        val observer = createObserver(session)
        session.error = null
        runCatching { sessionManager.ensureRuntime(session, observer) }
            .onSuccess { session.status = "就绪" }
            .onFailure {
                session.error = SecretRedactor.redact(it.message ?: it.toString())
                session.status = "配置不可用"
            }
    }

    // Reusable context compression helper
    suspend fun performCompression(session: SessionData, manageBusyState: Boolean = true): Boolean {
        if ((manageBusyState && session.isBusy) || session.runtime == null) return false
        val sessionId = session.id
        val titleRevision = session.titleRevision
        if (manageBusyState) session.isBusy = true
        session.status = "正在压缩上下文..."
        return try {
            val compressed = session.runtime!!.agent.compressHistory(session.conversationHistory)
            session.conversationHistory = compressed
            session.usedTokens = estimateContextTokens(compressed)
            session.messages.add(DisplayMessage("tool_result", "✅ 上下文已压缩。之前的对话已总结为摘要，保留最近几条消息。"))
            session.status = "就绪"
            // Auto-update session title from compression summary
            val summary = compressed.firstOrNull()
            if (summary is AgentMessage.Summary) {
                val agent = session.runtime!!.agent
                scope.launch {
                    runCatching { agent.generateTitle(summary.content) }
                        .onSuccess { title ->
                            sessionManager.renameSessionIfRevisionMatches(sessionId, titleRevision, title)
                        }
                }
            }
            true
        } catch (e: Exception) {
            session.error = "压缩失败: ${SecretRedactor.redact(e.message ?: e.toString())}"
            session.status = "压缩失败"
            false
        } finally {
            if (manageBusyState) session.isBusy = false
        }
    }

    KZAgentFluentTheme {
        KZAgentNavigationView(
            sessions = sessionManager.sessions,
            activeIndex = sessionManager.activeSessionIndex,
            settingsSelected = showSettings,
            onSelectSession = { index ->
                sessionManager.switchTo(index)
                showSettings = false
            },
            onAddSession = {
                sessionManager.addNewSession()
                showSettings = false
            },
            onDeleteSession = { showDeleteConfirmIndex = it },
            onRenameSession = { index ->
                renameText = sessionManager.sessions[index].name
                showRenameDialogIndex = index
            },
            onChooseWorkspace = {
                scope.launch {
                    val session = sessionManager.activeSession()
                    chooseWorkspace(session.workspace)?.let { newWorkspace ->
                        sessionManager.changeWorkspace(session, newWorkspace)
                        showSettings = false
                    }
                }
            },
            onSettings = { showSettings = true },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showSettings) {
                val (defaultApiKey, defaultBaseUrl) = settingsDefaults()
                SettingsPanel(
                    initialApiKey = defaultApiKey,
                    initialBaseUrl = defaultBaseUrl,
                    initialModel = savedConfig?.model ?: AppConfig.DEFAULT_MODEL,
                    initialContextWindowSize = savedConfig?.contextWindowSize ?: AppConfig.DEFAULT_CONTEXT_WINDOW_SIZE,
                    initialSensitivePathProtection = savedConfig?.sensitivePathProtection ?: AppConfig.DEFAULT_SENSITIVE_PATH_PROTECTION,
                    initialUserPrompt = savedConfig?.userPrompt ?: "",
                    initialApprovalMode = savedConfig?.approvalMode ?: AppConfig.DEFAULT_APPROVAL_MODE,
                    onSave = { onSettingsSaved() },
                    onCancel = {
                        if (savedConfig != null) {
                            showSettings = false
                        }
                    },
                )
            } else {
                val session = sessionManager.activeSession()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Header(
                        workspace = session.workspace,
                        status = session.status,
                        isBusy = session.isBusy,
                        contextPercent = (session.usedTokens * 100) / (session.runtime?.contextWindowSize ?: 1_000_000),
                        approvalMode = savedConfig?.approvalMode ?: AppConfig.DEFAULT_APPROVAL_MODE,
                        onApprovalModeChanged = { onApprovalModeChanged(it) },
                        onCompressContext = { showCompressConfirm = true },
                    )
                    Spacer(Modifier.height(10.dp))
                    session.error?.let {
                        ErrorBanner(it)
                        Spacer(Modifier.height(12.dp))
                    }
                    MessageList(
                        messages = session.messages,
                        workspace = session.workspace,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Composer(
                        input = input,
                        isBusy = session.isBusy,
                        enabled = session.runtime != null,
                        onInputChange = { input = it },
                        onSend = {
                            val prompt = input.trim()
                            if (prompt.isEmpty()) return@Composer
                            val currentRuntime = session.runtime ?: return@Composer
                            input = ""
                            session.isBusy = true
                            session.error = null
                            session.status = "准备发送..."
                            session.messages.add(DisplayMessage("user", prompt))
                            val sessionId = session.id
                            val titleRevision = session.titleRevision
                            val job = scope.launch {
                                try {
                                    // Auto-compress when context exceeds 80%
                                    val ctxPct = (session.usedTokens * 100) / (session.runtime?.contextWindowSize ?: 1_000_000)
                                    if (ctxPct > 80) {
                                        session.status = "上下文超 80%，自动压缩..."
                                        session.messages.add(DisplayMessage("tool_result", "⚠️ 上下文使用率达 $ctxPct%，自动触发压缩..."))
                                        if (!performCompression(session, manageBusyState = false)) return@launch
                                    }
                                    val result = currentRuntime.agent.runConversation(prompt, session.conversationHistory)
                                    session.conversationHistory = result.history
                                    session.usedTokens = result.totalTokens
                                    session.status = "就绪"
                                    // Auto-title on first user message
                                    if (result.history.count { it is AgentMessage.User } == 1) {
                                        scope.launch {
                                            runCatching { currentRuntime.agent.generateTitle(prompt) }
                                                .onSuccess { title ->
                                                    sessionManager.renameSessionIfRevisionMatches(
                                                        sessionId,
                                                        titleRevision,
                                                        title,
                                                    )
                                                }
                                        }
                                    }
                                } catch (_: CancellationException) {
                                    session.status = "已终止"
                                } catch (e: Exception) {
                                    session.error = SecretRedactor.redact(e.message ?: e.toString())
                                    session.status = "请求失败"
                                } finally {
                                    session.isBusy = false
                                    session.currentJob = null
                                }
                            }
                            session.currentJob = job
                        },
                        onTerminate = {
                            session.currentJob?.cancel()
                            session.status = "正在终止..."
                        },
                    )
                }
            }
        }
    }

    pendingApprovals.firstOrNull()?.let { approval ->
        ApprovalDialog(approval)
    }

    // Compress confirmation dialog
    if (showCompressConfirm) {
        val session = sessionManager.activeSession()
        val ctxPct = (session.usedTokens * 100) / (session.runtime?.contextWindowSize ?: 1_000_000)
        AlertDialog(
            onDismissRequest = { showCompressConfirm = false },
            title = { Text("压缩上下文") },
            text = {
                Text(
                    "当前上下文使用率 $ctxPct%。压缩将使用 LLM 把较早的对话总结为摘要，" +
                    "仅保留最近几条消息。是否继续？"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showCompressConfirm = false
                    scope.launch {
                        performCompression(session)
                        session.isBusy = false
                    }
                }) { Text("压缩") }
            },
            dismissButton = {
                TextButton(onClick = { showCompressConfirm = false }) { Text("取消") }
            },
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirmIndex >= 0) {
        val sessionName = sessionManager.sessions.getOrNull(showDeleteConfirmIndex)?.name ?: ""
        AlertDialog(
            onDismissRequest = { showDeleteConfirmIndex = -1 },
            title = { Text("删除会话") },
            text = { Text("确定要删除会话「$sessionName」吗？此操作不可撤销。") },
            confirmButton = {
                Button(onClick = {
                    sessionManager.deleteSession(showDeleteConfirmIndex)
                    showDeleteConfirmIndex = -1
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmIndex = -1 }) { Text("取消") }
            },
        )
    }

    // Rename dialog
    if (showRenameDialogIndex >= 0) {
        AlertDialog(
            onDismissRequest = { showRenameDialogIndex = -1 },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("会话名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (renameText.isNotBlank()) {
                        sessionManager.renameSession(showRenameDialogIndex, renameText)
                    }
                    showRenameDialogIndex = -1
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialogIndex = -1 }) { Text("取消") }
            },
        )
    }
}

internal val NavigationCompactBreakpoint = 1000.dp

internal fun navigationDisplayModeForWidth(width: androidx.compose.ui.unit.Dp): NavigationDisplayMode =
    if (width < NavigationCompactBreakpoint) {
        NavigationDisplayMode.LeftCompact
    } else {
        NavigationDisplayMode.Left
    }

internal fun isSessionNavigationSelected(
    settingsSelected: Boolean,
    activeIndex: Int,
    sessionIndex: Int,
): Boolean = !settingsSelected && activeIndex == sessionIndex

internal fun shouldCollapseNavigationAfterDestination(displayMode: NavigationDisplayMode): Boolean =
    displayMode == NavigationDisplayMode.LeftCompact

@Composable
private fun KZAgentNavigationView(
    sessions: List<SessionData>,
    activeIndex: Int,
    settingsSelected: Boolean,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onDeleteSession: (Int) -> Unit,
    onRenameSession: (Int) -> Unit,
    onChooseWorkspace: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    pane: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val displayMode = navigationDisplayModeForWidth(maxWidth)
        val navigationState = rememberNavigationState(
            initialExpanded = displayMode == NavigationDisplayMode.Left,
        )

        fun runDestinationAction(action: () -> Unit) {
            action()
            if (shouldCollapseNavigationAfterDestination(displayMode)) {
                navigationState.expanded = false
            }
        }

        NavigationView(
            modifier = Modifier.fillMaxSize(),
            displayMode = displayMode,
            state = navigationState,
            title = { FluentText("KZAgent", maxLines = 1) },
            menuItems = {
                item(key = "new-session") {
                    MenuItem(
                        selected = false,
                        onClick = { runDestinationAction(onAddSession) },
                        text = { FluentText("新建会话", maxLines = 1) },
                        icon = { FluentIcon(Icons.Default.Add, contentDescription = null) },
                    )
                }
                item(key = "choose-workspace") {
                    MenuItem(
                        selected = false,
                        onClick = { runDestinationAction(onChooseWorkspace) },
                        text = { FluentText("切换工作区", maxLines = 1) },
                        icon = { FluentIcon(Icons.Default.Folder, contentDescription = null) },
                    )
                }
                menuItemSeparator(key = "session-separator")
                item(key = "recent-sessions-header") {
                    SideNavHeader {
                        FluentText("最近会话", maxLines = 1)
                    }
                }
                items(
                    count = sessions.size,
                    key = { sessions[it].id },
                    contentType = { "session" },
                ) { index ->
                    val session = sessions[index]
                    MenuItem(
                        selected = isSessionNavigationSelected(settingsSelected, activeIndex, index),
                        onClick = { runDestinationAction { onSelectSession(index) } },
                        text = { FluentText(session.name, maxLines = 1) },
                        icon = { FluentIcon(Icons.Default.Document, contentDescription = null) },
                        badge = {
                            SessionNavigationBadge(
                                busy = session.isBusy,
                                showActions = navigationState.expanded,
                                onRename = { onRenameSession(index) },
                                onDelete = { onDeleteSession(index) },
                            )
                        },
                    )
                }
            },
            footerItems = {
                item(key = "settings") {
                    MenuItem(
                        selected = settingsSelected,
                        onClick = { runDestinationAction(onSettings) },
                        text = { FluentText("设置", maxLines = 1) },
                        icon = { FluentIcon(Icons.Default.Settings, contentDescription = null) },
                    )
                }
            },
            pane = pane,
        )
    }
}

@Composable
private fun SessionNavigationBadge(
    busy: Boolean,
    showActions: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            ProgressRing(size = ProgressRingSize.Small)
        }
        if (showActions) {
            MenuFlyoutContainer(
                flyout = {
                    MenuFlyoutItem(
                        onClick = {
                            isFlyoutVisible = false
                            onRename()
                        },
                        text = { FluentText("重命名") },
                        icon = { FluentIcon(Icons.Default.Rename, contentDescription = null) },
                    )
                    MenuFlyoutItem(
                        onClick = {
                            isFlyoutVisible = false
                            onDelete()
                        },
                        text = { FluentText("删除") },
                        icon = { FluentIcon(Icons.Default.Delete, contentDescription = null) },
                    )
                },
            ) {
                SubtleButton(
                    onClick = { isFlyoutVisible = !isFlyoutVisible },
                    modifier = Modifier.size(28.dp),
                    iconOnly = true,
                ) {
                    FluentIcon(Icons.Default.MoreHorizontal, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun Header(
    workspace: Path,
    status: String,
    isBusy: Boolean,
    contextPercent: Int,
    approvalMode: ApprovalMode,
    onApprovalModeChanged: (ApprovalMode) -> Unit,
    onCompressContext: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val useSingleRow = maxWidth >= 720.dp
        if (useSingleRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceIdentity(workspace = workspace, modifier = Modifier.weight(1f))
                StatusPill(status = status, isBusy = isBusy)
                HeaderActions(
                    approvalMode = approvalMode,
                    contextPercent = contextPercent,
                    isBusy = isBusy,
                    onApprovalModeChanged = onApprovalModeChanged,
                    onCompressContext = onCompressContext,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorkspaceIdentity(workspace = workspace, modifier = Modifier.weight(1f))
                    StatusPill(status = status, isBusy = isBusy)
                }
                Spacer(Modifier.height(8.dp))
                HeaderActions(
                    approvalMode = approvalMode,
                    contextPercent = contextPercent,
                    isBusy = isBusy,
                    onApprovalModeChanged = onApprovalModeChanged,
                    onCompressContext = onCompressContext,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceIdentity(workspace: Path, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            workspaceProjectName(workspace),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            workspace.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeaderActions(
    approvalMode: ApprovalMode,
    contextPercent: Int,
    isBusy: Boolean,
    onApprovalModeChanged: (ApprovalMode) -> Unit,
    onCompressContext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ApprovalModeMenu(
            approvalMode = approvalMode,
            onApprovalModeChanged = onApprovalModeChanged,
        )
        Button(
            onClick = onCompressContext,
            enabled = !isBusy,
            modifier = Modifier.height(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    contextPercent > 80 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text("上下文 $contextPercent%", maxLines = 1)
        }
    }
}

@Composable
private fun StatusPill(status: String, isBusy: Boolean) {
    Row(
        modifier = Modifier
            .widthIn(max = 180.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(Color(0xFF107C10), CircleShape),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun workspaceProjectName(workspace: Path): String =
    workspace.toAbsolutePath().normalize().fileName?.toString()
        ?.takeIf { it.isNotBlank() }
        ?: workspace.toAbsolutePath().normalize().toString()

internal fun approvalModeLabel(mode: ApprovalMode): String = when (mode) {
    ApprovalMode.AUTO -> "自动"
    ApprovalMode.MANUAL -> "手动"
    ApprovalMode.FULL -> "全部放行"
}

internal fun requiresFullModeConfirmation(current: ApprovalMode, target: ApprovalMode): Boolean =
    target == ApprovalMode.FULL && current != ApprovalMode.FULL

@Composable
private fun ApprovalModeMenu(
    approvalMode: ApprovalMode,
    onApprovalModeChanged: (ApprovalMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmFullMode by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) {
            Text("审批：${approvalModeLabel(approvalMode)} ▾", maxLines = 1)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ApprovalMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                if (mode == approvalMode) "✓ ${approvalModeLabel(mode)}" else approvalModeLabel(mode),
                            )
                            Text(
                                when (mode) {
                                    ApprovalMode.AUTO -> "静态规则、审批 Agent、必要时人工"
                                    ApprovalMode.MANUAL -> "所有受控操作逐次人工确认"
                                    ApprovalMode.FULL -> "命令和外部读取直接放行"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mode == ApprovalMode.FULL) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                },
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (requiresFullModeConfirmation(approvalMode, mode)) {
                            confirmFullMode = true
                        } else {
                            onApprovalModeChanged(mode)
                        }
                    },
                )
            }
        }
    }

    if (confirmFullMode) {
        AlertDialog(
            onDismissRequest = { confirmFullMode = false },
            title = { Text("确认全部放行") },
            text = {
                Text(
                    "全部放行会以当前系统用户权限直接执行命令，并允许读取工作区外或敏感文件，" +
                        "不会调用审批 Agent 或弹出人工确认。",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmFullMode = false
                    onApprovalModeChanged(ApprovalMode.FULL)
                }) {
                    Text("我了解风险，继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFullMode = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        SelectionContainer {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MessageList(
    messages: MutableList<DisplayMessage>,
    workspace: Path,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(messages.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(start = 22.dp, top = 22.dp, end = 34.dp, bottom = 22.dp),
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✦", color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("开始一个新会话", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "描述你想在当前工作区完成的任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                messages.forEachIndexed { index, message ->
                    MessageRow(index, message, messages, workspace)
                    if (index != messages.lastIndex) {
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun MessageRow(
    index: Int,
    message: DisplayMessage,
    messages: MutableList<DisplayMessage>,
    workspace: Path,
) {
    val (title, avatar) = when (message.role) {
        "user" -> "你" to "你"
        "assistant" -> "KZAgent" to "K"
        "tool_call" -> "工具调用" to "⌘"
        "tool_result" -> "执行结果" to "✓"
        else -> message.role to "·"
    }
    val background = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
        "tool_call", "tool_result" -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val avatarBackground = when (message.role) {
        "assistant" -> MaterialTheme.colorScheme.primary
        "user" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val avatarForeground = when (message.role) {
        "assistant" -> MaterialTheme.colorScheme.onPrimary
        "user" -> MaterialTheme.colorScheme.onSecondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val collapsedLabel = when (message.role) {
        "tool_call" -> "（展开查看工具调用详情）"
        "tool_result" -> "（展开查看执行结果）"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, shape = MaterialTheme.shapes.medium)
            .border(1.dp, borderColor, MaterialTheme.shapes.medium)
            .padding(14.dp)
            .let { mod ->
                if (message.collapsible) {
                    mod.clickable { toggleCollapse(index, messages) }
                } else mod
            },
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(avatarBackground, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(avatar, color = avatarForeground, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                if (message.collapsible) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (message.collapsed) "›" else "⌄",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            if (message.collapsible && message.collapsed) {
                Text(
                    collapsedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MessageContent(message, workspace)
            }
        }
    }
}

private fun toggleCollapse(index: Int, messages: MutableList<DisplayMessage>) {
    messages[index] = messages[index].copy(collapsed = !messages[index].collapsed)
}

@Composable
private fun Composer(
    input: String,
    isBusy: Boolean,
    enabled: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onTerminate: () -> Unit,
) {
    val isMacOs = remember { System.getProperty("os.name").lowercase().contains("mac") }
    var fieldValue by remember { mutableStateOf(TextFieldValue(input, TextRange(input.length))) }

    LaunchedEffect(input) {
        if (input != fieldValue.text) {
            fieldValue = TextFieldValue(input, TextRange(input.length))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    onInputChange(it.text)
                },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 68.dp, max = 148.dp)
                // Handle Enter in the preview phase so plain Enter can send and the
                // platform shortcut can insert a newline at the current selection.
                .onPreviewKeyEvent { event ->
                    when (
                        resolveComposerKeyAction(
                            isEnter = event.key == Key.Enter,
                            isCtrlPressed = event.isCtrlPressed,
                            isMetaPressed = event.isMetaPressed,
                            isMacOs = isMacOs,
                            eventType = event.type,
                            isBusy = isBusy,
                        )
                    ) {
                        ComposerKeyAction.InsertLineBreak -> {
                            val updatedValue = insertLineBreak(fieldValue)
                            fieldValue = updatedValue
                            onInputChange(updatedValue.text)
                            true
                        }
                        ComposerKeyAction.Send -> {
                            onSend()
                            true
                        }
                        ComposerKeyAction.Terminate -> {
                            onTerminate()
                            true
                        }
                        ComposerKeyAction.Consume -> true
                        ComposerKeyAction.PassThrough -> false
                    }
                },
                placeholder = { Text("向 KZAgent 描述任务…") },
                maxLines = 6,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.width(10.dp))
            if (isBusy) {
                Button(
                    onClick = onTerminate,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                ) {
                    Text("终止")
                }
            } else {
                FluentAccentButton(
                    onClick = onSend,
                    disabled = !enabled || input.isBlank(),
                    modifier = Modifier.height(40.dp).widthIn(min = 76.dp),
                ) {
                    FluentText("发送  ↑")
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            if (isMacOs) {
                "Enter 发送  ·  Command+Enter 换行"
            } else {
                "Enter 发送  ·  Ctrl+Enter 换行"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

internal enum class ComposerKeyAction {
    InsertLineBreak,
    Send,
    Terminate,
    Consume,
    PassThrough,
}

/**
 * Keeps keyboard behavior testable without constructing platform-specific key events.
 * Enter is consumed on both phases; only KeyDown performs a send or line-break action.
 */
internal fun resolveComposerKeyAction(
    isEnter: Boolean,
    isCtrlPressed: Boolean,
    isMetaPressed: Boolean,
    isMacOs: Boolean,
    eventType: KeyEventType,
    isBusy: Boolean,
): ComposerKeyAction = when {
    !isEnter -> ComposerKeyAction.PassThrough
    (if (isMacOs) isMetaPressed else isCtrlPressed) -> {
        if (eventType == KeyEventType.KeyDown) {
            ComposerKeyAction.InsertLineBreak
        } else {
            ComposerKeyAction.Consume
        }
    }
    isCtrlPressed -> ComposerKeyAction.PassThrough
    eventType != KeyEventType.KeyDown -> ComposerKeyAction.Consume
    isBusy -> ComposerKeyAction.Terminate
    else -> ComposerKeyAction.Send
}

internal fun insertLineBreak(value: TextFieldValue): TextFieldValue {
    val selectionStart = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val selectionEnd = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val updatedText = value.text.replaceRange(selectionStart, selectionEnd, "\n")
    return value.copy(
        text = updatedText,
        selection = TextRange(selectionStart + 1),
        composition = null,
    )
}

internal enum class ApprovalKeyAction {
    Approve,
    Deny,
    Consume,
    PassThrough,
}

internal fun resolveApprovalKeyAction(
    isEnter: Boolean,
    isEscape: Boolean,
    eventType: KeyEventType,
    highRisk: Boolean,
): ApprovalKeyAction = when {
    !isEnter && !isEscape -> ApprovalKeyAction.PassThrough
    eventType != KeyEventType.KeyUp -> ApprovalKeyAction.Consume
    isEscape -> ApprovalKeyAction.Deny
    highRisk -> ApprovalKeyAction.Consume
    else -> ApprovalKeyAction.Approve
}

@Composable
private fun ApprovalDialog(approval: PendingApproval) {
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = { approval.complete(false) },
        title = { Text(if (approval.highRisk) "高风险操作审批" else approval.request.actionLabel()) },
        text = {
            Box(
                modifier = Modifier.focusRequester(focusRequester)
                    .focusable()
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .onKeyEvent { event ->
                        when (
                            resolveApprovalKeyAction(
                                isEnter = event.key == Key.Enter,
                                isEscape = event.key == Key.Escape,
                                eventType = event.type,
                                highRisk = approval.highRisk,
                            )
                        ) {
                            ApprovalKeyAction.Approve -> {
                                approval.complete(true)
                                true
                            }
                            ApprovalKeyAction.Deny -> {
                                approval.complete(false)
                                true
                            }
                            ApprovalKeyAction.Consume -> true
                            ApprovalKeyAction.PassThrough -> false
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = 12.dp),
                ) {
                    if (approval.highRisk) {
                        Text(
                            "此操作可能产生高风险影响，请确认后再继续。",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(approval.request.actionLabel(), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            buildString {
                                append(approval.request.details())
                                if (approval.request.risk.isHighRisk) {
                                    appendLine()
                                    appendLine()
                                    append("风险：${approval.request.risk.reasons.joinToString("；")}")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { approval.complete(true) }) {
                Text(if (approval.highRisk) "仍然执行" else "允许")
            }
        },
        dismissButton = {
            TextButton(onClick = { approval.complete(false) }) {
                Text("拒绝")
            }
        },
    )
}

private suspend fun chooseWorkspace(current: Path): Path? =
    FileKit.openDirectoryPicker(directory = PlatformFile(current.toFile()))
        ?.file
        ?.toPath()
        ?.toAbsolutePath()
        ?.normalize()

fun List<AgentMessage>.toDisplayMessages(): List<DisplayMessage> {
    return mapNotNull { message ->
        when (message) {
            is AgentMessage.User -> DisplayMessage("user", message.content)
            is AgentMessage.Assistant -> {
                if (!message.content.isNullOrBlank()) {
                    DisplayMessage("assistant", message.content)
                } else if (message.toolCalls.isNotEmpty()) {
                    val summary = message.toolCalls.joinToString("\n") { tc ->
                        formatToolCallSummary(tc.name, tc.argumentsJson)
                    }
                    DisplayMessage("tool_call", summary, collapsible = true)
                } else null
            }
            is AgentMessage.Tool -> DisplayMessage(
                "tool_result",
                if (message.isError) "错误: ${message.content}" else message.content,
                collapsible = true,
            )
            is AgentMessage.System -> null
            is AgentMessage.ScopedInstruction -> null
            is AgentMessage.Summary -> null
        }
    }
}

/** Format a tool invocation into a human-readable one-liner. */
internal fun formatToolCallSummary(name: String, argsJson: String): String {
    return if (name == "run_command") {
        val command = extractArg(argsJson, "command")
        "运行命令: $command"
    } else if (name == "read_file") {
        val path = extractArg(argsJson, "path")
        "读取文件: $path"
    } else if (name == "list_files") {
        val path = extractArg(argsJson, "path") ?: "."
        "查看目录: $path"
    } else if (name == "search_text") {
        val query = extractArg(argsJson, "query")
        "搜索: $query"
    } else if (name == "apply_patch") {
        val files = extractPatchedFiles(argsJson)
        "应用文件补丁 " + files.joinToString(", ")
    } else if (name == "fetch_web_page") {
        val url = extractArg(argsJson, "url")
        "获取网页: $url"
    } else {
        "$name($argsJson)"
    }
}

internal fun extractArg(json: String, key: String): String? = runCatching {
    Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.contentOrNull
}.getOrNull()

/** Extract a bounded list of affected paths without running regex over a large patch. */
internal fun extractPatchedFiles(argsJson: String): List<String> {
    val patch = extractArg(argsJson, "patch") ?: return listOf("(patch)")
    return patch.lineSequence()
        .mapNotNull { line ->
            if (!line.startsWith("diff --git a/")) return@mapNotNull null
            val paths = line.removePrefix("diff --git a/")
            val separator = paths.indexOf(" b/")
            paths.takeIf { separator > 0 }?.substring(0, separator)
        }
        .distinct()
        .take(20)
        .toList()
        .ifEmpty { listOf("(patch)") }
}

data class DisplayMessage(
    val role: String,
    val content: String,
    val collapsible: Boolean = false,
    val collapsed: Boolean = true,
)

private data class PendingApproval(
    val request: ApprovalRequest,
    val complete: (Boolean) -> Unit,
) {
    val highRisk: Boolean get() = request.risk.isHighRisk
}
