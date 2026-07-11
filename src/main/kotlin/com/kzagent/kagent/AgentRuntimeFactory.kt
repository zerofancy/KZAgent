package com.kzagent.kagent

import com.kzagent.kagent.agent.AgentObserver
import com.kzagent.kagent.agent.CodingAgent
import com.kzagent.kagent.agent.NoOpAgentObserver
import com.kzagent.kagent.agent.PromptBuilder
import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.agent.SessionWriter
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
) {
    fun resetSessionTokens() {
        agent.resetSessionTokens()
    }
}

object AgentRuntimeFactory {
    fun create(
        workspace: Path,
        approvalPolicy: ApprovalPolicy,
        observer: AgentObserver = NoOpAgentObserver,
    ): AgentRuntime {
        val root = workspace.toAbsolutePath().normalize()
        val config = AppConfigLoader.load()
        val pathGuard = PathGuard(root)
        val agent = CodingAgent(
            model = DeepSeekClient(config),
            tools = LocalTools(
                pathGuard = pathGuard,
                approvalPolicy = approvalPolicy,
                sensitivePathProtection = config.sensitivePathProtection,
            ).registry(),
            promptBuilder = PromptBuilder(pathGuard.root),
            sessionWriter = SessionWriter(pathGuard.root),
            observer = observer,
        )
        return AgentRuntime(
            workspace = pathGuard.root,
            agent = agent,
            sessionReader = SessionReader(pathGuard.root),
            contextWindowSize = config.contextWindowSize,
        )
    }
}
