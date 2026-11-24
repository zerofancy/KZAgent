package com.kzagent.kagent.desktop

import com.kzagent.kagent.AgentRuntime
import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.agent.CodingAgent
import com.kzagent.kagent.agent.NoOpAgentObserver
import com.kzagent.kagent.agent.PromptBuilder
import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.agent.SessionWriter
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import com.kzagent.kagent.todo.TodoSnapshot
import com.kzagent.kagent.tools.AlwaysApprovePolicy
import com.kzagent.kagent.tools.ToolRegistry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectSessionManagerTest {
    private class Fixture(val root: Path) {
        val workspace: Path = Files.createDirectory(root.resolve("workspace"))
        val sessionsRoot: Path = root.resolve("sessions")
        val registryFile: Path = root.resolve("projects.json")
        val projectExpandFile: Path = root.resolve("session-project-collapses.json")
        val legacyExpandFile: Path = root.resolve("session-group-collapses.json")
        val sessionRepository = FileSessionRepository(sessionsRoot)
        val projectRepository = FileProjectRepository(registryFile)
        fun manager(
            repository: SessionRepository = sessionRepository,
            projects: ProjectRepository = projectRepository,
            createRuntime: ((SessionData, AgentObserver) -> AgentRuntime)? = null,
        ) = SessionManager(
            approvalPolicy = AlwaysApprovePolicy,
            sessionsRoot = sessionsRoot,
            repository = repository,
            projectRepository = projects,
            projectExpandFile = projectExpandFile,
            legacyExpandFile = legacyExpandFile,
            createRuntime = createRuntime,
        )
    }

    private suspend fun temporary(block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("project-session-manager-test")
        try { block(Fixture(root)) } finally { root.toFile().deleteRecursively() }
    }

    private fun sidecar(file: Path): Path = file.resolveSibling("${file.fileName}.project")

    private fun runtime(session: SessionData, close: () -> Unit): AgentRuntime {
        val model = object : ChatModel {
            override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply =
                error("No network expected")
        }
        return AgentRuntime(
            session.workspace,
            CodingAgent(model, ToolRegistry(emptyList()), PromptBuilder(session.workspace), SessionWriter(session.sessionFile)),
            SessionReader(session.sessionFile.parent), 100_000, session.modelSelection,
            MutableStateFlow(TodoSnapshot()), closeAction = close,
        )
    }

    @Test
    fun sameNameSameWorkspaceProjectsAndTheirSidecarsRemainIndependentAfterReload() = runBlocking { temporary { f ->
        val expected = mutableMapOf<String, String>()
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            val first = manager.createProject("同名", f.workspace)
            val second = manager.createProject("同名", f.workspace)
            assertNotEquals(first.id, second.id)
            for (project in listOf(first, second)) {
                val session = manager.startNewSessionInProject(project.id)
                expected[session.id] = project.id
                assertEquals(project.id, Files.readString(sidecar(session.sessionFile)))
            }
            manager.setProjectExpanded(first.id, false)
            manager.setProjectExpanded(second.id, true)
        }
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            assertEquals(2, manager.projects.size)
            assertEquals(expected, manager.sessions.associate { it.id to requireNotNull(it.projectId) })
            assertEquals(setOf(false, true), manager.projectExpanded.values.toSet())
            assertTrue(manager.loadIssues.isEmpty())
        }
    } }

    @Test
    fun emptyProjectPersistsAndDeletionCleansDefaultMappingAndExpandStateOnly() = runBlocking { temporary { f ->
        val project = Project(UUID.randomUUID().toString(), "空项目", f.workspace, Instant.now())
        f.projectRepository.save(ProjectRegistry(listOf(project), mapOf(projectWorkspaceKey(f.workspace) to project.id)))
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            assertEquals(listOf(project), manager.projects.toList())
            assertTrue(manager.sessions.isEmpty())
            manager.setProjectExpanded(project.id, false)
            manager.deleteProject(project.id)
            assertTrue(manager.projects.isEmpty())
            assertFalse(project.id in manager.projectExpanded)
        }
        assertEquals(ProjectRegistry(), f.projectRepository.load())
        assertFalse(project.id in loadProjectExpandState(f.projectExpandFile))
        assertTrue(Files.isDirectory(f.workspace))
        Files.list(f.sessionsRoot).use { assertEquals(0L, it.count()) }
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            val created = manager.createProject("新空项目", f.workspace)
            assertTrue(manager.sessions.isEmpty())
            assertEquals(created, f.projectRepository.load().projects.single())
        }
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            assertEquals("新空项目", manager.projects.single().name)
            assertTrue(manager.sessions.isEmpty())
        }
    } }

    @Test
    fun renameKeepsRuntimeSessionWorkspaceAndProjectIdentityEvenWhileBusy() = runBlocking { temporary { f ->
        var created = 0
        var closed = 0
        f.manager(createRuntime = { session, _ -> created++; runtime(session) { closed++ } }).use { manager ->
            manager.loadSessions(f.workspace)
            val project = manager.createProject("旧名", f.workspace)
            val session = manager.startNewSessionInProject(project.id)
            manager.ensureRuntime(session, NoOpAgentObserver)
            val originalRuntime = session.runtime
            session.isBusy = true
            manager.renameProject(project.id, "  新名  ")
            assertEquals(project.copy(name = "新名"), manager.projects.single())
            assertSame(session, manager.sessions.single())
            assertSame(originalRuntime, session.runtime)
            assertEquals(project.id, session.projectId)
            assertEquals(f.workspace, session.workspace)
            assertEquals(1, created)
            assertEquals(0, closed)
            assertTrue(session.isBusy)
        }
        assertEquals(1, closed)
    } }

    @Test
    fun historicalSubdirectoryAndCliSessionsMigrateIdempotentlyWithoutMovingHistory() = runBlocking { temporary { f ->
        val directory = Files.createDirectories(f.sessionsRoot.resolve(AppDataDir.workspaceKey(f.workspace)))
        val historical = directory.resolve("session-historical.jsonl")
        val message = AgentMessage.User("保留历史")
        SessionWriter(historical).append(message)
        val originalBytes = Files.readAllBytes(historical)
        val cli = f.sessionRepository.create(f.workspace, "CLI历史")
        var defaultId = ""
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            defaultId = manager.projects.single().id
            assertEquals(2, manager.sessions.size)
            assertTrue(manager.sessions.all { it.projectId == defaultId })
            assertEquals(listOf(message), manager.sessions.single { it.sessionFile == historical }.conversationHistory)
            assertTrue(originalBytes.contentEquals(Files.readAllBytes(historical)))
            assertEquals(defaultId, Files.readString(sidecar(historical)))
            assertEquals(defaultId, Files.readString(sidecar(cli.sessionFile)))
            manager.createProject("稍后创建的同路径项目", f.workspace)
        }
        val nextCli = f.sessionRepository.create(f.workspace, "后续CLI")
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            assertEquals(2, manager.projects.size)
            assertEquals(3, manager.sessions.size)
            assertTrue(manager.sessions.all { it.projectId == defaultId })
            assertEquals(defaultId, Files.readString(sidecar(nextCli.sessionFile)))
            manager.retryLoadIssues()
            assertEquals(2, manager.projects.size)
            assertEquals(3, manager.sessions.size)
            assertEquals(defaultId, f.projectRepository.load().legacyDefaults[projectWorkspaceKey(f.workspace)])
        }
    } }

    @Test
    fun invalidExplicitReferencesAreQuarantinedAndNeverReassignedByWorkspace() = runBlocking { temporary { f ->
        val damaged = listOf("", "not-a-uuid", UUID.randomUUID().toString()).map { reference ->
            f.sessionRepository.create(f.workspace, "损坏归属").also { Files.writeString(sidecar(it.sessionFile), reference) }
        }
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            assertTrue(manager.sessions.isEmpty())
            assertTrue(manager.projects.isEmpty())
            assertEquals(3, manager.loadIssues.size)
            manager.retryLoadIssues()
            assertEquals(3, manager.loadIssues.size)
            assertTrue(manager.projects.isEmpty())
        }
        assertEquals(listOf("", "not-a-uuid"), damaged.take(2).map { Files.readString(sidecar(it.sessionFile)) })
        assertEquals(ProjectRegistry(), f.projectRepository.load())
    } }

    @Test
    fun mismatchedWorkspaceReferenceIsQuarantinedAndStillPreventsProjectDeletion() = runBlocking { temporary { f ->
        val other = Files.createDirectory(f.root.resolve("other"))
        val project = Project(UUID.randomUUID().toString(), "项目", f.workspace, Instant.now())
        f.projectRepository.save(ProjectRegistry(listOf(project)))
        val session = f.sessionRepository.createInProject(other, "不一致", null, project.id)
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            assertTrue(manager.sessions.isEmpty())
            assertEquals(1, manager.loadIssues.size)
            assertTrue(manager.loadIssues.single().contains("不一致"))
            assertFailsWith<IllegalStateException> { manager.deleteProject(project.id) }
            assertEquals(project.id, Files.readString(sidecar(session.sessionFile)))
            assertEquals(listOf(project), manager.projects.toList())
        }
    } }

    @Test
    fun failedSidecarMigrationRetriesUsingAlreadyPersistedDefaultProject() = runBlocking { temporary { f ->
        val original = f.sessionRepository.create(f.workspace, "待迁移")
        var fail = true
        val repository = object : SessionRepository by f.sessionRepository {
            override suspend fun updateProject(sessionFile: Path, projectId: String) {
                if (fail) throw IOException("injected project sidecar failure")
                f.sessionRepository.updateProject(sessionFile, projectId)
            }
        }
        f.manager(repository).use { manager ->
            manager.loadSessions(f.workspace)
            assertTrue(manager.sessions.isEmpty())
            assertEquals(1, manager.loadIssues.size)
            val saved = f.projectRepository.load()
            assertEquals(1, saved.projects.size)
            assertFalse(Files.exists(sidecar(original.sessionFile)))
            fail = false
            manager.retryLoadIssues()
            assertTrue(manager.loadIssues.isEmpty())
            assertEquals(saved, f.projectRepository.load())
            assertEquals(saved.projects.single().id, manager.sessions.single().projectId)
            assertEquals(saved.projects.single().id, Files.readString(sidecar(original.sessionFile)))
            val preserved = manager.sessions.single()
            manager.retryLoadIssues()
            assertSame(preserved, manager.sessions.single())
            assertEquals(saved, f.projectRepository.load())
        }
    } }

    @Test
    fun registryMigrationFailureWritesNoSidecarsAndCanBeRetried() = runBlocking { temporary { f ->
        val original = f.sessionRepository.create(f.workspace, "待迁移")
        var fail = true
        val projects = object : ProjectRepository by f.projectRepository {
            override suspend fun save(registry: ProjectRegistry) {
                if (fail) throw IOException("injected registry failure")
                f.projectRepository.save(registry)
            }
        }
        f.manager(projects = projects).use { manager ->
            manager.loadSessions(f.workspace)
            assertEquals(1, manager.loadIssues.size)
            assertTrue(manager.projects.isEmpty())
            assertFalse(Files.exists(sidecar(original.sessionFile)))
            fail = false
            manager.retryLoadIssues()
            assertTrue(manager.loadIssues.isEmpty())
            assertEquals(1, manager.projects.size)
            assertEquals(manager.projects.single().id, manager.sessions.single().projectId)
        }
    } }

    @Test
    fun damagedRegistryBlocksInitializationAndCreationWithoutOverwritingDisk() = runBlocking { temporary { f ->
        val damaged = "{damaged registry}"
        Files.writeString(f.registryFile, damaged)
        f.manager().use { manager ->
            assertFailsWith<Exception> { manager.loadSessions(f.workspace) }
            assertFalse(manager.initialized)
            assertFailsWith<IllegalStateException> { manager.createProject("不可写", f.workspace) }
            assertTrue(manager.projects.isEmpty())
            assertTrue(manager.sessions.isEmpty())
            assertEquals(damaged, Files.readString(f.registryFile))
        }
    } }

    @Test
    fun unavailableWorkspaceKeepsProjectAndHistoryButRejectsNewSessionsAndRuntime() = runBlocking { temporary { f ->
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            val project = manager.createProject("原项目", f.workspace)
            val session = manager.startNewSessionInProject(project.id)
            Files.delete(f.workspace)
            assertFailsWith<IllegalArgumentException> { manager.startNewSessionInProject(project.id) }
            assertFailsWith<IllegalArgumentException> { manager.ensureRuntime(session, NoOpAgentObserver) }
            assertEquals(1, manager.sessions.size)
            assertEquals(listOf(project), manager.projects.toList())
            assertFailsWith<IllegalArgumentException> { manager.createProject("失败", f.workspace) }
        }
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            assertEquals(1, manager.projects.size)
            assertEquals(1, manager.sessions.size)
            assertTrue(manager.loadIssues.isEmpty())
        }
    } }

    @Test
    fun createAndDeleteAreSerializedSoProjectReferencesCannotBecomeDangling() = runBlocking { temporary { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : SessionRepository by f.sessionRepository {
            override suspend fun createInProject(workspace: Path, name: String, modelSelection: ModelSelection?, projectId: String): StoredSession {
                entered.complete(Unit)
                release.await()
                return f.sessionRepository.createInProject(workspace, name, modelSelection, projectId)
            }
        }
        f.manager(repository).use { manager ->
            manager.loadSessions(f.workspace)
            val project = manager.createProject("竞态项目", f.workspace)
            val creation = async { manager.startNewSessionInProject(project.id) }
            entered.await()
            val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<IllegalStateException> { manager.deleteProject(project.id) }
            }
            assertFalse(deletion.isCompleted)
            release.complete(Unit)
            val session = creation.await()
            deletion.await()
            assertEquals(project.id, session.projectId)
            assertEquals(project.id, f.projectRepository.load().projects.single().id)
            assertEquals(project.id, Files.readString(sidecar(session.sessionFile)))
            manager.deleteSession(0)
            manager.deleteProject(project.id)
            assertFailsWith<IllegalArgumentException> { manager.startNewSessionInProject(project.id) }
            assertTrue(manager.projects.isEmpty())
            assertTrue(manager.sessions.isEmpty())
        }
    } }

    @Test
    fun legacyExpandStateImportsOnlyOnceAndDoesNotAffectNewSamePathProjects() = runBlocking { temporary { f ->
        f.sessionRepository.create(f.workspace, "历史")
        saveSessionWorkspaceExpandState(mapOf(sessionWorkspaceKey(f.workspace) to false), f.legacyExpandFile)
        var defaultId = ""
        var manualId = ""
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            defaultId = manager.projects.single().id
            assertEquals(false, manager.projectExpanded[defaultId])
            manager.setProjectExpanded(defaultId, true)
            manualId = manager.createProject("同路径新项目", f.workspace).id
            assertFalse(manualId in manager.projectExpanded)
        }
        f.manager().use { manager ->
            manager.loadSessions(f.workspace)
            assertEquals(true, manager.projectExpanded[defaultId])
            assertFalse(manualId in manager.projectExpanded)
            assertTrue(Files.exists(f.legacyExpandFile))
        }
    } }

    @Test
    fun deletingSessionWhileCreatingAnotherNeverRemovesTheNewSessionByStaleIndex() = runBlocking { temporary { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = object : SessionRepository by f.sessionRepository {
            override suspend fun delete(sessionFile: Path) {
                entered.complete(Unit)
                release.await()
                f.sessionRepository.delete(sessionFile)
            }
        }
        f.manager(repository).use { manager ->
            manager.loadSessions(f.workspace)
            val project = manager.createProject("项目", f.workspace)
            val old = manager.startNewSessionInProject(project.id)
            val deletion = async { manager.deleteSession(0) }
            entered.await()
            val creation = async(start = CoroutineStart.UNDISPATCHED) { manager.startNewSessionInProject(project.id) }
            assertFalse(creation.isCompleted)
            release.complete(Unit)
            assertTrue(deletion.await())
            val fresh = creation.await()
            assertSame(fresh, manager.sessions.single())
            assertSame(fresh, manager.activeSession())
            assertFalse(Files.exists(old.sessionFile))
            assertTrue(Files.exists(fresh.sessionFile))
            assertEquals(project.id, Files.readString(sidecar(fresh.sessionFile)))
        }
    } }

    @Test
    fun knownProjectWorkspaceRecoversHashHistoryWhenStartupDirectoryIsDifferent() = runBlocking { temporary { f ->
        val project = Project(UUID.randomUUID().toString(), "既有项目", f.workspace, Instant.now())
        f.projectRepository.save(ProjectRegistry(listOf(project), mapOf(projectWorkspaceKey(f.workspace) to project.id)))
        val directory = Files.createDirectories(f.sessionsRoot.resolve(AppDataDir.workspaceKey(f.workspace)))
        val historical = directory.resolve("session-cli.jsonl")
        val message = AgentMessage.User("非启动目录CLI历史")
        SessionWriter(historical).append(message)
        val startup = Files.createDirectory(f.root.resolve("startup"))
        f.manager().use { manager ->
            manager.loadSessions(startup)
            assertTrue(manager.loadIssues.isEmpty())
            assertEquals(listOf(project), manager.projects.toList())
            val session = manager.sessions.single()
            assertEquals(f.workspace, session.workspace)
            assertEquals(project.id, session.projectId)
            assertEquals(listOf(message), session.conversationHistory)
            assertEquals(project.id, Files.readString(sidecar(historical)))
        }
    } }

    @Test
    fun repositoryScanFailureLeavesAllProjectOperationsUnavailableUntilSuccessfulRetry() = runBlocking { temporary { f ->
        val project = Project(UUID.randomUUID().toString(), "既有项目", f.workspace, Instant.now())
        f.projectRepository.save(ProjectRegistry(listOf(project)))
        var fail = true
        val repository = object : SessionRepository by f.sessionRepository {
            override suspend fun loadAll(defaultWorkspace: Path, knownWorkspaces: List<Path>): List<StoredSession> {
                if (fail) throw IOException("injected scan failure")
                return f.sessionRepository.loadAll(defaultWorkspace, knownWorkspaces)
            }
        }
        f.manager(repository).use { manager ->
            assertFailsWith<IOException> { manager.loadSessions(f.workspace) }
            assertFalse(manager.initialized)
            assertTrue(manager.projects.isEmpty())
            assertFailsWith<IllegalStateException> { manager.createProject("禁止创建", f.workspace) }
            assertFailsWith<IllegalStateException> { manager.renameProject(project.id, "禁止重命名") }
            assertFailsWith<IllegalStateException> { manager.deleteProject(project.id) }
            assertEquals(listOf(project), f.projectRepository.load().projects)
            fail = false
            manager.retryLoadIssues()
            assertTrue(manager.initialized)
            assertEquals(listOf(project), manager.projects.toList())
            assertEquals(project.id, manager.startNewSessionInProject(project.id).projectId)
        }
    } }
}
