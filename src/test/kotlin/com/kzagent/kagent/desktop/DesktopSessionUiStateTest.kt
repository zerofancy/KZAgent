package com.kzagent.kagent.desktop

import com.kzagent.kagent.desktop.app.DesktopSessionUiState
import com.kzagent.kagent.tools.ApprovalDecision
import com.kzagent.kagent.tools.ApprovalRequest
import com.kzagent.kagent.tools.ApprovalSource
import com.kzagent.kagent.tools.RiskAssessment
import com.kzagent.kagent.tools.UserQuestion
import com.kzagent.kagent.tools.UserQuestionAnswer
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSessionUiStateTest {
    @Test
    fun approvalCompletionAndCancellationRemovePendingDialogs() = runBlocking {
        val state = DesktopSessionUiState(Path.of("project"))
        val request = ApprovalRequest.CommandExecution(
            "pwd", Duration.ofSeconds(10), state.draft.workspace, RiskAssessment.NONE,
        )
        for (allowed in listOf(true, false)) {
            val result = async(start = CoroutineStart.UNDISPATCHED) { state.approvalPolicy.approve(request) }
            assertEquals(request, state.pendingApprovals.single().request)
            state.pendingApprovals.single().complete(allowed)
            assertEquals(if (allowed) ApprovalDecision.ALLOW else ApprovalDecision.DENY, result.await().decision)
            assertEquals(ApprovalSource.HUMAN, result.await().source)
            assertTrue(state.pendingApprovals.isEmpty())
        }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { state.approvalPolicy.approve(request) }
        val pending = state.pendingApprovals.single()
        cancelled.cancelAndJoin()
        assertTrue(state.pendingApprovals.isEmpty())
        // A late UI callback after cancellation must not resume the cancelled request.
        pending.complete(true)
        assertTrue(cancelled.isCancelled)
    }

    @Test
    fun questionAnswersAndCancellationRemovePendingDialogs() = runBlocking {
        val state = DesktopSessionUiState(Path.of("project"))
        val questions = listOf(UserQuestion("需要哪种实现？"))
        val result = async(start = CoroutineStart.UNDISPATCHED) { state.userQuestionPrompter.ask(questions) }
        assertEquals(questions, state.pendingUserQuestions.single().questions)
        val answers = listOf(UserQuestionAnswer("按现有设计实现"))
        state.pendingUserQuestions.single().complete(answers)
        assertEquals(answers, result.await())
        assertTrue(state.pendingUserQuestions.isEmpty())
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { state.userQuestionPrompter.ask(questions) }
        val pending = state.pendingUserQuestions.single()
        cancelled.cancelAndJoin()
        assertTrue(state.pendingUserQuestions.isEmpty())
        pending.complete(answers)
        assertTrue(cancelled.isCancelled)
    }
}
