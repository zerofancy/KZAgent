package com.kzagent.kagent.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectRepositoryTest {
    private val createdAt = Instant.parse("2026-11-24T16:33:10Z")
    private fun project(workspace: Path, name: String = "项目 😀") =
        Project(UUID.randomUUID().toString(), name, workspace.toAbsolutePath().normalize(), createdAt)

    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("project-repository-test")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun missingRegistryIsEmptyAndDoesNotCreateFiles() = temporary { root -> runBlocking {
        val path = root.resolve("projects.json")
        assertEquals(ProjectRegistry(), FileProjectRepository(path).load())
        assertFalse(Files.exists(path))
    } }

    @Test
    fun sameNameSamePathEmptyProjectsRoundTripAndRenameKeepsIdentity() = temporary { root -> runBlocking {
        val path = root.resolve("projects.json")
        val repository = FileProjectRepository(path)
        val first = project(root.resolve("missing-workspace"))
        val second = project(first.workspace)
        val defaults = mapOf(projectWorkspaceKey(first.workspace) to first.id)
        val original = ProjectRegistry(listOf(first, second), defaults)
        repository.save(original)
        assertEquals(original, FileProjectRepository(path).load())
        val renamed = first.copy(name = "重命名")
        repository.save(original.copy(projects = listOf(renamed, second)))
        assertEquals(renamed, repository.load().projects.first())
        repository.save(ProjectRegistry(listOf(second)))
        assertEquals(ProjectRegistry(listOf(second)), repository.load())
        repository.save(ProjectRegistry())
        assertEquals(ProjectRegistry(), repository.load())
        assertFalse(Files.exists(first.workspace))
    } }

    @Test
    fun saveTrimsNamesAndNormalizesPaths() = temporary { root -> runBlocking {
        val repository = FileProjectRepository(root.resolve("projects.json"))
        val input = project(root).copy(name = "  合法 😀  ", workspace = Path.of("build", "..", "workspace"))
        repository.save(ProjectRegistry(listOf(input)))
        assertEquals(input.copy(name = "合法 😀", workspace = input.workspace.toAbsolutePath().normalize()),
            repository.load().projects.single())
    } }

    @Test
    fun invalidRegistryCannotReplaceValidData() = temporary { root -> runBlocking {
        val path = root.resolve("projects.json")
        val repository = FileProjectRepository(path)
        val valid = project(root)
        repository.save(ProjectRegistry(listOf(valid)))
        val before = Files.readString(path)
        val invalid = listOf(
            ProjectRegistry(listOf(valid, valid)),
            ProjectRegistry(listOf(valid, valid.copy(id = valid.id.uppercase(Locale.ROOT)))),
            ProjectRegistry(listOf(valid.copy(id = "1-1-1-1-1"))),
            ProjectRegistry(listOf(valid.copy(name = " \n "))),
            ProjectRegistry(listOf(valid.copy(name = "\u0000bad"))),
            ProjectRegistry(listOf(valid), mapOf(projectWorkspaceKey(root) to UUID.randomUUID().toString())),
            ProjectRegistry(listOf(valid), mapOf(projectWorkspaceKey(root.resolve("other")) to valid.id)),
            ProjectRegistry(listOf(valid), mapOf("relative" to valid.id)),
        )
        invalid.forEach {
            assertFailsWith<IllegalArgumentException> { repository.save(it) }
            assertEquals(before, Files.readString(path))
        }
    } }

    @Test
    fun damagedOrUnsupportedRegistryBlocksLoadAndSaveEvenOnNewRepository() = temporary { root -> runBlocking {
        val path = root.resolve("projects.json")
        val badDocuments = listOf(
            "not json", "{}", "{\"schemaVersion\":2,\"projects\":[]}",
            "{\"schemaVersion\":0,\"projects\":[]}",
            "{\"schemaVersion\":1,\"projects\":null}",
            """{"schemaVersion":1,"projects":[{"id":"bad","name":"x","workspace":"/tmp","createdAt":"2026-11-24T16:33:10Z"}]}""",
        )
        badDocuments.forEach { document ->
            Files.writeString(path, document)
            assertFailsWith<Exception> { FileProjectRepository(path).load() }
            assertFailsWith<Exception> { FileProjectRepository(path).save(ProjectRegistry()) }
            assertEquals(document, Files.readString(path))
        }
        // Repairing the file permits an explicit retry, without rebuilding repository state.
        Files.writeString(path, "{\"schemaVersion\":1,\"projects\":[]}")
        FileProjectRepository(path).save(ProjectRegistry())
        assertEquals(ProjectRegistry(), FileProjectRepository(path).load())
    } }

    @Test
    fun invalidStoredFieldsAreRejectedRatherThanNormalizedSilently() = temporary { root -> runBlocking {
        val path = root.resolve("projects.json")
        val repository = FileProjectRepository(path)
        val valid = project(root)
        repository.save(ProjectRegistry(listOf(valid)))
        val original = Files.readString(path)
        val documents = listOf(
            original.replace(valid.createdAt.toString(), "yesterday"),
            original.replace(valid.name, "  untrimmed  "),
            original.replace(valid.workspace.toString().replace("\\", "\\\\"), "relative"),
        )
        documents.forEach { document ->
            Files.writeString(path, document)
            assertFailsWith<Exception> { repository.load() }
            assertEquals(document, Files.readString(path))
        }
    } }

    @Test
    fun missingTargetRecoversValidatedBackupButCorruptTargetIsNotOverwritten() = temporary { root -> runBlocking {
        val path = root.resolve("projects.json")
        val backup = metadataBackupFile(path)
        val repository = FileProjectRepository(path)
        val expected = ProjectRegistry(listOf(project(root)))
        repository.save(expected)
        Files.move(path, backup)
        assertEquals(expected, repository.load())
        assertTrue(Files.exists(path))
        assertFalse(Files.exists(backup))
        Files.copy(path, backup)
        Files.writeString(path, "broken")
        assertFailsWith<Exception> { repository.load() }
        assertEquals("broken", Files.readString(path))
        assertTrue(Files.exists(backup))
    } }

    @Test
    fun invalidBackupIsPreservedAndPreventsSave() = temporary { root -> runBlocking {
        val path = root.resolve("projects.json")
        val backup = metadataBackupFile(path)
        Files.writeString(backup, "broken")
        val repository = FileProjectRepository(path)
        assertFailsWith<Exception> { repository.load() }
        assertFailsWith<Exception> { repository.save(ProjectRegistry()) }
        assertFalse(Files.exists(path))
        assertEquals("broken", Files.readString(backup))
    } }

    @Test
    fun writeFailureIsReportedAndDoesNotCreateWorkspace() = temporary { root -> runBlocking {
        val blocker = root.resolve("not-a-directory")
        Files.writeString(blocker, "keep")
        val workspace = root.resolve("workspace")
        assertFailsWith<Exception> {
            FileProjectRepository(blocker.resolve("projects.json")).save(ProjectRegistry(listOf(project(workspace))))
        }
        assertEquals("keep", Files.readString(blocker))
        assertFalse(Files.exists(workspace))
    } }

    @Test
    fun cancelledLoadAndSavePropagateCancellationWithoutWriting() = temporary { root -> runBlocking {
        val path = root.resolve("projects.json")
        val repository = FileProjectRepository(path, Dispatchers.Unconfined)
        val cancelled = Job().also { it.cancel() }
        assertFailsWith<CancellationException> { withContext(cancelled) { repository.load() } }
        assertFailsWith<CancellationException> { withContext(cancelled) { repository.save(ProjectRegistry()) } }
        assertFalse(Files.exists(path))
    } }

    @Test
    fun nameValidationAndDefaultNames() {
        assertEquals("项目 😀", validateProjectName("  项目 😀  "))
        listOf("", "  ", "\nx", "x\r", "a\tb", "x\u0000", "x\u007f", "x\u0085", "x\u2028", "x\u2029")
            .forEach { assertFailsWith<IllegalArgumentException> { validateProjectName(it) } }
        val root = Path.of("").toAbsolutePath().root
        assertEquals(root.toString(), projectDefaultName(root))
        assertEquals("child", projectDefaultName(root.resolve("parent").resolve("child")))
    }

    @Test
    fun workspaceIdentityUsesAbsoluteNormalizationAndPlatformCaseRules() {
        val path = Path.of("build", "..", "CaseSensitive-İ")
        val expected = path.toAbsolutePath().normalize().toString()
        val windows = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")
        assertEquals(if (windows) expected.lowercase(Locale.ROOT) else expected, projectWorkspaceKey(path))
    }
}
