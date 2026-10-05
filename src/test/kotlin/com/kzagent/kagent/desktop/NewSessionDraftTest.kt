package com.kzagent.kagent.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class NewSessionDraftTest {
    @Test
    fun blankInputAndRepeatedSubmissionAreRejectedWithoutLosingTheDraft() {
        val draft = NewSessionDraft(Path.of("project"))
        draft.input.edit { append("  \n ") }
        assertFalse(draft.beginSubmission())
        draft.input.edit { replace(0, length, "修复问题") }
        assertTrue(draft.beginSubmission())
        assertFalse(draft.beginSubmission())
        assertEquals("修复问题", draft.input.text.toString())
        draft.submitting = false
        assertTrue(draft.beginSubmission())
    }
}
