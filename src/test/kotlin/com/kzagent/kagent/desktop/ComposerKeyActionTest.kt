package com.kzagent.kagent.desktop

import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerKeyActionTest {
    @Test
    fun plainEnterSendsOnKeyDownAndConsumesKeyUp() {
        assertEquals(
            ComposerKeyAction.Send,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = false,
                eventType = KeyEventType.KeyDown,
                isBusy = false,
            ),
        )
        assertEquals(
            ComposerKeyAction.Consume,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = false,
                eventType = KeyEventType.KeyUp,
                isBusy = false,
            ),
        )
    }

    @Test
    fun plainEnterTerminatesWhileBusy() {
        assertEquals(
            ComposerKeyAction.Terminate,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = false,
                eventType = KeyEventType.KeyDown,
                isBusy = true,
            ),
        )
    }

    @Test
    fun ctrlEnterAndOtherKeysPassThroughToTextField() {
        assertEquals(
            ComposerKeyAction.PassThrough,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = true,
                eventType = KeyEventType.KeyDown,
                isBusy = false,
            ),
        )
        assertEquals(
            ComposerKeyAction.PassThrough,
            resolveComposerKeyAction(
                isEnter = false,
                isCtrlPressed = false,
                eventType = KeyEventType.KeyDown,
                isBusy = false,
            ),
        )
    }
}
