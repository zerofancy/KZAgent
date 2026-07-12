package com.kzagent.kagent

import com.kzagent.kagent.agent.AgentObserver
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
        val pathGuard = PathGuard(root)
        val writer = if (sessionFile != null) {
            SessionWriter(sessionFile)
        } else {
            SessionWriter.createNew(sessionsDir)
        }
        val agent = CodingAgent(
            model = DeepSeekClient(config),
            tools = LocalTools(
                pathGuard = pathGuard,
                approvalPolicy = approvalPolicy,
                sensitivePathProtection = config.sensitivePathProtection,
            ).registry(),
            promptBuilder = PromptBuilder(pathGuard.root, config.userPrompt),
            sessionWriter = writer,
            observer = observer,
        )
        return AgentRuntime(
            workspace = pathGuard.root,
            agent = agent,
            sessionReader = SessionReader(sessionsDir),
            contextWindowSize = config.contextWindowSize,
        )
    }
}
