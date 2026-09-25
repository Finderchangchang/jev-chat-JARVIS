package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class ConversationSessionTest {
    private val chatA = ConversationSession.Target("com.tencent.mobileqq", 7, "Alice")
    private val chatB = chatA.copy(title = "Bob")

    @Test fun unchangedConversationAcceptsBothCallbacks() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        assertFalse(session.observe(chatA))
        assertTrue(session.accepts(request)) // judgment
        assertTrue(session.accepts(request)) // replies
    }

    @Test fun switchingChatRejectsOldResultsAndFill() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatB)
        assertFalse(session.accepts(request))
        assertTrue(session.accepts(session.begin()!!))
    }

    @Test fun returningToOriginalChatDoesNotReviveOldRequest() {
        val session = ConversationSession()
        session.observe(chatA)
        val old = session.begin()!!
        session.observe(chatB)
        session.observe(chatA)
        assertFalse(session.accepts(old))
    }

    @Test fun sameTitleInDifferentAppIsDifferentTarget() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatA.copy(pkg = "com.twitter.android"))
        assertFalse(session.accepts(request))
    }

    @Test fun replacementWindowInvalidatesTarget() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(chatA.copy(windowId = 8))
        assertFalse(session.accepts(request))
    }

    @Test fun differentMessagesInvalidateEvenWhenChatTitlesMatch() {
        val session = ConversationSession()
        session.observe(chatA.copy(messagesSignature = "other:hello"))
        val request = session.begin()!!
        session.observe(chatA.copy(messagesSignature = "other:goodbye"))
        assertFalse(session.accepts(request))
    }

    @Test fun leavingChatInvalidatesResultsAndDelayedWrites() {
        val session = ConversationSession()
        session.observe(chatA)
        val request = session.begin()!!
        session.observe(null)
        assertFalse(session.accepts(request))
        assertNull(session.begin())
    }

    @Test fun newRequestRejectsPreviousRequestEvenInSameChat() {
        val session = ConversationSession()
        session.observe(chatA)
        val old = session.begin()!!
        val new = session.begin()!!
        assertFalse(session.accepts(old))
        assertTrue(session.accepts(new))
    }

    @Test fun explicitInvalidationRejectsScreenshotAndNetworkCallbacks() {
        val session = ConversationSession()
        session.observe(chatA)
        val screenshot = session.token()!!
        val request = session.begin()!!
        session.invalidate()
        assertFalse(session.accepts(screenshot))
        assertFalse(session.accepts(request))
    }
}
