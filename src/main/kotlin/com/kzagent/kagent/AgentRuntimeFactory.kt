package com.kzagent.kagent

import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.agent.AgentsInstructionsLoader
import com.kzagent.kagent.agent.CodingAgent
import com.kzagent.kagent.agent.NoOpAgentObserver
import com.kzagent.kagent.agent.PromptBuilder
import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.agent.SessionWriter
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.config.AppConfigLoader
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.llm.OpenAiCompatibleClient
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.AskUserTools
import com.kzagent.kagent.tools.LocalTools
import com.kzagent.kagent.tools.ModeApprovalPolicy
import com.kzagent.kagent.tools.ModelApprovalAgent
import com.kzagent.kagent.tools.PathGuard
import com.kzagent.kagent.tools.TodoTools
import com.kzagent.kagent.tools.WebContentExtractor
import com.kzagent.kagent.tools.WebPageService
import com.kzagent.kagent.tools.UserQuestionPrompter
import com.kzagent.kagent.todo.TodoFiles
import com.kzagent.kagent.todo.TodoSnapshot
import com.kzagent.kagent.todo.TodoStore
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow

data class AgentRuntime(
    val workspace: Path,
    val agent: CodingAgent,
    val sessionReader: SessionReader,
    val contextWindowSize: Int,
    val modelSelection: ModelSelection,
    val todoState: StateFlow<TodoSnapshot>,
)

object AgentRuntimeFactory {
    fun create(
        workspace: Path,
        approvalPolicy: ApprovalPolicy,
        userQuestionPrompter: UserQuestionPrompter,
        observer: AgentObserver = NoOpAgentObserver,
        sessionFile: Path? = null,
        modelSelection: ModelSelection? = null,
    ): AgentRuntime {
        val root = workspace.toAbsolutePath().normalize()
        val sessionsDir = AppDataDir.ensureSessionsDir(root)
        val config = AppConfigLoader.load()
        val selection = modelSelection ?: config.defaultModel
        val providerConfig = requireNotNull(config.provider(selection.provider)) {
            "${selection.provider.displayName} is not configured."
        }
        val model = OpenAiCompatibleClient(selection.provider, providerConfig, selection)
        val effectiveApprovalPolicy = ModeApprovalPolicy(
            mode = config.approvalMode,
            humanPolicy = approvalPolicy,
            approvalAgent = ModelApprovalAgent(model),
        )
        val pathGuard = PathGuard(root)
        val instructionsLoader = AgentsInstructionsLoader(pathGuard.root)
        // Root guidance is a session-level snapshot. Rebuilding the runtime is
        // intentionally required before edits to the root AGENTS.md take effect.
        val rootInstructions = instructionsLoader.loadRoot()?.content.orEmpty()
        val writer = if (sessionFile != null) {
            SessionWriter(sessionFile)
        } else {
            SessionWriter.createNew(sessionsDir)
        }
        val todoStore = TodoStore(TodoFiles.forSession(writer.sessionPath))
        val localTools = LocalTools(
            pathGuard = pathGuard,
            approvalPolicy = effectiveApprovalPolicy,
            sensitivePathProtection = config.sensitivePathProtection,
            webPageService = WebPageService(WebContentExtractor(model)),
        ).registry()
        val agent = CodingAgent(
            model = model,
            tools = localTools + TodoTools(todoStore).registry() + AskUserTools(userQuestionPrompter).registry(),
            promptBuilder = PromptBuilder(
                workspace = pathGuard.root,
                userPrompt = config.userPrompt,
                rootInstructions = rootInstructions,
            ),
            sessionWriter = writer,
            observer = observer,
            instructionsLoader = instructionsLoader,
            contextWindowSize = selection.contextWindowSize ?: config.contextWindowSize,
            todoStore = todoStore,
        )
        return AgentRuntime(
            workspace = pathGuard.root,
            agent = agent,
            sessionReader = SessionReader(sessionsDir),
            contextWindowSize = selection.contextWindowSize ?: config.contextWindowSize,
            modelSelection = selection,
            todoState = todoStore.state,
        )
    }
}
