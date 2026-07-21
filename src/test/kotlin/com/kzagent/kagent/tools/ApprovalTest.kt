package com.kzagent.kagent.tools

import com.kzagent.kagent.llm.AgentMessage
import com.kzagent.kagent.llm.AssistantReply
import com.kzagent.kagent.llm.ChatModel
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalTest {
    @Test
    fun commandApprovalDetailsClarifyThatTimeoutAppliesToExecution() {
        assertEquals("命令：echo ok\n命令执行超时：30 秒", commandRequest("echo ok").details())
    }

    @Test
    fun manualModeAlwaysUsesHumanPolicy() = runBlocking {
        var humanCalls = 0
        var agentCalls = 0
        val policy = ModeApprovalPolicy(
            mode = ApprovalMode.MANUAL,
            humanPolicy = ApprovalPolicy {
                humanCalls++
                allowed(ApprovalSource.HUMAN)
            },
            approvalAgent = ApprovalAgent {
                agentCalls++
                allowed(ApprovalSource.APPROVAL_AGENT)
            },
        )

        val result = policy.approve(commandRequest("echo ok"))

        assertTrue(result.allowed)
        assertEquals(1, humanCalls)
        assertEquals(0, agentCalls)
    }

    @Test
    fun autoModeUsesStaticRulesThenAgentAndHumanFallback() = runBlocking {
        var humanCalls = 0
        var agentCalls = 0
        val human = ApprovalPolicy {
            humanCalls++
            allowed(ApprovalSource.HUMAN)
        }
        val agent = ApprovalAgent {
            agentCalls++
            when (it) {
                is ApprovalRequest.CommandExecution ->
                    ApprovalResult(ApprovalDecision.ASK_USER, ApprovalSource.APPROVAL_AGENT, "uncertain")
                is ApprovalRequest.ExternalFileRead ->
                    ApprovalResult(ApprovalDecision.DENY, ApprovalSource.APPROVAL_AGENT, "outside")
            }
        }
        val policy = ModeApprovalPolicy(ApprovalMode.AUTO, human, agent)

        val staticResult = policy.approve(commandRequest("git status"))
        val fallbackResult = policy.approve(commandRequest("unknown-tool value"))
        val externalResult = policy.approve(externalReadRequest())

        assertEquals(ApprovalSource.STATIC_RULE, staticResult.source)
        assertEquals(ApprovalSource.HUMAN, fallbackResult.source)
        assertEquals(ApprovalDecision.DENY, externalResult.decision)
        assertEquals(1, humanCalls)
        assertEquals(2, agentCalls)
    }

    @Test
    fun autoModeSendsHighRiskCommandDirectlyToHuman() = runBlocking {
        var humanCalls = 0
        var agentCalls = 0
        val policy = ModeApprovalPolicy(
            ApprovalMode.AUTO,
            ApprovalPolicy {
                humanCalls++
                allowed(ApprovalSource.HUMAN)
            },
            ApprovalAgent {
                agentCalls++
                allowed(ApprovalSource.APPROVAL_AGENT)
            },
        )

        policy.approve(
            commandRequest(
                command = "rm --version",
                risk = RiskAssessment(listOf("危险命令")),
            ),
        )

        assertEquals(1, humanCalls)
        assertEquals(0, agentCalls)
    }

    @Test
    fun fullModeBypassesHumanAndAgent() = runBlocking {
        var calls = 0
        val policy = ModeApprovalPolicy(
            ApprovalMode.FULL,
            ApprovalPolicy {
                calls++
                ApprovalResult(ApprovalDecision.DENY, ApprovalSource.HUMAN, "no")
            },
            ApprovalAgent {
                calls++
                ApprovalResult(ApprovalDecision.DENY, ApprovalSource.APPROVAL_AGENT, "no")
            },
        )

        val command = policy.approve(commandRequest("rm --version", RiskAssessment(listOf("danger"))))
        val read = policy.approve(externalReadRequest())

        assertTrue(command.allowed)
        assertTrue(read.allowed)
        assertEquals(0, calls)
    }

    @Test
    fun modelApprovalAgentParsesDecisionsAndFallsBackOnMalformedOutput() = runBlocking {
        val request = commandRequest("custom-tool")
        val allowAgent = ModelApprovalAgent(FakeModel("""{"decision":"allow","reason":"safe"}"""))
        val malformedAgent = ModelApprovalAgent(FakeModel("not json"))

        val allowed = allowAgent.decide(request)
        val fallback = malformedAgent.decide(request)

        assertEquals(ApprovalDecision.ALLOW, allowed.decision)
        assertEquals(ApprovalDecision.ASK_USER, fallback.decision)
        assertFalse(fallback.reason.isBlank())
    }

    @Test
    fun commandRiskAnalyzerReportsRiskWithoutRejecting() {
        val risk = CommandRiskAnalyzer.assess(
            "rm ../outside.txt | cat .env",
            sensitivePathProtection = true,
        )

        assertTrue(risk.isHighRisk)
        assertTrue(risk.reasons.any { "危险" in it })
        assertTrue(risk.reasons.any { "工作区外" in it })
        assertTrue(risk.reasons.any { "敏感" in it })
    }

    @Test
    fun staticRulesDoNotTreatMutatingBranchOrSystemProvidersAsSafe() {
        assertTrue(CommandStaticApprovalRule.isSafe("git status"))
        assertTrue(CommandStaticApprovalRule.isSafe("git branch --show-current"))
        assertFalse(CommandStaticApprovalRule.isSafe("git branch -D feature"))

        val providerRisk = CommandRiskAnalyzer.assess(
            "Get-Content Env:DEEPSEEK_API_KEY",
            sensitivePathProtection = false,
        )
        assertTrue(providerRisk.isHighRisk)
    }

    private fun commandRequest(
        command: String,
        risk: RiskAssessment = RiskAssessment.NONE,
    ): ApprovalRequest.CommandExecution {
        val workspace = Files.createTempDirectory("kagent-approval-workspace")
        return ApprovalRequest.CommandExecution(command, Duration.ofSeconds(30), workspace, risk)
    }

    private fun externalReadRequest(): ApprovalRequest.ExternalFileRead {
        val workspace = Files.createTempDirectory("kagent-approval-workspace")
        val external = Files.createTempFile("kagent-approval-external", ".txt")
        return ApprovalRequest.ExternalFileRead(
            path = external,
            startLine = 1,
            maxLines = 200,
            outsideWorkspace = true,
            sensitive = false,
            workspace = workspace,
            risk = RiskAssessment(listOf("outside")),
        )
    }

    private fun allowed(source: ApprovalSource) =
        ApprovalResult(ApprovalDecision.ALLOW, source, "allowed")

    private class FakeModel(private val content: String) : ChatModel {
        override suspend fun chat(messages: List<AgentMessage>, tools: List<JsonObject>): AssistantReply =
            AssistantReply(content = content)
    }
}
