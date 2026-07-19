package com.kzagent.kagent.desktop

import androidx.compose.ui.input.key.KeyEventType
import com.kzagent.kagent.tools.ApprovalMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalKeyActionTest {
    @Test
    fun normalApprovalAllowsEnterOnKeyUp() {
        assertEquals(
            ApprovalKeyAction.Approve,
            resolveApprovalKeyAction(
                isEnter = true,
                isEscape = false,
                eventType = KeyEventType.KeyUp,
                highRisk = false,
            ),
        )
    }

    @Test
    fun highRiskApprovalConsumesEnterOnBothPhases() {
        assertEquals(
            ApprovalKeyAction.Consume,
            resolveApprovalKeyAction(true, false, KeyEventType.KeyDown, highRisk = true),
        )
        assertEquals(
            ApprovalKeyAction.Consume,
            resolveApprovalKeyAction(true, false, KeyEventType.KeyUp, highRisk = true),
        )
    }

    @Test
    fun escapeStillRejectsHighRiskApproval() {
        assertEquals(
            ApprovalKeyAction.Deny,
            resolveApprovalKeyAction(false, true, KeyEventType.KeyUp, highRisk = true),
        )
    }

    @Test
    fun onlyEnteringFullModeRequiresConfirmation() {
        assertEquals(
            true,
            requiresFullModeConfirmation(ApprovalMode.AUTO, ApprovalMode.FULL),
        )
        assertEquals(
            false,
            requiresFullModeConfirmation(ApprovalMode.FULL, ApprovalMode.FULL),
        )
        assertEquals(
            false,
            requiresFullModeConfirmation(ApprovalMode.MANUAL, ApprovalMode.AUTO),
        )
    }
}
