package com.jev.probe.capture

import android.content.Intent
import android.app.UiAutomation
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real device windows and ACTION_SET_TEXT, with synthetic chats and no network or sending. */
@RunWith(AndroidJUnit4::class)
class DeviceInputTest {
    private val driver = HandlerThread("device-test-driver").apply { start() }
    @After fun stopDriver() { driver.quitSafely() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it) }
        }
        return result
    }

    private fun withChat(test: (ActivityScenario<DeviceTestActivity>) -> Unit) {
        // Connect before launching so the initial accessibility tree is available.
        automation
        ActivityScenario.launch<DeviceTestActivity>(Intent(instrumentation.targetContext, DeviceTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            instrumentation.waitForIdleSync()
            automation.waitForIdle(200, 3000)
            assertTrue("Fixture window must be visible", nodes().any { it.text?.toString() == "Test Alice" })
            test(scenario)
        }
    }

    private inner class Target : ReplyFiller.Target {
        var allowSet = true
        var writes = 0
        var copies = 0
        val finished = CountDownLatch(1)
        override fun isCurrent() = nodes().any { it.text?.toString() == "Test Alice" }
        override fun read() = nodes().firstOrNull { it.isEditable }?.text?.toString()
        override fun set(text: String): Boolean {
            if (!isCurrent()) return false
            if (!allowSet) return false // exercise the actual delayed fallback path
            val input = nodes().firstOrNull { it.isEditable } ?: return false
            writes++
            return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })
        }
        override fun focus() = nodes().firstOrNull { it.isEditable }
            ?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
        override fun paste(): Boolean { fail("Unexpected paste in this scenario"); return false }
        override fun copy(text: String) { copies++ } // do not alter the user's clipboard
        override fun toast(message: String) { finished.countDown() }
    }

    @Test fun realAccessibilitySetTextFillsOnlyTheTestInput() = withChat { scenario ->
        val target = Target()
        run {
            ReplyFiller(target) { delay, action -> Handler(driver.looper).postDelayed({ action() }, delay) }
                .fill("Device regression reply", true)
        }
        assertTrue(target.finished.await(5, TimeUnit.SECONDS))
        scenario.onActivity { assertEquals("Device regression reply", it.input.text.toString()) }
        assertEquals(1, target.writes)
        assertEquals(0, target.copies)
    }

    @Test fun switchingRealWindowContentDuringRetryPreservesNewDraft() = withChat { scenario ->
        val target = Target().apply { allowSet = false }
        val waitingRetry = CountDownLatch(1)
        var retry: (() -> Unit)? = null
        run {
            ReplyFiller(target) { delay, action ->
                if (delay == 300L) { retry = action; waitingRetry.countDown() }
                else Handler(driver.looper).postDelayed({ action() }, delay)
            }.fill("Alice reply", true)
        }
        assertTrue("Expected focus retry", waitingRetry.await(5, TimeUnit.SECONDS))
        scenario.onActivity { it.switchConversation() }
        instrumentation.waitForIdleSync()
        automation.waitForIdle(200, 3000)
        target.allowSet = true
        run { retry!!.invoke() }
        scenario.onActivity { assertEquals("Bob's draft", it.input.text.toString()) }
        assertEquals(0, target.writes)
        assertEquals(0, target.copies)
    }

    @Test fun staleReplyButtonCannotModifyAnotherRealConversation() = withChat { scenario ->
        val target = Target()
        scenario.onActivity { it.switchConversation() }
        instrumentation.waitForIdleSync()
        automation.waitForIdle(200, 3000)
        run {
            ReplyFiller(target) { _, _ -> fail("Stale reply scheduled a retry") }.fill("Alice reply", true)
        }
        scenario.onActivity { assertEquals("Bob's draft", it.input.text.toString()) }
        assertEquals(0, target.writes)
        assertEquals(0, target.copies)
    }
}
