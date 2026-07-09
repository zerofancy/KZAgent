package com.kzagent.kagent.cli

import com.kzagent.kagent.agent.CodingAgent
import com.kzagent.kagent.agent.PromptBuilder
import com.kzagent.kagent.agent.SessionWriter
import com.kzagent.kagent.config.AppConfigLoader
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.llm.DeepSeekClient
import com.kzagent.kagent.tools.LocalTools
import com.kzagent.kagent.tools.PathGuard
import com.kzagent.kagent.tools.TerminalApprovalPolicy
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty() || args[0] != "ask" || args.drop(1).joinToString(" ").isBlank()) {
        printUsage()
        return@runBlocking
    }

    val prompt = args.drop(1).joinToString(" ")
    val workspace = Path.of("").toAbsolutePath().normalize()

    try {
        val config = AppConfigLoader.load(workspace)
        val pathGuard = PathGuard(workspace)
        val tools = LocalTools(pathGuard, TerminalApprovalPolicy).registry()
        val agent = CodingAgent(
            model = DeepSeekClient(config),
            tools = tools,
            promptBuilder = PromptBuilder(pathGuard.root),
            sessionWriter = SessionWriter(pathGuard.root),
        )

        val answer = agent.run(prompt)
        println()
        println("Final answer:")
        println(answer)
    } catch (e: Exception) {
        System.err.println("Error: ${SecretRedactor.redact(e.message ?: e.toString())}")
        kotlin.system.exitProcess(1)
    }
}

private fun printUsage() {
    println(
        """
        Usage:
          ./gradlew run --args="ask \"列出当前项目文件\""

        Configuration:
          local.properties:
            deepseek.api.key=...
            deepseek.model=deepseek-v4-flash
            deepseek.base.url=https://api.deepseek.com

        The API key can also be provided with DEEPSEEK_API_KEY.
        """.trimIndent(),
    )
}

