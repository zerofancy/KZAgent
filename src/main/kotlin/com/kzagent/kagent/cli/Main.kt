package com.kzagent.kagent.cli

import com.kzagent.kagent.AgentRuntimeFactory
import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.TerminalApprovalPolicy
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    kotlin.system.exitProcess(runCli(args))
}

fun runCli(args: Array<String>): Int = runBlocking {
    if (args.isEmpty()) {
        printUsage()
        return@runBlocking 0
    }

    val command = args[0]
    val workspace = Path.of("").toAbsolutePath().normalize()

    try {
        when (command) {
            "ask" -> {
                val prompt = args.drop(1).joinToString(" ")
                if (prompt.isBlank()) {
                    printUsage()
                    return@runBlocking 0
                }
                val runtime = AgentRuntimeFactory.create(workspace, TerminalApprovalPolicy)
                val answer = runtime.agent.run(prompt)
                println(answer)
            }
            "chat" -> {
                val runtime = AgentRuntimeFactory.create(workspace, TerminalApprovalPolicy)
                val initialPrompt = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                interactiveChat(workspace, runtime.agent, initialPrompt)
            }
            else -> printUsage()
        }
        0
    } catch (e: Exception) {
        System.err.println("Error: ${SecretRedactor.redact(e.message ?: e.toString())}")
        1
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
    agent: com.kzagent.kagent.agent.CodingAgent,
    initialPrompt: String?,
) {
    val sessionsDir = AppDataDir.ensureSessionsDir(workspace)
    val reader = SessionReader(sessionsDir)
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
            val input = readlnOrNull()?.trim().orEmpty()
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

fun printUsage() {
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

        Configuration (%APPDATA%\kzagent\config.properties on Windows,
        ~/Library/Application Support/kzagent/config.properties on macOS,
        or ~/.config/kzagent/config.properties on Linux):
          deepseek.api.key=...
          deepseek.model=deepseek-v4-pro
          deepseek.base.url=https://api.deepseek.com
          deepseek.sensitive.path.protection=false

        DEEPSEEK_API_KEY can also be provided and takes priority over the config file.
        """.trimIndent(),
    )
}
