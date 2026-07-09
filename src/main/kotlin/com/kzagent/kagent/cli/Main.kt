package com.kzagent.kagent.cli

import com.kzagent.kagent.agent.CodingAgent
import com.kzagent.kagent.agent.PromptBuilder
import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.agent.SessionWriter
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.config.AppConfigLoader
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.llm.DeepSeekClient
import com.kzagent.kagent.tools.LocalTools
import com.kzagent.kagent.tools.PathGuard
import com.kzagent.kagent.tools.TerminalApprovalPolicy
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) {
        printUsage()
        return@runBlocking
    }

    val command = args[0]
    val workspace = Path.of("").toAbsolutePath().normalize()

    try {
        val config = AppConfigLoader.load(workspace)
        val pathGuard = PathGuard(workspace)
        val tools = LocalTools(pathGuard, TerminalApprovalPolicy).registry()
        val sessionWriter = SessionWriter(pathGuard.root)
        val agent = CodingAgent(
            model = DeepSeekClient(config),
            tools = tools,
            promptBuilder = PromptBuilder(pathGuard.root),
            sessionWriter = sessionWriter,
        )

        when (command) {
            "ask" -> {
                val prompt = args.drop(1).joinToString(" ")
                if (prompt.isBlank()) {
                    printUsage()
                    return@runBlocking
                }
                val answer = agent.run(prompt)
                println()
                println("Final answer:")
                println(answer)
            }
            "chat" -> {
                val initialPrompt = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                interactiveChat(workspace, agent, initialPrompt)
            }
            else -> printUsage()
        }
    } catch (e: Exception) {
        System.err.println("Error: ${SecretRedactor.redact(e.message ?: e.toString())}")
        kotlin.system.exitProcess(1)
    }
}

/**
 * Interactive multi-turn chat loop.
 *
 * - If [initialPrompt] is provided, starts with that question.
 * - Otherwise tries to load the latest session history so the user can continue where they left off.
 * - After each answer, prompts the user for the next question.
 * - Type empty input or "exit" / "quit" to finish.
 */
private suspend fun interactiveChat(
    workspace: java.nio.file.Path,
    agent: CodingAgent,
    initialPrompt: String?,
) {
    val reader = SessionReader(workspace)
    val resumedHistory: List<AgentMessage> = if (initialPrompt == null) {
        reader.loadLatestHistory().orEmpty()
    } else {
        emptyList()
    }
    var history: List<AgentMessage>? = resumedHistory.takeIf { it.isNotEmpty() }

    if (resumedHistory.isNotEmpty()) {
        val lastAssistantMsg = resumedHistory.lastOrNull { it is AgentMessage.Assistant }
        val lastContent = (lastAssistantMsg as? AgentMessage.Assistant)?.content
        println("Resuming previous session with ${resumedHistory.size} existing messages.")
        if (lastContent != null) {
            println("Last assistant reply: ${lastContent.take(200)}${if (lastContent.length > 200) "..." else ""}")
        }
    }

    var turn = 0
    while (true) {
        val prompt: String
        if (turn == 0 && initialPrompt != null) {
            prompt = initialPrompt
            println("You: $prompt")
        } else {
            println()
            print("You (empty to exit): ")
            val input = readLine()?.trim().orEmpty()
            if (input.isEmpty() || input == "exit" || input == "quit") {
                println("Chat ended.")
                break
            }
            prompt = input
        }

        val answer = if (history != null) {
            agent.run(prompt, history)
        } else {
            agent.run(prompt)
        }

        println()
        println("Assistant:")
        println(answer)

        turn++
        reader.loadLatestHistory()?.let { loaded ->
            history = resumedHistory + loaded
        }
    }
}

private fun printUsage() {
    println(
        """
        Usage:
          ./gradlew run --args="ask \"列出当前项目文件\""
          ./gradlew run --args="chat"
          ./gradlew run --args="chat \"列出当前项目文件\""

        Commands:
          ask   - Ask a single question and get an answer.
          chat  - Interactive multi-turn chat. Provide an optional initial question.
                 After each answer, type your next question. Empty line to exit.

        Configuration (local.properties or environment variables):
          deepseek.api.key=...
          deepseek.model=deepseek-v4-flash
          deepseek.base.url=https://api.deepseek.com

        The API key can also be provided with DEEPSEEK_API_KEY.
        """.trimIndent(),
    )
}
