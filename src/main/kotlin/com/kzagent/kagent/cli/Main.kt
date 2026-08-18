package com.kzagent.kagent.cli

import com.kzagent.kagent.AgentRuntimeFactory
import com.kzagent.kagent.agent.SessionReader
import com.kzagent.kagent.config.AppDataDir
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.config.FileKitPaths
import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.tools.TerminalApprovalPolicy
import com.kzagent.kagent.tools.UserQuestion
import com.kzagent.kagent.tools.UserQuestionAnswer
import com.kzagent.kagent.tools.UserQuestionPrompter
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

fun main(args: Array<String>) {
    FileKitPaths.initialize()
    kotlin.system.exitProcess(runCli(args))
}

fun runCli(args: Array<String>): Int = runBlocking {
    val effectiveArgs = args.ifEmpty { arrayOf("chat") }
    val command = effectiveArgs[0]
    val workspace = Path.of("").toAbsolutePath().normalize()

    try {
        when (command) {
            "ask" -> {
                val prompt = effectiveArgs.drop(1).joinToString(" ")
                if (prompt.isBlank()) {
                    printUsage()
                    return@runBlocking 0
                }
                val runtime = AgentRuntimeFactory.create(workspace, TerminalApprovalPolicy, TerminalUserQuestionPrompter)
                val answer = runtime.agent.run(prompt)
                println(answer)
            }
            "chat" -> {
                val runtime = AgentRuntimeFactory.create(workspace, TerminalApprovalPolicy, TerminalUserQuestionPrompter)
                val initialPrompt = effectiveArgs.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
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

internal object TerminalUserQuestionPrompter : UserQuestionPrompter {
    private val timeout = Duration.ofMinutes(5)
    override suspend fun ask(questions: List<UserQuestion>): List<UserQuestionAnswer> = questions.mapIndexed { index, question ->
        println()
        println("问题 ${index + 1}/${questions.size}: ${question.question}")
        question.options.forEachIndexed { optionIndex, option ->
            println("${optionIndex + 1}. ${option.label} — ${option.description}")
        }
        print("输入选项编号或自由答案（直接回车跳过，5 分钟超时）：")
        val input = withTimeoutOrNull(timeout.toMillis()) {
            withContext(Dispatchers.IO) { readlnOrNull() }
        }?.trim()
        val answer = input?.takeIf { it.isNotEmpty() }?.let { value ->
            value.toIntOrNull()?.let { number -> question.options.getOrNull(number - 1)?.label } ?: value
        }
        UserQuestionAnswer(answer)
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
          ./gradlew run
          ./gradlew run --args="app"
          ./gradlew run --args="ask \"列出当前项目文件\""
          ./gradlew run --args="chat"
          ./gradlew run --args="chat \"列出当前项目文件\""

        Commands:
          app   - Launch the desktop application in a new session using the current directory.
          ask   - Ask a single question and get an answer.
          chat  - Interactive multi-turn chat. Provide an optional initial question.
                 After each answer, type your next question. Empty line to exit.
          (no command) - Same as chat.

        Configuration (%APPDATA%\kzagent\config.properties on Windows,
        ~/Library/Application Support/kzagent/config.properties on macOS,
        or ~/.config/kzagent/config.properties on Linux):
          deepseek.api.key=...
          deepseek.base.url=https://api.deepseek.com
          openrouter.api.key=...
          openrouter.base.url=https://openrouter.ai/api/v1
          kzagent.default.provider=deepseek  # deepseek | openrouter
          kzagent.default.model=deepseek-v4-pro
          kzagent.sensitive.path.protection=false
          kzagent.approval.mode=auto  # auto | manual | full

        DEEPSEEK_API_KEY and OPENROUTER_API_KEY take priority over the config file.
        """.trimIndent(),
    )
}
