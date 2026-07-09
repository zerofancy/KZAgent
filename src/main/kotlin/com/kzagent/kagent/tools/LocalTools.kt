package com.kzagent.kagent.tools

import java.nio.charset.MalformedInputException
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
    private val maxToolOutputChars: Int = 12_000,
) {
    fun registry(): ToolRegistry = ToolRegistry(
        listOf(
            listFilesTool(),
            readFileTool(),
            searchTextTool(),
            replaceInFileTool(),
            runCommandTool(),
        ),
    )

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
        description = "Read a bounded range from a text file in the workspace.",
        parameters = objectSchema(
            properties = mapOf(
                "path" to stringSchema("Workspace-relative file path."),
                "start_line" to intSchema("1-based start line. Defaults to 1."),
                "max_lines" to intSchema("Maximum lines to read, 1 to 500. Defaults to 200."),
            ),
            required = listOf("path"),
        ),
        requiresApproval = false,
    ) { args ->
        runCatching {
            val path = pathGuard.resolveWritableExistingFile(args.requiredString("path"))
            rejectSensitivePath(path)
            rejectLargeFile(path)
            val startLine = (args.int("start_line") ?: 1).coerceAtLeast(1)
            val maxLines = (args.int("max_lines") ?: 200).coerceIn(1, 500)
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

    private fun replaceInFileTool(): ToolDefinition = ToolDefinition(
        name = "replace_in_file",
        description = "Replace exactly one occurrence of old_text with new_text in a workspace file, or create the file with new_text when it does not exist. Does not require approval.",
        parameters = objectSchema(
            properties = mapOf(
                "path" to stringSchema("Workspace-relative file path."),
                "old_text" to stringSchema("Exact text to replace. Required when the file already exists; omit when creating a new file."),
                "new_text" to stringSchema("Replacement text, or full file content when creating a new file."),
            ),
            required = listOf("path", "new_text"),
        ),
        requiresApproval = false,
    ) { args ->
        runCatching {
            val path = pathGuard.resolveWritableFile(args.requiredString("path"))
            rejectSensitivePath(path)
            val newText = args.requiredString("new_text")

            if (!Files.exists(path)) {
                path.parent?.let { Files.createDirectories(it) }
                Files.writeString(path, newText, StandardCharsets.UTF_8)
                return@runCatching ToolResult.ok("Created ${pathGuard.display(path)}.")
            }

            rejectLargeFile(path)
            val oldText = args.requiredString("old_text")
            if (oldText.isEmpty()) throw IllegalArgumentException("old_text must not be empty for existing files.")

            val content = Files.readString(path, StandardCharsets.UTF_8)
            val matches = countOccurrences(content, oldText)
            when (matches) {
                0 -> throw IllegalArgumentException("old_text was not found in ${pathGuard.display(path)}.")
                1 -> Unit
                else -> throw IllegalArgumentException("old_text matched $matches times; refusing ambiguous edit.")
            }

            Files.writeString(path, content.replace(oldText, newText), StandardCharsets.UTF_8)
            ToolResult.ok("Updated ${pathGuard.display(path)}.")
        }.getOrElse { ToolResult.error(it.message ?: it.toString()) }
    }

    private fun runCommandTool(): ToolDefinition = ToolDefinition(
        name = "run_command",
        description = "Run a shell command in the workspace. Requires approval and blocks dangerous commands.",
        parameters = objectSchema(
            properties = mapOf(
                "command" to stringSchema("Shell command to run from the workspace root."),
                "timeout_seconds" to intSchema("Timeout in seconds, 1 to 120. Defaults to 30."),
            ),
            required = listOf("command"),
        ),
        requiresApproval = true,
    ) { args ->
        runCatching {
            val command = args.requiredString("command")
            val timeoutSeconds = (args.int("timeout_seconds") ?: 30).coerceIn(1, 120)
            validateCommand(command)
            if (!approvalPolicy.approve("run_command", "Command: $command\nTimeout: ${timeoutSeconds}s")) {
                return@runCatching ToolResult.error("User denied run_command.")
            }
            ToolResult.ok(truncate(runShell(command, Duration.ofSeconds(timeoutSeconds.toLong()))))
        }.getOrElse { ToolResult.error(it.message ?: it.toString()) }
    }

    private suspend fun runShell(command: String, timeout: Duration): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/bin/sh", "-lc", command)
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
            return@withContext "Command timed out after ${timeout.seconds}s.\n${output}"
        }
        readerThread.join(1_000)
        "Exit code: ${process.exitValue()}\n${output}"
    }

    private fun validateCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("command must not be empty.")
        if (trimmed.contains('\n')) throw IllegalArgumentException("multi-line shell commands are blocked.")
        val dangerous = Regex("""(^|[;&|]\s*|\s)(rm|sudo|chmod|chown|mkfs|dd|shutdown|reboot)\b""")
        if (dangerous.containsMatchIn(trimmed)) {
            throw IllegalArgumentException("blocked dangerous command: $trimmed")
        }
        if (Regex("""[<>]{1,2}\s*/""").containsMatchIn(trimmed)) {
            throw IllegalArgumentException("blocked redirection to absolute path.")
        }
        if (Regex("""(^|\s)\.\./""").containsMatchIn(trimmed)) {
            throw IllegalArgumentException("blocked path escape using ../")
        }
        if (SENSITIVE_NAMES.any { trimmed.contains(it) }) {
            throw IllegalArgumentException("blocked command referencing sensitive local config.")
        }
    }

    private fun readTextLines(path: Path): List<String> {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8)
        } catch (e: MalformedInputException) {
            throw IllegalArgumentException("File is not valid UTF-8 text: ${pathGuard.display(path)}")
        }
    }

    private fun rejectLargeFile(path: Path) {
        if (Files.size(path) > MAX_TEXT_FILE_BYTES) {
            throw IllegalArgumentException("File is too large for this MVP tool: ${pathGuard.display(path)}")
        }
    }

    private fun rejectSensitivePath(path: Path) {
        val parts = path.map { it.toString() }.toSet()
        if (parts.any { it in SENSITIVE_NAMES }) {
            throw IllegalArgumentException("Access to sensitive local config is blocked: ${pathGuard.display(path)}")
        }
    }

    private fun isIgnoredPath(path: Path): Boolean {
        val parts = path.map { it.toString() }.toSet()
        return parts.any { it in IGNORED_NAMES || it in SENSITIVE_NAMES }
    }

    private fun truncate(value: String): String =
        if (value.length <= maxToolOutputChars) value else value.take(maxToolOutputChars) + "\n...[truncated]"

    private fun countOccurrences(value: String, needle: String): Int {
        var count = 0
        var index = value.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = value.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing required string argument: $name")

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    companion object {
        private const val MAX_TEXT_FILE_BYTES = 1_000_000L
        private val IGNORED_NAMES = setOf(".git", ".gradle", "build", "out", ".kagent")
        private val SENSITIVE_NAMES = setOf("local.properties", ".env", ".env.local", ".envrc")
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
