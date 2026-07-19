package com.kzagent.kagent.tools

import java.nio.file.Files
import java.nio.file.Path

data class ResolvedReadPath(
    val path: Path,
    val insideWorkspace: Boolean,
)

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

    /**
     * Resolves a single existing file without enforcing the workspace boundary.
     * Callers must obtain approval before accessing a result outside [root].
     */
    fun resolveReadableExistingFile(input: String?): ResolvedReadPath {
        val raw = input?.trim().orEmpty()
        require(raw.isNotEmpty()) { "Path must not be empty." }
        val supplied = Path.of(raw)
        val candidate = if (supplied.isAbsolute) supplied else root.resolve(supplied)
        val normalized = candidate.toAbsolutePath().normalize()
        if (!Files.exists(normalized)) {
            throw IllegalArgumentException("Path does not exist: $normalized")
        }
        val real = normalized.toRealPath()
        if (!Files.isRegularFile(real)) {
            throw IllegalArgumentException("Path is not a regular file: $real")
        }
        return ResolvedReadPath(real, real.startsWith(root))
    }

    fun resolveWritableFile(input: String?): Path {
        val candidate = resolveCandidate(input)
        if (Files.exists(candidate)) {
            val real = candidate.toRealPath()
            requireInsideRoot(real)
            if (!Files.isRegularFile(real)) {
                throw IllegalArgumentException("Path is not a regular file: ${display(real)}")
            }
            return real
        }

        var ancestor = candidate.parent
            ?: throw IllegalArgumentException("Path has no parent: $candidate")
        while (!Files.exists(ancestor)) {
            ancestor = ancestor.parent
                ?: throw IllegalArgumentException("No existing parent for path: ${display(candidate)}")
        }
        requireInsideRoot(ancestor.toRealPath())
        return candidate
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
