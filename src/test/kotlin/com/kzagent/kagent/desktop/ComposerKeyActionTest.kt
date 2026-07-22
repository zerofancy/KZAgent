package com.kzagent.kagent.desktop

import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
                isMetaPressed = false,
                isMacOs = false,
                eventType = KeyEventType.KeyDown,
                isBusy = false,
            ),
        )
        assertEquals(
            ComposerKeyAction.Consume,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = false,
                isMetaPressed = false,
                isMacOs = false,
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
                isMetaPressed = false,
                isMacOs = false,
                eventType = KeyEventType.KeyDown,
                isBusy = true,
            ),
        )
    }

    @Test
    fun platformLineBreakShortcutsRequestExplicitInsertion() {
        assertEquals(
            ComposerKeyAction.InsertLineBreak,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = true,
                isMetaPressed = false,
                isMacOs = false,
                eventType = KeyEventType.KeyDown,
                isBusy = false,
            ),
        )
        assertEquals(
            ComposerKeyAction.InsertLineBreak,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = false,
                isMetaPressed = true,
                isMacOs = true,
                eventType = KeyEventType.KeyDown,
                isBusy = false,
            ),
        )
    }

    @Test
    fun lineBreakShortcutKeyUpIsConsumedWithoutInsertingAgain() {
        assertEquals(
            ComposerKeyAction.Consume,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = false,
                isMetaPressed = true,
                isMacOs = true,
                eventType = KeyEventType.KeyUp,
                isBusy = false,
            ),
        )
    }

    @Test
    fun lineBreakIsInsertedAtCursorOrReplacesSelection() {
        assertEquals(
            TextFieldValue("first\nsecond", TextRange(6)),
            insertLineBreak(TextFieldValue("firstsecond", TextRange(5))),
        )
        assertEquals(
            TextFieldValue("first\nsecond", TextRange(6)),
            insertLineBreak(TextFieldValue("first selected second", TextRange(5, 15))),
        )
    }

    @Test
    fun commandEnterDoesNotBecomeLineBreakOnOtherPlatforms() {
        assertEquals(
            ComposerKeyAction.Send,
            resolveComposerKeyAction(
                isEnter = true,
                isCtrlPressed = false,
                isMetaPressed = true,
                isMacOs = false,
                eventType = KeyEventType.KeyDown,
                isBusy = false,
            ),
        )
    }

    @Test
    fun otherKeysPassThroughToTextField() {
        assertEquals(
            ComposerKeyAction.PassThrough,
            resolveComposerKeyAction(
                isEnter = false,
                isCtrlPressed = false,
                isMetaPressed = false,
                isMacOs = false,
                eventType = KeyEventType.KeyDown,
                isBusy = false,
            ),
        )
    }
}
