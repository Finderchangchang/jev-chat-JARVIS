package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrReviewDraftTest {
    @Test
    fun correctedTextAndSidesAreUsedOnlyAfterConfirmation() {
        val original = listOf(Msg("other", "错字"), Msg("other", "无关内容"))
        val draft = OcrReviewDraft(original)

        assertEquals("me", draft.switchSide(0))
        draft.updateText(0, "  我说的话  ")
        draft.updateText(1, "   ")

        assertEquals(listOf(Msg("me", "我说的话")), draft.confirmedMessages())
        assertEquals("错字", original[0].text)
    }
}
