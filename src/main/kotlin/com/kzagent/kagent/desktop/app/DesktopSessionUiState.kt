package com.kzagent.kagent.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kzagent.kagent.desktop.NewSessionDraft
import com.kzagent.kagent.desktop.PendingApproval
import com.kzagent.kagent.desktop.PendingUserQuestions
import com.kzagent.kagent.desktop.SessionManager
import com.kzagent.kagent.tools.ApprovalDecision
import com.kzagent.kagent.tools.ApprovalPolicy
import com.kzagent.kagent.tools.ApprovalResult
import com.kzagent.kagent.tools.ApprovalSource
import com.kzagent.kagent.tools.UserQuestionPrompter
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Process-local page and dialog state; runtime resources remain owned by SessionManager. */
internal class DesktopSessionUiState(initialWorkspace: Path) {
    var showNewSession by mutableStateOf(true)
    var hasDraft by mutableStateOf(true)
    var draft by mutableStateOf(NewSessionDraft(initialWorkspace))
    var input by mutableStateOf("")
    val pendingApprovals = mutableStateListOf<PendingApproval>()
    val pendingUserQuestions = mutableStateListOf<PendingUserQuestions>()
    var showDeleteConfirmIndex by mutableStateOf(-1)
    var showRenameDialogIndex by mutableStateOf(-1)
    var renameText by mutableStateOf("")
    var renameSuggesting by mutableStateOf(false)
    var showCompressConfirm by mutableStateOf(false)
    var showSkills by mutableStateOf(false)
    var deletingSkill by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showSessionSidePanel by mutableStateOf(true)
    var sessionLoadError by mutableStateOf<String?>(null)
    val sessionWorkspaceExpandedState = mutableStateMapOf<String, Boolean>()

    val approvalPolicy = ApprovalPolicy { request ->
        suspendCancellableCoroutine { continuation ->
            lateinit var approval: PendingApproval
            approval = PendingApproval(request) { allowed ->
                pendingApprovals.remove(approval)
                if (continuation.isActive) {
                    continuation.resume(
                        ApprovalResult(
                            decision = if (allowed) ApprovalDecision.ALLOW else ApprovalDecision.DENY,
                            source = ApprovalSource.HUMAN,
                            reason = if (allowed) "用户已批准。" else "用户已拒绝。",
                        ),
                    )
                }
            }
            continuation.invokeOnCancellation { pendingApprovals.remove(approval) }
            pendingApprovals.add(approval)
        }
    }

    val userQuestionPrompter = UserQuestionPrompter { questions ->
        suspendCancellableCoroutine { continuation ->
            lateinit var pending: PendingUserQuestions
            pending = PendingUserQuestions(questions) { answers ->
                pendingUserQuestions.remove(pending)
                if (continuation.isActive) continuation.resume(answers)
            }
            continuation.invokeOnCancellation { pendingUserQuestions.remove(pending) }
            pendingUserQuestions.add(pending)
        }
    }
}
