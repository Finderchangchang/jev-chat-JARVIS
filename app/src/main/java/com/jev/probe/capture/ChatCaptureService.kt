package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name. */
    private val adapters = listOf(WeChatAdapter(), QQAdapter(), FeishuAdapter()).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
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
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable. Our own app
        // (com.jev.probe) is not an adapted package, so it hides too.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg != null && fg !in adapters) {
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
        val pkg = root.packageName?.toString()
        val adapter = adapters[pkg] ?: return
        // Only act inside a chat window (the adapter returns null elsewhere).
        val snapshot = adapter.extract(root, resources) ?: return
        if (snapshot.messages.isEmpty()) return
        if (!prefs.isAllowed(snapshot.title)) { main.post { overlay?.hide() }; return }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) { main.post { overlay?.showIdle(snapshot.title) }; return }
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

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

    /** Fill the chat input box with the chosen reply (never sends). */
    private fun fillInput(text: String) {
        submit {
            // Fast path: SET_TEXT works when the box already has input focus and no
            // IME composing session is active.
            var ok = trySetText(text)
            if (!ok) {
                // Otherwise focus the box (pops the keyboard) and retry SET_TEXT;
                // if the IME composing region still swallows it (WeChat), PASTE from
                // the clipboard. The box is cleared before PASTE so a SET_TEXT that
                // silently took (but failed verification) never gets doubled.
                // Never clicks send.
                val edit = rootInActiveWindow?.let { findEditable(it) }
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(300)
                    ok = trySetText(text)
                    if (!ok) {
                        copyToClipboard(text)
                        val focused = rootInActiveWindow?.let { findEditable(it) } ?: edit
                        setTextRaw(focused, "")
                        val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Thread.sleep(150)
                        val after = readInput()
                        ok = (after != null && after.contains(text)) || (pasted && after == null)
                        Log.i(TAG, "fill: paste=$pasted readback=${after?.length ?: -1}")
                    }
                }
            }
            main.post {
                if (ok) overlay?.toast("已填入，确认后自己发送")
                else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
            }
        }
    }

    /** Set text on the chat input box, verifying it actually took. */
    private fun trySetText(text: String): Boolean {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return false
        if (!setTextRaw(edit, text)) return false
        // SET_TEXT can report success without filling an unfocused box; verify.
        // Read back through refresh() — the node cache can still hold the old
        // (empty) text right after the action, which made Feishu look like a
        // failure and triggered a second PASTE on top.
        Thread.sleep(150)
        val after = readInput()
        Log.i(TAG, "fill: setText readback=${after?.length ?: -1} want=${text.length}")
        return after == text
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Current text of the input box, fetched fresh (bypassing the node cache). */
    private fun readInput(): String? {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return null
        runCatching { edit.refresh() }
        return edit.text?.toString()
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
    }
}
