package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads the open WeChat
 * chat, detects a new incoming message from the other person, runs Jev analysis
 * off the main thread, and drives the floating overlay.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT to fill the
 * WeChat input box when the user taps "填入"; the user still presses send.
 */
open class WeChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var analyzing = false
    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { main.post { overlay?.hide() }; return }

        val type = event.eventType
        // Decide "did we leave WeChat" from the REAL active window, not the event's
        // package. The event package can be an IME (e.g. com.tencent.wetype) or the
        // status bar while WeChat is still foreground — keying off it made the bubble
        // flicker (hide → re-show → hide…). rootInActiveWindow stays WeChat while the
        // keyboard is up, so this is stable.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg != null && fg != WECHAT) {
                foregroundPkg = fg
                main.post { overlay?.hide() }
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        val root = rootInActiveWindow ?: return
        // Only act inside a chat window (has message bubbles).
        val snapshot = extractSnapshot(root) ?: return
        if (snapshot.messages.isEmpty()) return
        if (!prefs.isAllowed(snapshot.title)) { main.post { overlay?.hide() }; return }

        currentSnapshot = snapshot
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) { main.post { overlay?.showIdle(snapshot.title) }; return }
        lastSignature = sig

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            main.post { overlay?.showIdle(snapshot.title) }; return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        if (analyzing) return
        if (!prefs.hasKey()) { main.post { overlay?.showError("未设置 OpenRouter 密钥，去设置里填") }; return }
        analyzing = true
        main.post { overlay?.showLoading() }
        val client = JevClient(prefs.openRouterKey, prefs.replyModel)
        val rel = prefs.relationship
        // Judgment is fast (~1s) — show it immediately.
        submit {
            val judgment = client.judge(snapshot, rel)
            main.post {
                if (judgment.error != null) { analyzing = false; overlay?.showError(judgment.error) }
                else overlay?.showJudgment(judgment)
            }
        }
        // Candidate replies are slower (generative + rank) — fill in when ready.
        submit {
            val ranked = try { client.draftAndRank(snapshot, rel) } catch (e: Exception) { emptyList() }
            main.post {
                analyzing = false
                overlay?.showReplies(ranked) { text -> fillInput(text) }
            }
        }
    }

    /** Walk the tree once, collect chat bubbles (id/bkl) and the title. */
    private fun extractSnapshot(root: AccessibilityNodeInfo): ChatSnapshot? {
        val width = resources.displayMetrics.widthPixels
        val bubbles = ArrayList<Triple<Int, Int, String>>() // top, centerX, text
        var title: String? = null
        var firstBubbleTop = Int.MAX_VALUE

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(Triple(b.top, b.centerX(), text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        if (bubbles.isEmpty()) return null

        // Title: topmost short text above the message area, centered-ish.
        title = findTitle(root, firstBubbleTop, width)

        // Chronological order = top to bottom.
        bubbles.sortBy { it.first }
        val msgs = bubbles.map { (_, cx, text) ->
            val side = if (cx > width / 2) "me" else "other"
            Msg(side, text)
        }
        return ChatSnapshot(title, msgs)
    }

    private fun findTitle(root: AccessibilityNodeInfo, firstBubbleTop: Int, width: Int): String? {
        // The conversation title sits in the top action bar. Constrain to that
        // band (and clear of the message area) so we don't grab an in-chat
        // timestamp like "8月12日 18:57".
        val actionBarMax = minOf(firstBubbleTop, (resources.displayMetrics.heightPixels * 0.14).toInt())
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var best: String? = null
        var bestTop = Int.MAX_VALUE
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.bottom in 1 until actionBarMax && b.centerX() in (width / 4)..(width * 3 / 4)) {
                    if (b.top < bestTop) { bestTop = b.top; best = text }
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }

    private fun looksLikeTimestamp(t: String): Boolean =
        Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(t) ||
            Regex("""\d+月\d+日""").containsMatchIn(t) ||
            t == "昨天" || t == "今天"

    /** Fill the WeChat input box with the chosen reply (never sends). */
    private fun fillInput(text: String) {
        submit {
            // Fast path: SET_TEXT works when the box already has input focus and no
            // IME composing session is active.
            var ok = trySetText(text)
            if (!ok) {
                // Otherwise focus the box (pops the keyboard) and PASTE from the
                // clipboard — robust against WeChat's IME composing region, which
                // makes SET_TEXT silently fail. Never clicks send.
                copyToClipboard(text)
                val edit = rootInActiveWindow?.let { findEditable(it) }
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(300)
                    val focused = rootInActiveWindow?.let { findEditable(it) } ?: edit
                    ok = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    if (!ok) ok = trySetText(text)
                    if (!ok) {
                        val after = rootInActiveWindow?.let { findEditable(it) }?.text?.toString()
                        ok = after == text
                    }
                }
            }
            main.post {
                if (ok) overlay?.toast("已填入，确认后自己发送")
                else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
            }
        }
    }

    /** Set text on the WeChat input box, verifying it actually took. */
    private fun trySetText(text: String): Boolean {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        // SET_TEXT can report success without filling an unfocused box; verify.
        val after = rootInActiveWindow?.let { findEditable(it) }?.text?.toString()
        return after == text
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable) return node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val WECHAT = "com.tencent.mm"
        private const val BUBBLE_ID = "com.tencent.mm:id/bkl"
    }
}
