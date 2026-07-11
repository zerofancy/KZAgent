package com.kzagent.kagent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.AgentRuntime
import com.kzagent.kagent.AgentRuntimeFactory
import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.coroutines.resume
import kotlin.system.exitProcess

fun runDesktopApp() {
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
    var startupFailure: Throwable? = null
    startPackagedAppFallbackWatchdog(windowShown)
    SwingUtilities.invokeLater {
        try {
            desktopLog("creating JFrame")
            val loadingPanel = JPanel(BorderLayout()).apply {
                add(JLabel("KZAgent loading..."), BorderLayout.CENTER)
            }
            val frame = JFrame("KZAgent").apply {
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                minimumSize = Dimension(880, 600)
                preferredSize = Dimension(1120, 760)
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
        ?: Path.of(System.getProperty("user.home"), ".kzagent", "desktop.log")

private fun showWindowInForeground(window: java.awt.Window) {
    window.minimumSize = Dimension(880, 600)
    window.setLocationRelativeTo(null)
    window.isAlwaysOnTop = true
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
    var workspace by remember { mutableStateOf(initialWorkspace) }
    var runtime by remember { mutableStateOf<AgentRuntime?>(null) }
    var conversationHistory by remember { mutableStateOf<List<AgentMessage>>(emptyList()) }
    var messages by remember { mutableStateOf<List<DisplayMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("正在加载工作区...") }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var pendingApproval by remember { mutableStateOf<PendingApproval?>(null) }
    var usedTokens by remember { mutableStateOf(0) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val approvalPolicy = ApprovalPolicy { action, details ->
        suspendCancellableCoroutine { continuation ->
            pendingApproval = PendingApproval(action, details) { allowed ->
                pendingApproval = null
                if (continuation.isActive) {
                    continuation.resume(allowed)
                }
            }
        }
    }
    val observer = object : AgentObserver {
        override suspend fun onModelRequest(turn: Int) {
            status = "请求模型（第 ${turn} 轮）..."
        }

        override suspend fun onToolCall(name: String) {
            status = if (name == "run_command") {
                "等待命令审批..."
            } else {
                "执行工具：$name"
            }
        }

        override suspend fun onToolResult(name: String, result: ToolResult) {
            status = if (result.isError) {
                "工具返回错误：$name"
            } else {
                "工具完成：$name"
            }
        }
    }

    LaunchedEffect(workspace) {
        runtime = null
        error = null
        status = "正在加载工作区..."
        val loadedHistory = runCatching {
            com.kzagent.kagent.agent.SessionReader(workspace).loadLatestHistory().orEmpty()
        }.getOrDefault(emptyList())
        conversationHistory = loadedHistory
        messages = loadedHistory.toDisplayMessages()
        usedTokens = runCatching {
            com.kzagent.kagent.agent.SessionReader(workspace).loadLatestTokenCount()
        }.getOrDefault(0)
        runCatching {
            AgentRuntimeFactory.create(workspace, approvalPolicy, observer)
        }.onSuccess {
            runtime = it
            status = "就绪"
        }.onFailure {
            error = SecretRedactor.redact(it.message ?: it.toString())
            status = "配置不可用"
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Header(
                    workspace = workspace,
                    status = status,
                    isBusy = isBusy,
                    contextPercent = (usedTokens * 100) / (runtime?.contextWindowSize ?: 1_000_000),
                    onNewSession = { showNewSessionDialog = true },
                    onChooseWorkspace = {
                        chooseWorkspace(workspace)?.let { workspace = it }
                    },
                )
                Spacer(Modifier.height(12.dp))
                error?.let {
                    ErrorBanner(it)
                    Spacer(Modifier.height(12.dp))
                }
                MessageList(messages, modifier = Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Composer(
                    input = input,
                    isBusy = isBusy,
                    enabled = runtime != null,
                    onInputChange = { input = it },
                    onSend = {
                        val prompt = input.trim()
                        if (prompt.isEmpty()) return@Composer
                        val currentRuntime = runtime ?: return@Composer
                        input = ""
                        isBusy = true
                        error = null
                        status = "准备发送..."
                        messages = messages + DisplayMessage("user", prompt)
                        val job = scope.launch {
                            try {
                                val result = currentRuntime.agent.runConversation(prompt, conversationHistory)
                                conversationHistory = result.history
                                messages = result.history.toDisplayMessages()
                                usedTokens = result.totalTokens
                                status = "就绪"
                            } catch (_: CancellationException) {
                                status = "已终止"
                            } catch (e: Exception) {
                                error = SecretRedactor.redact(e.message ?: e.toString())
                                status = "请求失败"
                            } finally {
                                isBusy = false
                                currentJob = null
                            }
                        }
                        currentJob = job
                    },
                    onTerminate = {
                        currentJob?.cancel()
                        status = "正在终止..."
                    },
                )
            }
        }
    }

    pendingApproval?.let { approval ->
        ApprovalDialog(approval)
    }

    if (showNewSessionDialog) {
        val cws = runtime?.contextWindowSize ?: 1_000_000
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text("新建会话") },
            text = {
                Text("当前上下文已使用 ${(usedTokens * 100) / cws}%（${usedTokens}/${cws}）。是否开启一个新的会话？")
            },
            confirmButton = {
                Button(onClick = {
                    conversationHistory = emptyList()
                    messages = emptyList()
                    usedTokens = 0
                    showNewSessionDialog = false
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun Header(
    workspace: Path,
    status: String,
    isBusy: Boolean,
    contextPercent: Int,
    onNewSession: () -> Unit,
    onChooseWorkspace: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("KZAgent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                workspace.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onNewSession,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (contextPercent > 80) Color(0xFFE53935) else Color(0xFF5C6BC0),
                ),
            ) {
                Text("上下文 $contextPercent%")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onChooseWorkspace, enabled = !isBusy) {
                Text("切换工作区")
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFEBEE))
            .padding(12.dp),
    ) {
        Text(
            text = message,
            color = Color(0xFFB71C1C),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MessageList(messages: List<DisplayMessage>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(messages.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Column(
        modifier = modifier
            .background(Color(0xFFF7F7F8))
            .padding(16.dp)
            .verticalScroll(scrollState),
    ) {
        if (messages.isEmpty()) {
            Text(
                "暂无会话",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        } else {
            messages.forEachIndexed { index, message ->
                MessageRow(message)
                if (index != messages.lastIndex) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: DisplayMessage) {
    val title = if (message.role == "user") "You" else "Assistant"
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(message.content, style = MaterialTheme.typography.bodyLarge)
        }
    }
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
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 88.dp, max = 160.dp)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp &&
                        event.key == Key.Enter &&
                        !event.isCtrlPressed
                    ) {
                        if (isBusy) onTerminate() else onSend()
                        true
                    } else {
                        false
                    }
                },
            label = { Text("输入问题 (Enter 发送, Ctrl+Enter 换行)") },
            maxLines = 6,
        )
        Spacer(Modifier.width(12.dp))
        if (isBusy) {
            Button(
                onClick = onTerminate,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                modifier = Modifier.height(56.dp),
            ) {
                Text("终止")
            }
        } else {
            Button(
                onClick = onSend,
                enabled = enabled && input.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
private fun ApprovalDialog(approval: PendingApproval) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = { approval.complete(false) },
        title = { Text("命令执行审批") },
        text = {
            Box(
                modifier = Modifier.focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp) {
                            when (event.key) {
                                Key.Enter -> { approval.complete(true); true }
                                Key.Escape -> { approval.complete(false); true }
                                else -> false
                            }
                        } else false
                    }
            ) {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(approval.action, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(approval.details, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { approval.complete(true) }) {
                Text("允许")
            }
        },
        dismissButton = {
            TextButton(onClick = { approval.complete(false) }) {
                Text("拒绝")
            }
        },
    )
}

private fun chooseWorkspace(current: Path): Path? {
    val chooser = JFileChooser(current.toFile())
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = "选择 KZAgent 工作区"
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.toPath().toAbsolutePath().normalize()
    } else {
        null
    }
}

private fun List<AgentMessage>.toDisplayMessages(): List<DisplayMessage> {
    return mapNotNull { message ->
        when (message) {
            is AgentMessage.User -> DisplayMessage("user", message.content)
            is AgentMessage.Assistant -> message.content
                ?.takeIf { it.isNotBlank() }
                ?.let { DisplayMessage("assistant", it) }
            is AgentMessage.System,
            is AgentMessage.Tool,
            -> null
        }
    }
}

private data class DisplayMessage(
    val role: String,
    val content: String,
)

private data class PendingApproval(
    val action: String,
    val details: String,
    val complete: (Boolean) -> Unit,
)
