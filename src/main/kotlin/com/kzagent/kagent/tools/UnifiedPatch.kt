package com.kzagent.kagent.tools

internal data class FilePatch(val oldPath: String?, val newPath: String?, val hunks: List<PatchHunk>)
internal data class PatchHunk(val oldStart: Int, val lines: List<PatchLine>)
internal data class PatchLine(val kind: Char, val text: String)

internal object UnifiedPatch {
    private val hunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*$""")

    fun parse(input: String): List<FilePatch> {
        val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = mutableListOf<FilePatch>()
        var index = 0
        while (index < lines.size) {
            if (!lines[index].startsWith("diff --git ")) { index++; continue }
            index++
            while (index < lines.size && !lines[index].startsWith("--- ")) index++
            require(index + 1 < lines.size && lines[index + 1].startsWith("+++ ")) { "Invalid patch: missing ---/+++ headers." }
            val oldPath = parsePath(lines[index].removePrefix("--- "))
            val newPath = parsePath(lines[index + 1].removePrefix("+++ "))
            index += 2
            val hunks = mutableListOf<PatchHunk>()
            while (index < lines.size && !lines[index].startsWith("diff --git ")) {
                val match = hunkHeader.matchEntire(lines[index])
                if (match == null) { index++; continue }
                val oldStart = match.groupValues[1].toInt()
                index++
                val hunkLines = mutableListOf<PatchLine>()
                while (index < lines.size && !lines[index].startsWith("@@ ") && !lines[index].startsWith("diff --git ")) {
                    val line = lines[index]
                    if (line == "\\ No newline at end of file") { index++; continue }
                    if (line.isEmpty() || line[0] !in charArrayOf(' ', '+', '-')) break
                    hunkLines += PatchLine(line[0], line.drop(1))
                    index++
                }
                hunks += PatchHunk(oldStart, hunkLines)
            }
            require(oldPath != null || newPath != null) { "Invalid patch: both paths are /dev/null." }
            require(hunks.isNotEmpty()) { "Invalid patch: no hunks for ${newPath ?: oldPath}." }
            result += FilePatch(oldPath, newPath, hunks)
        }
        require(result.isNotEmpty()) { "Invalid patch: expected standard 'diff --git' unified diff format." }
        return result
    }

    fun apply(original: String, patch: FilePatch): String {
        val separator = if (original.contains("\r\n")) "\r\n" else if (original.contains('\r')) "\r" else "\n"
        val trailingNewline = original.endsWith("\n") || original.endsWith("\r")
        val source = if (original.isEmpty()) emptyList() else original.split("\r\n", "\n", "\r").let { if (trailingNewline) it.dropLast(1) else it }
        val output = mutableListOf<String>()
        var cursor = 0
        for (hunk in patch.hunks) {
            val expected = (hunk.oldStart - 1).coerceAtLeast(0)
            val target = locateHunk(source, hunk, cursor, expected)
            output += source.subList(cursor, target)
            cursor = target
            for (line in hunk.lines) when (line.kind) {
                ' ' -> { require(cursor < source.size && source[cursor] == line.text) { "Patch context did not match at line ${cursor + 1}." }; output += line.text; cursor++ }
                '-' -> { require(cursor < source.size && source[cursor] == line.text) { "Patch deletion did not match at line ${cursor + 1}." }; cursor++ }
                '+' -> output += line.text
            }
        }
        output += source.drop(cursor)
        val result = output.joinToString(separator)
        return if (trailingNewline || patch.oldPath == null) result + separator else result
    }

    /**
     * Git hunk line numbers are hints rather than an exact address. Locate the old
     * hunk body by context, preferring the candidate nearest to the advertised line.
     * Equally near candidates are rejected so a repeated block cannot be edited by
     * accident.
     */
    private fun locateHunk(source: List<String>, hunk: PatchHunk, cursor: Int, expected: Int): Int {
        val oldLines = hunk.lines.filter { it.kind != '+' }.map { it.text }
        if (oldLines.isEmpty()) {
            require(expected in cursor..source.size) { "Patch hunk starts outside the file." }
            return expected
        }

        val candidates = (cursor..source.size - oldLines.size).filter { start ->
            oldLines.indices.all { offset -> source[start + offset] == oldLines[offset] }
        }
        require(candidates.isNotEmpty()) {
            "Patch context did not match near line ${expected + 1}."
        }
        val bestDistance = candidates.minOf { kotlin.math.abs(it - expected) }
        val nearest = candidates.filter { kotlin.math.abs(it - expected) == bestDistance }
        require(nearest.size == 1) {
            "Patch context is ambiguous near line ${expected + 1}; matched equally well at ${nearest.joinToString { (it + 1).toString() }}."
        }
        return nearest.single()
    }

    private fun parsePath(value: String): String? {
        val raw = value.substringBefore('\t').trim()
        if (raw == "/dev/null") return null
        return raw.removePrefix("a/").removePrefix("b/")
    }
}
