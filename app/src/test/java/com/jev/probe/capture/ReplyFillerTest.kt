package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class ReplyFillerTest {
    private class FakeTarget : ReplyFiller.Target {
        var current = true
        var input = ""
        var acceptSet = false
        var switchOnClear = false
        val actions = mutableListOf<String>()
        override fun isCurrent() = current
        override fun read() = input
        override fun set(text: String): Boolean {
            actions.add("set:$text")
            if (acceptSet) input = text
            if (text.isEmpty() && switchOnClear) current = false
            return true
        }
        override fun focus(): Boolean { actions.add("focus"); return true }
        override fun paste(): Boolean { actions.add("paste"); return true }
        override fun copy(text: String) { actions.add("copy:$text") }
        override fun toast(message: String) {}
    }

    @Test fun staleButtonCannotWriteOrCopy() {
        val target = FakeTarget().apply { current = false }
        ReplyFiller(target) { _, _ -> fail("No retry should be scheduled") }.fill("reply", true)
        assertTrue(target.actions.isEmpty())
    }

    @Test fun switchingDuringFocusDelayStopsTheRetry() {
        val target = FakeTarget()
        val pending = ArrayDeque<() -> Unit>()
        ReplyFiller(target) { _, action -> pending.addLast(action) }.fill("reply", true)
        pending.removeFirst().invoke() // failed verification focuses the box
        assertEquals(listOf("set:reply", "focus"), target.actions)
        target.current = false
        pending.removeFirst().invoke()
        assertEquals(listOf("set:reply", "focus"), target.actions)
        assertTrue(pending.isEmpty())
    }

    @Test fun switchingAfterClearStopsPaste() {
        val target = FakeTarget().apply { switchOnClear = true }
        val pending = ArrayDeque<() -> Unit>()
        ReplyFiller(target) { _, action -> pending.addLast(action) }.fill("reply", true)
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        assertTrue(target.actions.contains("set:"))
        assertFalse(target.actions.contains("paste"))
    }

    @Test fun unverifiedConversationCopiesWithoutTouchingInput() {
        val target = FakeTarget()
        ReplyFiller(target) { _, _ -> fail("No retry needed") }.fill("reply", false)
        assertEquals(listOf("copy:reply"), target.actions)
    }

    @Test fun successfulSetDoesNotFocusClearOrPaste() {
        val target = FakeTarget().apply { acceptSet = true }
        val pending = ArrayDeque<() -> Unit>()
        ReplyFiller(target) { _, action -> pending.addLast(action) }.fill("reply", true)
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        assertEquals("reply", target.input)
        assertEquals(listOf("set:reply"), target.actions)
    }
}
