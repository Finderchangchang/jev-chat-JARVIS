package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the two gates behind "the bubble must not vanish" (issues #20,
 * #18). They are pure logic, so these run without a device or an emulator.
 */
class StabilityGatesTest {

    // ------------------------------------------------------- ForegroundLeaveGate

    @Test
    fun `a transient foreign window does not hide the bubble`() {
        val gate = ForegroundLeaveGate(confirmMs = 1_500)
        gate.onForeign("com.android.systemui", nowMs = 10_000)
        // A heads-up notification or the shade edge is gone again 300ms later.
        gate.onChatApp()
        assertFalse(gate.shouldHide(11_600))
        assertNull(gate.remainingMs(11_600))
    }

    @Test
    fun `hiding needs the whole confirmation window`() {
        val gate = ForegroundLeaveGate(confirmMs = 1_500)
        gate.onForeign("com.miui.home", nowMs = 1_000)
        assertFalse("before the window elapses", gate.shouldHide(2_499))
        assertTrue("exactly at the deadline", gate.shouldHide(2_500))
        assertTrue("after the deadline", gate.shouldHide(9_999))
    }

    @Test
    fun `remaining time counts down and clamps at zero`() {
        val gate = ForegroundLeaveGate(confirmMs = 1_500)
        gate.onForeign("com.miui.home", nowMs = 1_000)
        assertEquals(1_500L, gate.remainingMs(1_000))
        assertEquals(700L, gate.remainingMs(1_800))
        assertEquals(0L, gate.remainingMs(2_500))
        assertEquals(0L, gate.remainingMs(9_000))
    }

    @Test
    fun `repeated observations of one window do not restart the countdown`() {
        val gate = ForegroundLeaveGate(confirmMs = 1_000)
        gate.onForeign("com.android.systemui", nowMs = 0)
        gate.onForeign("com.android.systemui", nowMs = 400)
        gate.onForeign("com.android.systemui", nowMs = 800)
        assertTrue("counted from the first sighting", gate.shouldHide(1_000))
    }

    @Test
    fun `switching foreign window restarts the countdown`() {
        val gate = ForegroundLeaveGate(confirmMs = 1_000)
        gate.onForeign("com.android.systemui", nowMs = 0)
        gate.onForeign("com.miui.home", nowMs = 900)
        assertFalse("the new window has only just appeared", gate.shouldHide(1_000))
        assertTrue(gate.shouldHide(1_900))
    }

    @Test
    fun `reset clears a pending hide`() {
        val gate = ForegroundLeaveGate(confirmMs = 1_000)
        gate.onForeign("com.miui.home", nowMs = 0)
        gate.reset()
        assertFalse(gate.shouldHide(5_000))
    }

    // ------------------------------------------------------ OverlaySelfHealPolicy

    private fun heal(
        policy: OverlaySelfHealPolicy,
        nowMs: Long,
        enabled: Boolean = true,
        inChat: Boolean = true,
        allowed: Boolean = true,
        showing: Boolean = false,
        dismissed: Boolean = false
    ) = policy.shouldHeal(enabled, inChat, allowed, showing, dismissed, nowMs)

    @Test
    fun `heals a bubble that belongs on screen but is gone`() {
        assertTrue(heal(OverlaySelfHealPolicy(), nowMs = 1_000))
    }

    @Test
    fun `never heals while the bubble is already showing`() {
        assertFalse(heal(OverlaySelfHealPolicy(), nowMs = 1_000, showing = true))
    }

    @Test
    fun `does not heal when the assistant is off`() {
        assertFalse(heal(OverlaySelfHealPolicy(), nowMs = 1_000, enabled = false))
    }

    @Test
    fun `does not heal outside an adapted chat app`() {
        assertFalse(heal(OverlaySelfHealPolicy(), nowMs = 1_000, inChat = false))
    }

    @Test
    fun `does not heal when the whitelist excludes this chat`() {
        assertFalse(heal(OverlaySelfHealPolicy(), nowMs = 1_000, allowed = false))
    }

    @Test
    fun `does not undo a hide the user asked for`() {
        assertFalse(heal(OverlaySelfHealPolicy(), nowMs = 1_000, dismissed = true))
    }

    @Test
    fun `retries are spaced out`() {
        val policy = OverlaySelfHealPolicy(minRetryMs = 5_000)
        assertTrue(heal(policy, nowMs = 0))
        assertFalse("too soon for the next attempt", heal(policy, nowMs = 4_999))
        assertTrue("spacing elapsed", heal(policy, nowMs = 5_000))
    }

    @Test
    fun `a bubble that is back reclaims the retry slot`() {
        val policy = OverlaySelfHealPolicy(minRetryMs = 5_000)
        assertTrue(heal(policy, nowMs = 0))
        policy.onShowing()
        assertTrue("no spacing left over from the previous loss", heal(policy, nowMs = 1))
    }
}
