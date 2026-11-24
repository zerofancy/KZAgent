package com.kzagent.kagent.desktop

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Never truncate the only valid copy, including on filesystems without atomic rename. */
internal fun atomicWriteMetadata(path: Path, content: String) = writeMetadataAtomically(path, content) { source, target, atomic ->
    if (atomic) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } else {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

// The move operation is injectable so fallback and interrupted replacement are deterministic in tests.
internal fun writeMetadataAtomically(path: Path, content: String, move: (Path, Path, Boolean) -> Unit) {
    val target = path.toAbsolutePath().normalize()
    val parent = requireNotNull(target.parent) { "Metadata must have a parent directory" }
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
    var failure: Throwable? = null
    try {
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(content.toByteArray(StandardCharsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try {
            move(temporary, target, true)
        } catch (_: AtomicMoveNotSupportedException) {
            val backup = metadataBackupFile(target)
            val hadTarget = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            if (hadTarget) {
                // Copy first: an interrupted replacement always leaves a complete old copy.
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
                FileChannel.open(backup, StandardOpenOption.WRITE).use { it.force(true) }
            }
            try {
                move(temporary, target, false)
            } catch (failure: Exception) {
                if (hadTarget) {
                    try {
                        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING)
                    } catch (restoreFailure: Exception) {
                        failure.addSuppressed(restoreFailure)
                    }
                }
                throw failure
            }
        }
        // Replacement is already committed. A stale backup must not turn success into failure.
        cleanupMetadataFile(metadataBackupFile(target))
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        cleanupMetadataFile(temporary, failure)
    }
}

private fun cleanupMetadataFile(path: Path, failure: Throwable? = null) {
    try {
        Files.deleteIfExists(path)
    } catch (cleanupFailure: java.io.IOException) {
        failure?.addSuppressed(cleanupFailure)
    } catch (cleanupFailure: SecurityException) {
        failure?.addSuppressed(cleanupFailure)
    }
}

internal fun metadataBackupFile(path: Path): Path = path.resolveSibling("${path.fileName}.bak")

/** A present but invalid target is an error, not permission to roll back newer data. */
internal fun <T> readMetadataRecovering(path: Path, decode: (String) -> T): T? {
    // notExists distinguishes an absent file from an indeterminate/inaccessible one.
    if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
        return decode(Files.readString(path, StandardCharsets.UTF_8))
    }
    val backup = metadataBackupFile(path)
    if (Files.notExists(backup, LinkOption.NOFOLLOW_LINKS)) return null
    val content = Files.readString(backup, StandardCharsets.UTF_8)
    val restored = decode(content) // Validate before touching either recovery copy.
    atomicWriteMetadata(path, content)
    cleanupMetadataFile(backup)
    return restored
}
