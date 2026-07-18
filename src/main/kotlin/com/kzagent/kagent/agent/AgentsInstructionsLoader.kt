package com.kzagent.kagent.agent

import com.kzagent.kagent.tools.TextFileCodec
import java.nio.file.Files
import java.nio.file.Path

data class LoadedAgentsInstruction(
    val source: Path,
    val sourcePath: String,
    val scopePath: String,
    val content: String,
)

/**
 * Discovers canonical AGENTS.md files inside a workspace.
 *
 * The root instruction is loaded separately because it belongs to the durable
 * session system prompt. Nested instructions are discovered lazily after a
 * successful file read.
 */
class AgentsInstructionsLoader(workspace: Path) {
    private val root = workspace.toAbsolutePath().normalize().toRealPath()

    fun loadRoot(): LoadedAgentsInstruction? = loadFromDirectory(root)

    fun loadForRead(
        file: Path,
        alreadyLoaded: Set<Path> = emptySet(),
    ): List<LoadedAgentsInstruction> {
        val realFile = file.toAbsolutePath().normalize().toRealPath()
        requireInsideWorkspace(realFile)
        require(Files.isRegularFile(realFile)) { "Path is not a regular file: ${display(realFile)}" }

        val parent = realFile.parent ?: root
        // Root guidance is already part of the durable system prompt, so lazy
        // discovery begins at the first directory below the workspace root.
        if (parent == root) return emptyList()

        val instructions = mutableListOf<LoadedAgentsInstruction>()
        var directory = root
        for (part in root.relativize(parent)) {
            directory = directory.resolve(part)
            val loaded = loadFromDirectory(directory) ?: continue
            if (loaded.source !in alreadyLoaded) {
                instructions += loaded
            }
        }
        return instructions
    }

    private fun loadFromDirectory(directory: Path): LoadedAgentsInstruction? {
        val candidate = directory.resolve(FILE_NAME)
        if (!Files.exists(candidate)) return null

        val realSource = candidate.toRealPath()
        requireInsideWorkspace(realSource)
        require(Files.isRegularFile(realSource)) {
            "$FILE_NAME is not a regular file: ${display(realSource)}"
        }

        val content = TextFileCodec.read(realSource).text
        if (content.isBlank()) return null
        return LoadedAgentsInstruction(
            source = realSource,
            sourcePath = display(realSource),
            scopePath = display(directory),
            content = content,
        )
    }

    private fun requireInsideWorkspace(path: Path) {
        require(path.startsWith(root)) { "AGENTS.md path escapes workspace: $path" }
    }

    private fun display(path: Path): String =
        root.relativize(path.toAbsolutePath().normalize()).toString().ifBlank { "." }

    companion object {
        const val FILE_NAME = "AGENTS.md"
    }
}
