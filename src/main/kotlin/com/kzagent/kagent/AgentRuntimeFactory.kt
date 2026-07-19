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
import com.kzagent.kagent.llm.DeepSeekClient
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.LocalTools
import com.kzagent.kagent.tools.ModeApprovalPolicy
import com.kzagent.kagent.tools.ModelApprovalAgent
import com.kzagent.kagent.tools.PathGuard
import java.nio.file.Path

data class AgentRuntime(
    val workspace: Path,
    val agent: CodingAgent,
    val sessionReader: SessionReader,
    val contextWindowSize: Int,
)

object AgentRuntimeFactory {
    fun create(
        workspace: Path,
        approvalPolicy: ApprovalPolicy,
        observer: AgentObserver = NoOpAgentObserver,
        sessionFile: Path? = null,
    ): AgentRuntime {
        val root = workspace.toAbsolutePath().normalize()
        val sessionsDir = AppDataDir.ensureSessionsDir(root)
        val config = AppConfigLoader.load()
        val model = DeepSeekClient(config)
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
        val agent = CodingAgent(
            model = model,
            tools = LocalTools(
                pathGuard = pathGuard,
                approvalPolicy = effectiveApprovalPolicy,
                sensitivePathProtection = config.sensitivePathProtection,
            ).registry(),
            promptBuilder = PromptBuilder(
                workspace = pathGuard.root,
                userPrompt = config.userPrompt,
                rootInstructions = rootInstructions,
            ),
            sessionWriter = writer,
            observer = observer,
            instructionsLoader = instructionsLoader,
        )
        return AgentRuntime(
            workspace = pathGuard.root,
            agent = agent,
            sessionReader = SessionReader(sessionsDir),
            contextWindowSize = config.contextWindowSize,
        )
    }
}
