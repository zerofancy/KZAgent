package com.kzagent.kagent.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class LocalTools(
    private val pathGuard: PathGuard,
    private val approvalPolicy: ApprovalPolicy,
    private val maxToolOutputChars: Int = 48_000,
    private val sensitivePathProtection: Boolean = false,
    private val webPageService: WebPageService? = null,
) {
    fun registry(): ToolRegistry = ToolRegistry(
        buildList {
            addAll(
                listOf(
                    listFilesTool(),
                    readFileTool(),
                    searchTextTool(),
                    applyPatchTool(),
                    runCommandTool(),
                ),
            )
            webPageService?.let { add(fetchWebPageTool(it)) }
        },
    )

    private fun fetchWebPageTool(service: WebPageService): ToolDefinition = ToolDefinition(
        name = "fetch_web_page",
        description = "Fetch and extract the main content of a public static HTTP(S) page. Returns metadata, Markdown content, and key links; it cannot execute JavaScript, authenticate, or access private networks.",
        parameters = objectSchema(
            properties = mapOf(
                "url" to stringSchema("Complete public http:// or https:// URL to fetch."),
            ),
            required = listOf("url"),
        ),
        requiresApproval = false,
        cost = 5,
    ) { args ->
        service.fetch(args.requiredString("url"))
    }

    private fun listFilesTool(): ToolDefinition = ToolDefinition(
        name = "list_files",
        description = "List files under a workspace path. Use this to inspect project structure.",
        parameters = objectSchema(
            properties = mapOf(
                "path" to stringSchema("Workspace-relative path. Defaults to current workspace."),
                "max_depth" to intSchema("Maximum directory depth, 1 to 8. Defaults to 3."),
            ),
            required = emptyList(),
        ),
        requiresApproval = false,
        cost = 1,
    ) { args ->
        runCatching {
            val start = pathGuard.resolveExisting(args.string("path") ?: ".")
            val maxDepth = (args.int("max_depth") ?: 3).coerceIn(1, 8)
            if (!Files.isDirectory(start)) {
                throw IllegalArgumentException("Path is not a directory: ${pathGuard.display(start)}")
            }

            val lines = Files.walk(start, maxDepth).use { stream ->
                stream
                    .filter { it != start }
                    .filter { !isIgnoredPath(it) }
                    .sorted()
                    .limit(500)
                    .map { path ->
                        val suffix = if (Files.isDirectory(path)) "/" else ""
                        pathGuard.display(path) + suffix
                    }
                    .collect(Collectors.toList())
            }
            ToolResult.ok(truncate(lines.joinToString("\n").ifBlank { "(empty)" }))
        }.getOrElse { ToolResult.error(it.message ?: it.toString()) }
    }

    private fun readFileTool(): ToolDefinition = ToolDefinition(
        name = "read_file",
        description = "Read a bounded range from a text file. Workspace files are read directly; external or protected sensitive files use the configured approval mode.",
        parameters = objectSchema(
            properties = mapOf(
                "path" to stringSchema("Workspace-relative file path."),
                "start_line" to intSchema("1-based start line. Defaults to 1."),
                "max_lines" to intSchema("Maximum lines to read (no hard cap; output truncated by character limit). Defaults to 200."),
            ),
            required = listOf("path"),
        ),
        requiresApproval = false,
        cost = 1,
    ) { args ->
        runCatching {
            val resolved = pathGuard.resolveReadableExistingFile(args.requiredString("path"))
            val path = resolved.path
            rejectLargeFile(path)
            val startLine = args.int("start_line") ?: 1
            val maxLines = args.int("max_lines") ?: 200
            require(startLine >= 1) { "start_line must be at least 1." }
            require(maxLines >= 1) { "max_lines must be at least 1." }
            val sensitive = sensitivePathProtection && isSensitivePath(path)
            var approvalSource: ApprovalSource? = null
            if (!resolved.insideWorkspace || sensitive) {
                val reasons = buildList {
                    if (!resolved.insideWorkspace) add("目标文件位于工作区外")
                    if (sensitive) add("目标是受保护的敏感文件")
                }
                val approval = approvalPolicy.approve(
                    ApprovalRequest.ExternalFileRead(
                        path = path,
                        startLine = startLine,
                        maxLines = maxLines,
                        outsideWorkspace = !resolved.insideWorkspace,
                        sensitive = sensitive,
                        workspace = pathGuard.root,
                        risk = RiskAssessment(reasons),
                    ),
                )
                approvalSource = approval.source
                if (!approval.allowed) {
                    return@runCatching ToolResult.error(
                        approval.denialMessage("read_file"),
                        approval.source,
                    )
                }
            }
            val lines = readTextLines(path)
            val selected = lines.drop(startLine - 1).take(maxLines)
            val numbered = selected.mapIndexed { index, line -> "${startLine + index}: $line" }
            ToolResult.ok(
                truncate(
                    if (numbered.isEmpty()) {
                        "(no lines in requested range)"
                    } else {
                        numbered.joinToString("\n")
                    },
                ),
                // External paths must never participate in scoped AGENTS.md discovery.
                readPaths = if (resolved.insideWorkspace) listOf(path) else emptyList(),
                approvalSource = approvalSource,
            )
        }.getOrElse { ToolResult.error(it.message ?: it.toString()) }
    }

    private fun searchTextTool(): ToolDefinition = ToolDefinition(
        name = "search_text",
        description = "Search text files in the workspace for a case-insensitive substring.",
        parameters = objectSchema(
            properties = mapOf(
                "query" to stringSchema("Text to search for."),
                "path" to stringSchema("Workspace-relative directory or file. Defaults to current workspace."),
                "max_results" to intSchema("Maximum matches, 1 to 200. Defaults to 50."),
            ),
            required = listOf("query"),
        ),
        requiresApproval = false,
        cost = 1,
    ) { args ->
        runCatching {
            val query = args.requiredString("query")
            val start = pathGuard.resolveExisting(args.string("path") ?: ".")
            val maxResults = (args.int("max_results") ?: 50).coerceIn(1, 200)
            val files = if (Files.isRegularFile(start)) {
                rejectSensitivePath(start)
                listOf(start)
            } else {
                Files.walk(start, 12).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter { !isIgnoredPath(it) }
                        .limit(2_000)
                        .collect(Collectors.toList())
                }
            }

            val matches = mutableListOf<String>()
            for (file in files) {
                if (matches.size >= maxResults) break
                if (Files.size(file) > MAX_TEXT_FILE_BYTES) continue
                val lines = runCatching { readTextLines(file) }.getOrNull() ?: continue
                lines.forEachIndexed { index, line ->
                    if (matches.size < maxResults && line.contains(query, ignoreCase = true)) {
                        matches += "${pathGuard.display(file)}:${index + 1}: $line"
                    }
                }
            }
            ToolResult.ok(truncate(matches.joinToString("\n").ifBlank { "(no matches)" }))
        }.getOrElse { ToolResult.error(it.message ?: it.toString()) }
    }

    private fun applyPatchTool(): ToolDefinition = ToolDefinition(
        name = "apply_patch",
        description = "Apply a standard git unified diff to workspace text files. Preserves detected encoding, BOM, and line endings. Does not require approval.",
        parameters = objectSchema(
            properties = mapOf(
                "patch" to stringSchema("A complete git-style unified diff beginning with 'diff --git'."),
            ),
            required = listOf("patch"),
        ),
        requiresApproval = false,
        cost = 2,
    ) { args ->
        runCatching {
            data class PendingChange(val path: Path, val content: TextFile?)
            val changes = UnifiedPatch.parse(args.requiredString("patch")).map { filePatch ->
                require(filePatch.oldPath == null || filePatch.newPath == null || filePatch.oldPath == filePatch.newPath) {
                    "File renames are not supported by apply_patch."
                }
                val relativePath = filePatch.newPath ?: filePatch.oldPath!!
                val path = pathGuard.resolveWritableFile(relativePath)
                rejectSensitivePath(path)
                if (filePatch.newPath == null) {
                    require(Files.isRegularFile(path)) { "Cannot delete missing file: $relativePath" }
                    rejectLargeFile(path)
                    applyFilePatch(relativePath, TextFileCodec.read(path).text, filePatch)
                    PendingChange(path, null)
                } else {
                    val original = if (Files.exists(path)) {
                        rejectLargeFile(path)
                        TextFileCodec.read(path)
                    } else {
                        require(filePatch.oldPath == null) { "File does not exist: $relativePath" }
                        TextFile("", StandardCharsets.UTF_8, byteArrayOf())
                    }
                    PendingChange(path, original.copy(text = applyFilePatch(relativePath, original.text, filePatch)))
                }
            }
            changes.forEach { change ->
                if (change.content == null) Files.delete(change.path)
                else {
                    change.path.parent?.let { Files.createDirectories(it) }
                    TextFileCodec.write(change.path, change.content)
                }
            }
            ToolResult.ok("Applied patch to ${changes.size} file(s): ${changes.joinToString { pathGuard.display(it.path) }}")
        }.getOrElse { ToolResult.error(it.message ?: it.toString()) }
    }

    private fun applyFilePatch(relativePath: String, original: String, patch: FilePatch): String =
        try {
            UnifiedPatch.apply(original, patch)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Patch failed for $relativePath:\n${error.message}", error)
        }

    private fun runCommandTool(): ToolDefinition = ToolDefinition(
        name = "run_command",
        description = "Run a shell command with a bounded execution time from the workspace using the configured manual, automatic, or full approval mode.",
        parameters = objectSchema(
            properties = mapOf(
                "command" to stringSchema("Shell command to run from the workspace root."),
                "timeout_seconds" to intSchema("Maximum command execution time in seconds, 1 to 120. Defaults to 30."),
            ),
            required = listOf("command"),
        ),
        requiresApproval = true,
        cost = 5,
    ) { args ->
        runCatching {
            val command = args.requiredString("command")
            val timeoutSeconds = args.int("timeout_seconds") ?: 30
            validateBasicCommand(command, timeoutSeconds)
            val timeout = Duration.ofSeconds(timeoutSeconds.toLong())
            val approval = approvalPolicy.approve(
                ApprovalRequest.CommandExecution(
                    command = command,
                    timeout = timeout,
                    workspace = pathGuard.root,
                    risk = CommandRiskAnalyzer.assess(command, sensitivePathProtection),
                ),
            )
            if (!approval.allowed) {
                return@runCatching ToolResult.error(
                    approval.denialMessage("run_command"),
                    approval.source,
                )
            }
            ToolResult.ok(
                truncate(runShell(command, timeout)),
                approvalSource = approval.source,
            )
        }.getOrElse { ToolResult.error(it.message ?: it.toString()) }
    }

    private suspend fun runShell(command: String, timeout: Duration): String = withContext(Dispatchers.IO) {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val shellCommand = if (isWindows) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("/bin/sh", "-lc", command)
        }
        val process = ProcessBuilder(shellCommand)
            .directory(pathGuard.root.toFile())
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length < maxToolOutputChars * 2) {
                        output.appendLine(line)
                    }
                }
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            readerThread.join(1_000)
            return@withContext "Command execution timed out after ${timeout.seconds}s.\n${output}"
        }
        readerThread.join(1_000)
        "Exit code: ${process.exitValue()}\n${output}"
    }

    private fun validateBasicCommand(command: String, timeoutSeconds: Int) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("command must not be empty.")
        require(timeoutSeconds in 1..120) { "timeout_seconds must be between 1 and 120." }
    }

    private fun readTextLines(path: Path): List<String> {
        try {
            return TextFileCodec.read(path).text.split("\r\n", "\n", "\r").let {
                if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unable to read text file ${pathGuard.display(path)}: ${e.message}")
        }
    }

    private fun rejectLargeFile(path: Path) {
        if (Files.size(path) > MAX_TEXT_FILE_BYTES) {
            throw IllegalArgumentException("File is too large for this MVP tool: ${pathGuard.display(path)}")
        }
    }

    private fun rejectSensitivePath(path: Path) {
        if (!sensitivePathProtection) return
        if (isSensitivePath(path)) {
            throw IllegalArgumentException("Access to sensitive local config is blocked: ${pathGuard.display(path)}")
        }
    }

    private fun isSensitivePath(path: Path): Boolean =
        path.any { part -> SENSITIVE_NAMES.any { it.equals(part.toString(), ignoreCase = true) } }

    private fun isIgnoredPath(path: Path): Boolean {
        val parts = path.map { it.toString() }.toSet()
        if (parts.any { it in IGNORED_NAMES }) return true
        if (sensitivePathProtection && parts.any { it in SENSITIVE_NAMES }) return true
        return false
    }

    private fun truncate(value: String): String =
        if (value.length <= maxToolOutputChars) value else value.take(maxToolOutputChars) + "\n...[truncated]"

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing required string argument: $name")

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun ApprovalResult.denialMessage(operation: String): String =
        when (source) {
            ApprovalSource.APPROVAL_AGENT -> "Approval agent denied $operation: $reason"
            else -> "User denied $operation: $reason"
        }

    companion object {
        private const val MAX_TEXT_FILE_BYTES = 1_000_000L
        private val IGNORED_NAMES = setOf(".git", ".gradle", "build", "out", ".kagent")
        internal val SENSITIVE_NAMES = setOf("local.properties", ".env", ".env.local", ".envrc")
    }
}

fun objectSchema(properties: Map<String, JsonObject>, required: List<String>): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            for ((name, schema) in properties) {
                put(name, schema)
            }
        })
        put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }

fun stringSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun intSchema(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}
