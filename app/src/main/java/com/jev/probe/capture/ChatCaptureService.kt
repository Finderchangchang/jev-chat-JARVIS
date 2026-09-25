package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.Future
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

    /** Adapted chat apps, keyed by package name.
     *  WeChat is intentionally NOT wired in: reading it (node tree / screenshot /
     *  OCR) is what trips WeChat's anti-screenshot risk control, so it is fully
     *  disabled and handled by a short-circuit notice instead of an adapter (see
     *  [maybeCapture] / [onAccessibilityEvent]). [WeChatAdapter] is kept in the
     *  codebase for a possible future restore, just not used here. */
    private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter()).associateBy { it.pkg }

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
    private val session = ConversationSession()
    private val analysisTasks = ArrayList<Future<*>>()
    private var destroyed = false
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "whitelist") {
            main.post {
                leaveConversation()
                overlay?.hide()
            }
        }
    }

    /** Invalidate callbacks before cancelling workers; interruption alone is not a guard. */
    private fun cancelAnalysis() {
        session.invalidate()
        main.removeCallbacks(debounce)
        pendingSnapshot = null
        analyzing = false
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
        overlay?.resetForNewConversation()
    }

    private fun observeTarget(target: ConversationSession.Target?) {
        if (session.observe(target)) {
            cancelAnalysis()
            currentSnapshot = null
            activePkg = null
            lastSignature = ""
            lastOcrSignature = ""
        }
    }

    private fun leaveConversation() {
        observeTarget(null)
        cancelAnalysis()
        currentSnapshot = null
    }

    /** Read the live target, never the previous chat's cached/stabilized title. */
    private fun targetFor(root: AccessibilityNodeInfo): ConversationSession.Target? {
        val pkg = root.packageName?.toString() ?: return null
        if (pkg == PKG_WECHAT || pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("launcher", true) || pkg == "com.miui.home") return null
        val adapter = adapters[pkg]
        var messagesSignature: String? = null
        val title = if (adapter != null) {
            val snapshot = adapter.extract(root, resources) ?: return null
            messagesSignature = snapshot.takeIf { it.messages.isNotEmpty() }?.signature()
            // A loading/unknown title cannot prove which conversation is open.
            snapshot.title?.takeUnless { isTransientTitle(it) } ?: return null
        } else {
            findTitleInActionBar(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
                resources, 0.15, 0.85)
        }
        return ConversationSession.Target(pkg, root.windowId, title, messagesSignature)
    }

    private fun isCurrent(token: ConversationSession.Token): Boolean {
        if (destroyed || !prefs.enabled || !session.accepts(token)) return false
        val live = rootInActiveWindow?.let { targetFor(it) }
        if (live != token.target || !prefs.isAllowed(currentSnapshot?.title ?: live.title)) {
            leaveConversation()
            overlay?.hide()
            return false
        }
        return true
    }

    /** Only called on the main thread, including the context-completion callback. */
    private fun submitAnalysis(task: () -> Unit) {
        try { analysisTasks.add(worker.submit(task)) } catch (_: RejectedExecutionException) { }
    }

    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    /** WeChat is fully disabled — we never read it, so instead of a signature we
     *  just track whether the "WeChat not supported" notice has been shown for
     *  the current WeChat visit. Reset to false whenever a non-WeChat foreground
     *  is seen, so it re-appears next visit but does not re-pop on every event. */
    private var wechatNoticeShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { leaveConversation(); overlay?.hide(); return }

        val type = event.eventType
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            // WeChat is fully disabled: never read/screenshot/OCR/fill here, only
            // show the one-time "not supported" notice and stop. Checked before the
            // generic no-adapter branch because WeChat is no longer in `adapters`.
            if (fg == PKG_WECHAT) { foregroundPkg = fg; showWeChatDisabled(auto = true); return }
            if (fg != null && fg !in adapters) {
                val target = rootInActiveWindow?.let { targetFor(it) }
                if (session.target != target) leaveConversation()
                foregroundPkg = fg
                wechatNoticeShown = false // left WeChat → allow the notice again next visit
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                if (drop) overlay?.hide() else overlay?.showIdle(null)
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
        val root = rootInActiveWindow ?: run { leaveConversation(); overlay?.hide(); return }
        val pkg = root.packageName?.toString()
        // WeChat is fully disabled — no tree read, no screenshot, no OCR, no fill.
        // A content-changed / scrolled event in WeChat only re-shows the one-time
        // notice (deduped); it must never reach an adapter or the OCR path.
        if (pkg == PKG_WECHAT) { showWeChatDisabled(auto = true); return }
        wechatNoticeShown = false // any other foreground → allow the notice again next WeChat visit
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: run {
            if (session.target != null && session.target != targetFor(root)) leaveConversation()
            return
        }
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val rawSnapshot = adapter.extract(root, resources)
        if (rawSnapshot == null) { leaveConversation(); overlay?.showIdle(null); return }
        val target = targetFor(root)
        if (target == null) { leaveConversation(); overlay?.showIdle(null); return }
        observeTarget(target)
        // Use only this window's title; never inherit another conversation's title.
        val snapshot = rawSnapshot
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            if (prefs.ocrFallback) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                if (ocrBusy) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

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
        if (sig == lastSignature && !showing) { overlay?.showIdle(snapshot.title); return }
        // Anything else reaching here is a genuinely different conversation (new
        // app, or new content in this one) — a leftover judgment/candidates from
        // whatever was shown before must not leak into it.
        cancelAnalysis()
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            overlay?.showIdle(snapshot.title); return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    /**
     * Foreground is WeChat, which is fully disabled: no node-tree read, no
     * screenshot, no OCR, no fill. We only surface a one-time notice saying WeChat
     * itself blocks reading. [auto] events (accessibility callbacks)
     * show it once per WeChat visit — [wechatNoticeShown] dedupes them; a manual
     * bubble tap ([auto] = false) always shows it. Never gated by the user's
     * whitelist (it is an info notice, not a read) and only ever reached under
     * the WeChat package.
     */
    private fun showWeChatDisabled(auto: Boolean) {
        leaveConversation()
        if (auto && wechatNoticeShown) return
        wechatNoticeShown = true
        // No popup panel in WeChat — a full card is intrusive when others can see
        // the screen. Take the overlay off WeChat entirely and show the reason
        // once as a small transient toast.
        overlay?.hide()
        overlay?.toast(WECHAT_DISABLED_MSG)
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        if (analyzing || destroyed || !prefs.enabled) return
        val previous = session.token() ?: return
        if (!isCurrent(previous)) return
        if (!prefs.hasKey()) { overlay?.showError("未设置判断接口密钥，去设置里填"); return }
        val token = session.begin() ?: return
        analyzing = true
        overlay?.showLoading()
        overlay?.setNote(snapshot.note)
        val client = JevClient(prefs)
        val rel = prefs.relationship
        submitAnalysis {
            val ctx = try {
                ContextBuilder.build(this, snapshot, token.target.pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                if (!isCurrent(token)) return@post
                overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
                var remaining = 2
                fun completed() {
                    remaining--
                    if (remaining == 0) {
                        analyzing = false
                        analysisTasks.clear()
                    }
                }
                submitAnalysis {
                    val judgment = client.judge(snapshot, rel, ctx)
                    main.post {
                        if (isCurrent(token)) {
                            if (judgment.error != null) overlay?.showError(judgment.error)
                            else overlay?.showJudgment(judgment)
                            completed()
                        }
                    }
                }
                submitAnalysis {
                    var replyError: String? = null
                    val ranked = try { client.draftAndRank(snapshot, rel, ctx) } catch (e: Exception) {
                        replyError = e.message ?: e.javaClass.simpleName
                        emptyList()
                    }
                    main.post {
                        if (isCurrent(token)) {
                            overlay?.showReplies(ranked, replyError) { text -> fillInput(token, text) }
                            completed()
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // WeChat is fully disabled: a manual "截屏识别一次" in WeChat must NOT take
        // a screenshot — just show the notice (a manual tap always shows it).
        if (pkg == PKG_WECHAT) { showWeChatDisabled(auto = false); return }
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        val target = root?.let { targetFor(it) } ?: run {
            overlay?.toast("无法确认当前会话，请等待标题加载后重试")
            return
        }
        observeTarget(target)
        ocrCapture(title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (ocrBusy || destroyed || !prefs.enabled) return
        val token = session.token() ?: return
        if (!isCurrent(token)) return
        ocrBusy = true
        screenCapture.capture(shouldCapture = { isCurrent(token) }) { res ->
            if (!isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                ocrBusy = false
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this
                    // screen is done — the next event may retry, still held
                    // back by ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not
                    // something the user can act on — nagging would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg, token)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual, token)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, token: ConversationSession.Token) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false, token = token)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual, token)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        ocrBusy = false
        if (!isCurrent(token)) return
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        cancelAnalysis()
        lastSignature = sig

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Resolve only the originating chat's input, never an arbitrary foreground editor. */
    private fun inputFor(token: ConversationSession.Token): AccessibilityNodeInfo? {
        if (!isCurrent(token)) return null
        val root = rootInActiveWindow ?: return null
        if (targetFor(root) != token.target) return null
        val input = when (token.target.pkg) {
            "com.tencent.mobileqq" -> root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/input").firstOrNull()
            "com.ss.android.lark" -> root.findAccessibilityNodeInfosByViewId("com.ss.android.lark:id/kb_rich_text_content").firstOrNull()
            "com.twitter.android" -> findEditable(root)
            else -> null // Unknown apps support explicit clipboard copy, not unverified writes.
        }
        // Re-read the node after SET_TEXT: the accessibility cache may still
        // contain the previous draft even though the write already succeeded.
        input ?: return null
        if (!input.refresh()) return null
        return input.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
    }

    /** Fill without blocking the main thread; all retries re-resolve the original target. */
    private fun fillInput(token: ConversationSession.Token, text: String) {
        fun finish(ok: Boolean) {
            if (!isCurrent(token)) return
            if (ok) overlay?.toast("已填入，确认后自己发送")
            else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
        }
        if (!isCurrent(token)) { overlay?.toast("会话已变化，请重新分析后填入"); return }
        if (inputFor(token) == null) { finish(false); return }
        GuardedInputWriter(
            resolve = {
                inputFor(token)?.let { node ->
                    object : GuardedInputWriter.Input {
                        override val text: String? get() = node.text?.toString()
                        override fun setText(text: String) = setTextRaw(node, text)
                        override fun focus() { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        override fun paste() { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    }
                }
            },
            later = { delay, action -> main.postDelayed({ action() }, delay) },
            copy = { copyToClipboard(it) },
            complete = { finish(it) }
        ).fill(text)
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword) {
                if (found != null) return null // Ambiguous editor: clipboard only.
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return if (stack.isEmpty()) found else null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {
        leaveConversation()
        overlay?.hide()
    }

    override fun onDestroy() {
        destroyed = true
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        leaveConversation()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** WeChat's package. Reading it (node tree / screenshot / OCR) is what
         *  trips WeChat's anti-screenshot risk control, so it is fully disabled:
         *  no adapter, no capture, only a one-time "not supported" notice. */
        private const val PKG_WECHAT = "com.tencent.mm"

        /** Shown once when the foreground is WeChat. Plain words, full-width
         *  punctuation; steers the user to a still-supported app. */
        private const val WECHAT_DISABLED_MSG =
            "微信已限制读取，请在别的软件上使用"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
