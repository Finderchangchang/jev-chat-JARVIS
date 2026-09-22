package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatSnapshotTest {
    private val messages = listOf(Msg("other", "在吗？"), Msg("me", "在"))

    @Test
    fun sameConversationAndMessagesHaveSameSignature() {
        assertEquals(
            ChatSnapshot("Alice", messages).signature(),
            ChatSnapshot("Alice", messages.map { it.copy() }).signature()
        )
    }

    @Test
    fun differentConversationsWithIdenticalMessagesHaveDifferentSignatures() {
        assertNotEquals(
            ChatSnapshot("Alice", messages).signature(),
            ChatSnapshot("Bob", messages).signature()
        )
    }

    @Test
    fun messageTextCannotImpersonateMessageBoundaries() {
        assertNotEquals(
            ChatSnapshot("Alice", listOf(Msg("other", "hello|me:world"))).signature(),
            ChatSnapshot("Alice", listOf(Msg("other", "hello"), Msg("me", "world"))).signature()
        )
    }

    @Test
    fun titleCannotImpersonateMessageBoundaries() {
        assertNotEquals(
            ChatSnapshot("Alice|other:hello", listOf(Msg("me", "world"))).signature(),
            ChatSnapshot("Alice", listOf(Msg("other", "hello"), Msg("me", "world"))).signature()
        )
    }

    @Test
    fun senderAndMessageOrderAffectSignature() {
        val snapshot = ChatSnapshot("Alice", messages)
        assertNotEquals(snapshot.signature(), snapshot.copy(messages = messages.reversed()).signature())
        assertNotEquals(snapshot.signature(), snapshot.copy(messages = messages.map { it.copy(side = "other") }).signature())
    }

    @Test
    fun onlyLastSixMessagesAffectContentSignature() {
        val recent = (1..6).map { Msg("other", "消息 $it") }
        val snapshot = ChatSnapshot("Alice", recent)
        assertEquals(snapshot.signature(), snapshot.copy(messages = listOf(Msg("me", "旧消息")) + recent).signature())
        assertNotEquals(snapshot.signature(), snapshot.copy(messages = recent + Msg("other", "新消息")).signature())
    }

    @Test
    fun absentTitlesAreStableAndDifferFromKnownTitles() {
        val snapshot = ChatSnapshot(null, messages)
        assertEquals(snapshot.signature(), snapshot.copy().signature())
        assertNotEquals(snapshot.signature(), snapshot.copy(title = "Alice").signature())
    }

    @Test
    fun emptySnapshotsStillDistinguishConversations() {
        assertEquals(ChatSnapshot("Alice", emptyList()).signature(), ChatSnapshot("Alice", emptyList()).signature())
        assertNotEquals(ChatSnapshot("Alice", emptyList()).signature(), ChatSnapshot("Bob", emptyList()).signature())
    }
}
