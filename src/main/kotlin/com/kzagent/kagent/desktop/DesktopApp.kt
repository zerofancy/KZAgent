package com.kzagent.kagent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.agent.estimateContextTokens
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Image
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.imageio.ImageIO
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
    var input by remember { mutableStateOf("") }
    val pendingApprovals = remember { mutableStateListOf<PendingApproval>() }
    var showDeleteConfirmIndex by remember { mutableStateOf(-1) }
    var showRenameDialogIndex by remember { mutableStateOf(-1) }
    var renameText by remember { mutableStateOf("") }
    var showCompressConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val approvalPolicy = ApprovalPolicy { action, details ->
        suspendCancellableCoroutine { continuation ->
            lateinit var approval: PendingApproval
            approval = PendingApproval(action, details) { allowed ->
                pendingApprovals.remove(approval)
                if (continuation.isActive) {
                    continuation.resume(allowed)
                }
            }
            continuation.invokeOnCancellation { pendingApprovals.remove(approval) }
            pendingApprovals.add(approval)
        }
    }

    val sessionManager = remember {
        SessionManager(approvalPolicy).also { it.loadOrCreate(workspace) }
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
                session.status = if (name == "run_command") "等待命令审批..." else "执行工具：$name"
            }
            override suspend fun onToolResult(name: String, result: ToolResult) {
                session.messages.add(DisplayMessage("tool_result", result.content, collapsible = true, collapsed = false))
                session.status = if (result.isError) "工具返回错误：$name" else "工具完成：$name"
            }
        }
    }

    // Ensure active session has a runtime
    LaunchedEffect(workspace, sessionManager.activeSession().id) {
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

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ---- Sidebar ----
                SessionSidebar(
                    sessions = sessionManager.sessions,
                    activeIndex = sessionManager.activeSessionIndex,
                    onSelect = { sessionManager.switchTo(it) },
                    onAdd = { sessionManager.addNewSession(workspace) },
                    onDelete = { showDeleteConfirmIndex = it },
                    onRename = { idx ->
                        renameText = sessionManager.sessions[idx].name
                        showRenameDialogIndex = idx
                    },
                    onChooseWorkspace = {
                        chooseWorkspace(workspace)?.let { newWs ->
                            sessionManager.cancelAllSessions()
                            pendingApprovals.toList().forEach { it.complete(false) }
                            workspace = newWs
                            sessionManager.sessions.clear()
                            sessionManager.loadOrCreate(newWs)
                        }
                    },
                    modifier = Modifier.width(240.dp).fillMaxSize(),
                )

                // ---- Divider ----
                VerticalDivider(modifier = Modifier.fillMaxHeight())

                // ---- Main Chat Area ----
                val session = sessionManager.activeSession()
                Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    Header(
                        workspace = workspace,
                        status = session.status,
                        isBusy = session.isBusy,
                        contextPercent = (session.usedTokens * 100) / (session.runtime?.contextWindowSize ?: 1_000_000),
                        onCompressContext = { showCompressConfirm = true },
                    )
                    Spacer(Modifier.height(8.dp))
                    session.error?.let {
                        ErrorBanner(it)
                        Spacer(Modifier.height(8.dp))
                    }
                    MessageList(session.messages, modifier = Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
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

@Composable
private fun SessionSidebar(
    sessions: List<SessionData>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onRename: (Int) -> Unit,
    onChooseWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "会话列表",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row {
                OutlinedButton(
                    onClick = onAdd,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("+", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = onChooseWorkspace,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("切换工作区", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(sessions, key = { _, s -> s.id }) { index, session ->
                val isActive = index == activeIndex
                val bgColor = if (isActive)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else
                    Color.Transparent

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .background(bgColor, shape = MaterialTheme.shapes.small)
                        .padding(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            session.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (session.isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        session.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { onRename(index) },
                            modifier = Modifier.height(24.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text("✎", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { onDelete(index) },
                            modifier = Modifier.height(24.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text("✕", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE53935))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
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
    onCompressContext: () -> Unit,
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
                onClick = onCompressContext,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        contextPercent > 80 -> Color(0xFFE53935)
                        else -> Color(0xFF5C6BC0)
                    },
                ),
            ) {
                Text("上下文 $contextPercent%")
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
private fun MessageList(messages: MutableList<DisplayMessage>, modifier: Modifier = Modifier) {
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
                MessageRow(index, message, messages)
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
private fun MessageRow(
    index: Int,
    message: DisplayMessage,
    messages: MutableList<DisplayMessage>,
) {
    val (title, bgColor) = when (message.role) {
        "user" -> "You" to Color.Transparent
        "assistant" -> "Assistant" to Color.Transparent
        "tool_call" -> "🛠 工具调用" to Color(0xFFE3F2FD) // light blue
        "tool_result" -> "📋 执行结果" to Color(0xFFF3E5F5) // light purple
        else -> message.role to Color.Transparent
    }

    val collapsedLabel = when (message.role) {
        "tool_call" -> "（展开查看工具调用详情）"
        "tool_result" -> "（展开查看执行结果）"
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, shape = MaterialTheme.shapes.small)
            .padding(8.dp)
            .let { mod ->
                if (message.collapsible) {
                    mod.clickable { toggleCollapse(index, messages) }
                } else mod
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            if (message.collapsible) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (message.collapsed) "▶" else "▼",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (message.collapsible && message.collapsed) {
            Text(
                collapsedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        } else {
            SelectionContainer {
                Text(
                    message.content,
                    style = when (message.role) {
                        "tool_call" -> MaterialTheme.typography.bodyMedium
                        "tool_result" -> MaterialTheme.typography.bodySmall
                        else -> MaterialTheme.typography.bodyLarge
                    },
                )
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
            is AgentMessage.Summary -> null
        }
    }
}

/** Format a tool invocation into a human-readable one-liner. */
private fun formatToolCallSummary(name: String, argsJson: String): String {
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
    val action: String,
    val details: String,
    val complete: (Boolean) -> Unit,
)
