package com.jev.probe.core

import org.junit.Assert.assertNotEquals
import org.junit.Test

class AnalysisIdentityTest {
    @Test
    fun resultIsInvalidAfterChangingAppConversationOrMessages() {
        val first = ChatSnapshot("小李", listOf(Msg("other", "在吗")))
        val changedTitle = first.copy(title = "小王")
        val changedMessage = first.copy(messages = listOf(Msg("other", "明天见")))
        val original = AnalysisIdentity.of("com.tencent.mm", first)

        assertNotEquals(original, AnalysisIdentity.of("com.tencent.mobileqq", first))
        assertNotEquals(original, AnalysisIdentity.of("com.tencent.mm", changedTitle))
        assertNotEquals(original, AnalysisIdentity.of("com.tencent.mm", changedMessage))
    }
}
