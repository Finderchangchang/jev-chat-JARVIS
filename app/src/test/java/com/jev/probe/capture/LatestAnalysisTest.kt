package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class LatestAnalysisTest {
    private val a = ConversationKey("chat.app", 1, "Alice")
    private val b = ConversationKey("chat.app", 1, "Bob")

    @Test fun switchingChatsRejectsOldResultsEvenWithIdenticalMessages() {
        val state = LatestAnalysis<String>()
        state.observe(a, "other:hello")
        state.enqueue("Alice")
        val old = state.start()!!
        assertTrue(state.observe(b, "other:hello"))
        assertFalse(state.isCurrent(old))
        state.enqueue("Bob")
        assertNull(state.start())
        state.finish(old, LatestAnalysis.Part.REPLIES)
        assertNull(state.start())
        state.finish(old, LatestAnalysis.Part.JUDGMENT)
        assertEquals("Bob", state.start()!!.value)
    }

    @Test fun burstKeepsOnlyNewestMessageAndDrainsWithoutAnotherEvent() {
        val state = LatestAnalysis<String>()
        state.observe(a, "one")
        state.enqueue("one")
        val first = state.start()!!
        for (message in listOf("two", "three", "four")) {
            state.observe(a, message)
            state.enqueue(message)
            assertNull(state.start())
        }
        state.finish(first, LatestAnalysis.Part.JUDGMENT)
        state.finish(first, LatestAnalysis.Part.REPLIES)
        val latest = state.start()!!
        assertEquals("four", latest.value)
        assertTrue(state.isCurrent(latest))
        assertFalse(state.isCurrent(first))
    }

    @Test fun neitherFailureNorDuplicateCompletionUnlocksTheOtherPart() {
        for (firstPart in LatestAnalysis.Part.values()) {
            val state = LatestAnalysis<String>()
            state.observe(a, "one")
            state.enqueue("first")
            val first = state.start()!!
            state.enqueue("retry")
            // A failed route completes only its own part, in either completion order.
            state.finish(first, firstPart)
            state.finish(first, firstPart)
            assertNull(state.start())
            state.finish(first, LatestAnalysis.Part.values().first { it != firstPart })
            val retry = state.start()!!
            assertEquals("retry", retry.value)
            state.finish(first, firstPart) // stale completion cannot unlock the retry
            state.enqueue("third")
            assertNull(state.start())
        }
    }

    @Test fun leavingOrDisablingClearsPendingAndInvalidatesCompletedButtons() {
        val state = LatestAnalysis<String>()
        state.observe(a, "one")
        state.enqueue("first")
        val first = state.start()!!
        state.enqueue("pending")
        state.invalidate()
        state.finish(first, LatestAnalysis.Part.JUDGMENT)
        state.finish(first, LatestAnalysis.Part.REPLIES)
        assertNull(state.start())
        assertFalse(state.isCurrent(first))
        state.observe(a, "one") // returning to exactly the same chat is a new session
        assertFalse(state.isCurrent(first))
    }

    @Test fun unchangedEventsPreserveRequestsButOutgoingMessagesCancelPending() {
        val state = LatestAnalysis<String>()
        state.observe(a, "incoming")
        state.enqueue("first")
        val first = state.start()!!
        assertFalse(state.observe(a, "incoming"))
        assertTrue(state.isCurrent(first))
        state.enqueue("pending")
        state.observe(a, "outgoing") // caller intentionally does not enqueue auto analysis
        state.finish(first, LatestAnalysis.Part.JUDGMENT)
        state.finish(first, LatestAnalysis.Part.REPLIES)
        assertNull(state.start())
        assertFalse(state.isCurrent(first))
    }

    @Test fun appAndWindowArePartOfIdentity() {
        val state = LatestAnalysis<String>()
        assertTrue(state.observe(a, "same"))
        assertTrue(state.observe(a.copy(pkg = "another.app"), "same"))
        assertTrue(state.observe(a.copy(windowId = 2), "same"))
    }

    @Test fun blankAndLoadingTitlesNeverBecomeContactIdentities() {
        for (title in listOf(null, "", "  ", "连接中…", "正在连接...", "Loading", "Connecting…", "同步中")) {
            assertNull(ConversationKey.confirmedTitle(title))
        }
        assertEquals("Alice", ConversationKey.confirmedTitle("  Alice  "))
        assertEquals("项目组 (12)", ConversationKey.confirmedTitle("项目组 (12)"))
    }
}
