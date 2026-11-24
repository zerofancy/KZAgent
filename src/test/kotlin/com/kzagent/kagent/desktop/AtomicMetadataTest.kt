package com.kzagent.kagent.desktop

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicMetadataTest {
    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("atomic-metadata-test")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun createsParentsAndReplacesCompleteUtf8ContentWithoutTemporaryFiles() = temporary { root ->
        val target = root.resolve("nested").resolve("metadata.json")
        atomicWriteMetadata(target, "原始 😀")
        assertEquals("原始 😀", Files.readString(target))
        atomicWriteMetadata(target, "新")
        assertEquals("新", Files.readString(target))
        Files.list(target.parent).use { assertEquals(listOf(target), it.toList()) }
    }

    @Test
    fun unsupportedAtomicMoveUsesBackupBeforeReplacingTarget() = temporary { root ->
        val target = root.resolve("metadata.json")
        Files.writeString(target, "old")
        var fallbackUsed = false
        writeMetadataAtomically(target, "new") { source, destination, atomic ->
            if (atomic) throw AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test")
            fallbackUsed = true
            assertEquals("old", Files.readString(metadataBackupFile(target)))
            assertEquals("old", Files.readString(target))
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(fallbackUsed)
        assertEquals("new", Files.readString(target))
        assertFalse(Files.exists(metadataBackupFile(target)))
        Files.list(root).use { assertEquals(listOf(target), it.toList()) }
    }

    @Test
    fun interruptedFallbackRestoresOldCopyAndKeepsRecoveryBackup() = temporary { root ->
        val target = root.resolve("metadata.json")
        Files.writeString(target, "old")
        assertFailsWith<IOException> {
            writeMetadataAtomically(target, "new") { source, destination, atomic ->
                if (atomic) throw AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test")
                // Simulate a provider that destroyed its destination before reporting failure.
                Files.writeString(destination, "partial")
                throw IOException("injected replacement failure")
            }
        }
        assertEquals("old", Files.readString(target))
        assertEquals("old", Files.readString(metadataBackupFile(target)))
        Files.list(root).use { assertEquals(2L, it.count()) }
    }

    @Test
    fun ordinaryMoveFailureDoesNotAttemptFallbackOrTouchOriginal() = temporary { root ->
        val target = root.resolve("metadata.json")
        Files.writeString(target, "old")
        assertFailsWith<IOException> {
            writeMetadataAtomically(target, "new") { _, _, atomic ->
                assertTrue(atomic)
                throw IOException("injected atomic move failure")
            }
        }
        assertEquals("old", Files.readString(target))
        Files.list(root).use { assertEquals(listOf(target), it.toList()) }
    }

    @Test
    fun fallbackCanCreateFirstFileWithoutBackup() = temporary { root ->
        val target = root.resolve("metadata.json")
        writeMetadataAtomically(target, "new") { source, destination, atomic ->
            if (atomic) throw AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test")
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        assertEquals("new", Files.readString(target))
        assertFalse(Files.exists(metadataBackupFile(target)))
    }

    @Test
    fun committedWriteDoesNotReportFailureWhenStaleBackupCannotBeDeleted() = temporary { root ->
        val target = root.resolve("metadata.json")
        Files.writeString(target, "old")
        val backup = metadataBackupFile(target)
        Files.createDirectories(backup.resolve("nonempty"))
        atomicWriteMetadata(target, "new")
        assertEquals("new", Files.readString(target))
        assertTrue(Files.isDirectory(backup))
    }

    @Test
    fun temporaryCleanupFailureIsSuppressedOnOriginalWriteError() = temporary { root ->
        val target = root.resolve("metadata.json")
        Files.writeString(target, "old")
        val injected = IOException("original failure")
        val actual = assertFailsWith<IOException> {
            writeMetadataAtomically(target, "new") { source, _, _ ->
                Files.delete(source)
                Files.createDirectories(source.resolve("nonempty"))
                throw injected
            }
        }
        assertTrue(actual === injected)
        assertEquals(1, actual.suppressed.size)
        assertTrue(actual.suppressed.single() is java.nio.file.DirectoryNotEmptyException)
        assertEquals("old", Files.readString(target))
    }

    @Test
    fun completedBackupRecoveryDoesNotFailIfBackupCleanupIsUnavailable() = temporary { root ->
        val target = root.resolve("metadata.json")
        val backup = metadataBackupFile(target)
        Files.writeString(backup, "valid")
        val restored = readMetadataRecovering(target) { content ->
            // Deterministically make cleanup fail after the complete recovery content was read.
            Files.delete(backup)
            Files.createDirectories(backup.resolve("nonempty"))
            content
        }
        assertEquals("valid", restored)
        assertEquals("valid", Files.readString(target))
        assertTrue(Files.isDirectory(backup))
    }
}
