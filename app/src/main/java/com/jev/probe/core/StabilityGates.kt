package com.jev.probe.core

/**
 * Timing decisions for "should the floating bubble be on screen right now?",
 * kept as pure Kotlin with no Android imports so they are covered by JVM unit
 * tests ([StabilityGatesTest]).
 *
 * Both gates come from what long real-device use on MIUI / HyperOS exposed
 * (issues #20 and #18): the bubble had two ways of going away and staying away.
 *
 * - It was hidden on the *first* accessibility event whose active window was not
 *   a chat app. A heads-up notification, the shade edge, a gesture hint or any
 *   window mid-animation briefly becomes the active window
 *   (com.android.systemui), so a single swipe could take the bubble away.
 * - Once hidden, nothing brought it back: recovery waited for the next
 *   accessibility event, and a chat screen that just sits there produces none.
 *   On an aggressive OEM build that freezes and restarts the process the user
 *   was simply left without a bubble until they scrolled.
 *
 * [ForegroundLeaveGate] fixes the first (confirm before hiding) and
 * [OverlaySelfHealPolicy] the second (a timer that puts the bubble back).
 */

/**
 * "The foreground is no longer a chat app" -> hide the bubble, but only once
 * that has held for [confirmMs].
 *
 * Feed it every observation: [onChatApp] when the chat app (or our own overlay)
 * is in front, [onForeign] with the current elapsed-realtime when a window the
 * bubble does not belong on is in front. [shouldHide] answers whether the
 * confirmation window has elapsed; [remainingMs] says how long is left, so the
 * caller can arm exactly one re-check instead of polling.
 *
 * A chat-app observation clears the pending hide immediately, and switching to a
 * different foreign package restarts the window: a swipe that drags the shade
 * over the screen produces a burst of different windows and must never add up to
 * "the user left the chat".
 */
class ForegroundLeaveGate(private val confirmMs: Long) {

    private var foreignPkg: String? = null
    private var foreignSinceMs = 0L

    /** The foreground is a chat app we adapt (or our own window). */
    fun onChatApp() { foreignPkg = null }

    /** [pkg] — a window the bubble does not belong on — is in front at [nowMs]. */
    fun onForeign(pkg: String, nowMs: Long) {
        if (foreignPkg != pkg) {
            foreignPkg = pkg
            foreignSinceMs = nowMs
        }
    }

    /** True only once that window has stayed in front for the whole window. */
    fun shouldHide(nowMs: Long): Boolean =
        foreignPkg != null && nowMs - foreignSinceMs >= confirmMs

    /** Milliseconds until [shouldHide] flips; null when nothing is pending. */
    fun remainingMs(nowMs: Long): Long? =
        foreignPkg?.let { (foreignSinceMs + confirmMs - nowMs).coerceAtLeast(0L) }

    fun reset() { foreignPkg = null }
}

/**
 * When the self-heal timer should put the bubble back.
 *
 * It restores the bubble only where the bubble *belongs* and simply is not: the
 * master switch is on, the foreground is a chat app we adapt, the conversation
 * whitelist allows this chat, and the window is not on screen. Our own screens,
 * the launcher and the shade are excluded — there the bubble is supposed to be
 * gone and re-adding it would fight the user.
 *
 * A hide the user asked for ("隐藏助手（本次）", passed as [shouldHeal]'s
 * `dismissedByUser`) is never healed: this backstop exists to undo a bubble the
 * system dropped, not a decision the user made.
 *
 * [minRetryMs] keeps a failing re-show (no overlay permission, a window the
 * system keeps refusing) from turning into a once-per-tick loop.
 */
class OverlaySelfHealPolicy(private val minRetryMs: Long = DEFAULT_MIN_RETRY_MS) {

    private var lastAttemptMs: Long? = null

    fun shouldHeal(
        enabled: Boolean,
        inAdaptedChatApp: Boolean,
        allowed: Boolean,
        showing: Boolean,
        dismissedByUser: Boolean,
        nowMs: Long
    ): Boolean {
        if (!enabled || !inAdaptedChatApp || !allowed || showing || dismissedByUser) return false
        val last = lastAttemptMs
        if (last != null && nowMs - last < minRetryMs) return false
        lastAttemptMs = nowMs
        return true
    }

    /** The bubble is up again: drop the spacing so the next loss heals at once. */
    fun onShowing() { lastAttemptMs = null }

    companion object {
        /** Long enough not to hammer a denied overlay permission, short enough
         *  that a bubble the system dropped is back before the user notices. */
        const val DEFAULT_MIN_RETRY_MS = 5_000L
    }
}
