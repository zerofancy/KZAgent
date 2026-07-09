package com.kzagent.kagent.tools

import java.nio.file.Files
import java.nio.file.Path

class PathGuard(workspace: Path) {
    val root: Path = workspace.toAbsolutePath().normalize().toRealPath()

    fun resolveExisting(input: String?): Path {
        val candidate = resolveCandidate(input)
        if (!Files.exists(candidate)) {
            throw IllegalArgumentException("Path does not exist: ${display(candidate)}")
        }
        val real = candidate.toRealPath()
        requireInsideRoot(real)
        return real
    }

    fun resolveWritableExistingFile(input: String?): Path {
        val path = resolveExisting(input)
        if (!Files.isRegularFile(path)) {
            throw IllegalArgumentException("Path is not a regular file: ${display(path)}")
        }
        return path
    }

    fun display(path: Path): String = root.relativize(path.toAbsolutePath().normalize()).toString()
        .ifBlank { "." }

    private fun resolveCandidate(input: String?): Path {
        val raw = input?.trim().orEmpty().ifEmpty { "." }
        val path = Path.of(raw)
        val candidate = if (path.isAbsolute) path else root.resolve(path)
        val normalized = candidate.toAbsolutePath().normalize()
        requireInsideRoot(normalized)
        return normalized
    }

    private fun requireInsideRoot(path: Path) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw IllegalArgumentException("Path escapes workspace: $path")
        }
    }
}

