package com.jev.probe.capture

import org.junit.Assert.*
import org.junit.Test

class GuardedInputWriterTest {
    private class Fixture {
        val session = ConversationSession()
        val a = ConversationSession.Target("chat.app", 1, "Alice")
        val b = a.copy(title = "Bob")
        val queue = ArrayDeque<() -> Unit>()
        val writes = ArrayList<String>()
        val results = ArrayList<Boolean>()
        var acceptSetText = false
        var onClear: () -> Unit = {}
        var clipboard = ""
        val input = object : GuardedInputWriter.Input {
            override var text: String? = "original draft"
            override fun setText(text: String): Boolean {
                writes.add("set:$text")
                if (text.isEmpty()) { this.text = ""; onClear() }
                else if (acceptSetText) this.text = text
                return true
            }
            override fun focus() { writes.add("focus") }
            override fun paste() { writes.add("paste"); text = clipboard }
        }

        init { session.observe(a) }
        private val token = session.begin()!!
        val writer = GuardedInputWriter(
            resolve = { if (session.accepts(token)) input else null },
            later = { _, action -> queue.addLast(action) },
            copy = { clipboard = it },
            complete = { results.add(it) }
        )
        fun step() { queue.removeFirst().invoke() }
        fun drain() { while (queue.isNotEmpty()) step() }
    }

    @Test fun successfulSetDoesNotFocusClearOrPaste() {
        val f = Fixture()
        f.acceptSetText = true
        f.writer.fill("reply")
        f.drain()
        assertEquals(listOf("set:reply"), f.writes)
        assertEquals(listOf(true), f.results)
    }

    @Test fun switchBeforeClickDoesNotWriteAnything() {
        val f = Fixture()
        f.session.observe(f.b)
        f.writer.fill("reply")
        assertTrue(f.writes.isEmpty())
    }

    @Test fun switchAfterFirstWriteStopsFocusAndRetry() {
        val f = Fixture()
        f.writer.fill("reply")
        f.session.observe(f.b)
        f.drain()
        assertEquals(listOf("set:reply"), f.writes)
        assertTrue(f.results.isEmpty())
    }

    @Test fun switchDuringFocusDelayStopsRetryAndPaste() {
        val f = Fixture()
        f.writer.fill("reply")
        f.step()
        f.session.observe(f.b)
        f.drain()
        assertEquals(listOf("set:reply", "focus"), f.writes)
    }

    @Test fun invalidationBeforePasteDoesNotClearDraft() {
        val f = Fixture()
        f.writer.fill("reply")
        f.step()
        f.step()
        f.session.invalidate()
        f.drain()
        assertEquals(listOf("set:reply", "focus", "set:reply"), f.writes)
        assertEquals("original draft", f.input.text)
    }

    @Test fun switchTriggeredByClearStopsPaste() {
        val f = Fixture()
        f.onClear = { f.session.observe(f.b) }
        f.writer.fill("reply")
        f.drain()
        assertEquals(listOf("set:reply", "focus", "set:reply", "set:"), f.writes)
        assertTrue(f.results.isEmpty())
    }

    @Test fun unchangedSessionCanUseClipboardFallback() {
        val f = Fixture()
        f.writer.fill("reply")
        f.drain()
        assertEquals(listOf("set:reply", "focus", "set:reply", "set:", "paste"), f.writes)
        assertEquals("reply", f.input.text)
        assertEquals(listOf(true), f.results)
    }
}
