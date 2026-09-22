package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
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
import com.jev.probe.core.Analysis
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
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
    private val adapters = listOf(WeChatAdapter(), QQAdapter(), XAdapter(), FeishuAdapter()).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private data class Input(val key: ConversationKey, val snapshot: ChatSnapshot, val relationship: String)
    private val analyses = LatestAnalysis<Input>()
    private var currentKey: ConversationKey? = null
    private var screenRevision = 0L
    private var destroyed = false
    private val debounce = Runnable { runAnalysis() }
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { queueAnalysis(it); runAnalysis() }
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
        if (!prefs.enabled) { invalidateConversation(); overlay?.hide(); return }

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
            if (fg != null && fg !in adapters) {
                invalidateConversation()
                foregroundPkg = fg
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                main.post { if (drop) overlay?.hide() else overlay?.showIdle(null) }
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
        val root = rootInActiveWindow ?: run { invalidateConversation(); return }
        val pkg = root.packageName?.toString()
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: run { invalidateConversation(); return }
        // Only act inside a chat window (the adapter returns null elsewhere).
        val rawSnapshot = adapter.extract(root, resources) ?: run { invalidateConversation(); return }
        val title = ConversationKey.confirmedTitle(rawSnapshot.title)
        if (title == null) {
            invalidateConversation()
            overlay?.showIdle(null)
            return
        }
        val snapshot = rawSnapshot.copy(title = title)
        if (!prefs.isAllowed(title)) { invalidateConversation(); overlay?.hide(); return }
        observeConversation(ConversationKey(pkg ?: "", root.windowId, title))
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
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
        if (analyses.observe(currentKey!!, sig)) main.removeCallbacks(debounce)
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) { main.post { overlay?.showIdle(snapshot.title) }; return }
        // Anything else reaching here is a genuinely different conversation (new
        // app, or new content in this one) — a leftover judgment/candidates from
        // whatever was shown before must not leak into it.
        main.post { overlay?.resetForNewConversation() }
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            main.post { overlay?.showIdle(snapshot.title) }; return
        }

        queueAnalysis(snapshot)
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    private fun isTransientTitle(t: String?) = ConversationKey.confirmedTitle(t) == null

    private fun invalidateConversation() {
        screenRevision++
        analyses.invalidate()
        main.removeCallbacks(debounce)
        currentKey = null
        currentSnapshot = null
        activePkg = null
        lastSignature = ""
        lastOcrSignature = ""
        overlay?.resetForNewConversation()
    }

    private fun observeConversation(key: ConversationKey) {
        if (key == currentKey) return
        invalidateConversation()
        currentKey = key
        activePkg = key.pkg
    }

    private fun queueAnalysis(snapshot: ChatSnapshot) {
        val key = currentKey ?: return
        analyses.observe(key, snapshot.signature())
        analyses.enqueue(Input(key, snapshot, prefs.relationship))
    }

    /** Re-read the foreground tree: callbacks can beat the next accessibility event. */
    private fun matchesForeground(key: ConversationKey, root: AccessibilityNodeInfo? = rootInActiveWindow): Boolean {
        if (destroyed || !prefs.enabled || !prefs.isAllowed(key.title)) return false
        root ?: return false
        if (root.packageName?.toString() != key.pkg || root.windowId != key.windowId) return false
        val adapter = adapters[key.pkg] ?: return key == currentKey
        val snapshot = adapter.extract(root, resources) ?: return false
        return ConversationKey.confirmedTitle(snapshot.title) == key.title
    }

    private fun accepts(request: LatestAnalysis.Request<Input>, root: AccessibilityNodeInfo? = rootInActiveWindow): Boolean {
        if (!analyses.isCurrent(request) || !matchesForeground(request.value.key, root)) return false
        root ?: return false
        val live = adapters[request.value.key.pkg]?.extract(root, resources)
        return live == null || live.messages.isEmpty() || request.value.snapshot.note != null ||
            live.signature() == request.value.snapshot.signature()
    }

    private fun runAnalysis() {
        if (destroyed || !analyses.hasPending) return
        if (!prefs.hasKey()) { overlay?.showError("未设置判断接口密钥，去设置里填"); return }
        val request = analyses.start() ?: return
        if (!accepts(request)) {
            analyses.finish(request, LatestAnalysis.Part.JUDGMENT)
            analyses.finish(request, LatestAnalysis.Part.REPLIES)
            return
        }
        val (key, snapshot, rel) = request.value
        overlay?.resetForNewConversation()
        overlay?.showLoading()
        overlay?.setNote(snapshot.note)
        val client = JevClient(prefs)
        // Main-thread result assembly supports either completion order, including failures.
        var judgment: Analysis? = null
        var replies: List<RankedReply>? = null
        var replyError: String? = null
        fun render() {
            if (!accepts(request)) return
            val a = judgment ?: return
            if (a.error != null) { overlay?.showError(a.error); return }
            overlay?.showJudgment(a)
            replies?.let { ranked ->
                overlay?.showReplies(ranked, replyError) { text -> fillInput(text, request) }
            }
        }
        // Knowledge context first (local file reads only, a few ms), then the two
        // network calls in parallel on the pool. A failure here must never stop
        // the analysis — it just means no extra context this round.
        submit {
            val ctx = try {
                if (key.title == null) null else ContextBuilder.build(this, snapshot, key.pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post { if (accepts(request)) overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0) }

            // Judgment is fast (~1s) — show it immediately.
            submit {
                val result = client.judge(snapshot, rel, ctx)
                main.post {
                    judgment = result
                    render()
                    analyses.finish(request, LatestAnalysis.Part.JUDGMENT)
                    runAnalysis()
                }
            }
            // Candidate replies are slower (generative + rank) — fill in when ready.
            submit {
                var error: String? = null
                val ranked = try { client.draftAndRank(snapshot, rel, ctx) } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                    emptyList()
                }
                main.post {
                    replies = ranked
                    replyError = error
                    render()
                    analyses.finish(request, LatestAnalysis.Part.REPLIES)
                    runAnalysis()
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
        // Only an adapter-confirmed title can identify a contact.
        if (root == null) return
        val title = ConversationKey.confirmedTitle(adapters[pkg]?.extract(root, resources)?.title)
        // Unadapted screens have no verified conversation identity: no contact context or autofill.
        observeConversation(ConversationKey(pkg, root.windowId, title))
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
        return pkg + "|" + (title ?: "") + "|" + rects.joinToString(";") { br ->
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
        if (ocrBusy) return
        val key = currentKey ?: return
        val revision = ++screenRevision
        analyses.invalidate()
        main.removeCallbacks(debounce)
        currentSnapshot = null
        lastSignature = ""
        overlay?.resetForNewConversation()
        ocrBusy = true
        screenCapture.capture { res ->
            if (revision != screenRevision || !matchesForeground(key)) {
                if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                ocrBusy = false
                lastOcrSignature = ""
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this screen
                    // is done — the next event may retry, still held back by
                    // ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not something
                    // the user can act on — nagging about them would be constant.
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
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg, revision)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual, revision)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, revision: Long) {
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
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false, revision = revision)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean, revision: Long) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            finishOcrSnapshot(ChatSnapshot(treeTitle, msgs, note = OCR_NOTE), pkg, manual, revision)
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
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, revision: Long) {
        ocrBusy = false
        val key = currentKey
        if (revision != screenRevision || key == null || !matchesForeground(key)) {
            lastOcrSignature = ""
            return
        }
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        if (analyses.observe(key, sig)) main.removeCallbacks(debounce)
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        overlay?.resetForNewConversation()
        lastSignature = sig

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (manual || auto) {
            queueAnalysis(snapshot)
            main.removeCallbacks(debounce)
            runAnalysis()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Fill the chat input box with the chosen reply (never sends). */
    private fun fillInput(text: String, request: LatestAnalysis.Request<Input>) {
        // Each target action fetches a fresh node, after checking the live foreground.
        val target = object : ReplyFiller.Target {
            override fun isCurrent() = accepts(request)
            private fun edit(): AccessibilityNodeInfo? {
                val root = rootInActiveWindow ?: return null
                return if (accepts(request, root)) findEditable(root) else null
            }
            override fun read(): String? = edit()?.let {
                runCatching { it.refresh() }
                it.text?.toString()
            }
            override fun set(text: String) = edit()?.let { setTextRaw(it, text) } ?: false
            override fun focus() = edit()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            override fun paste() = edit()?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
            override fun copy(text: String) { if (isCurrent()) copyToClipboard(text) }
            override fun toast(message: String) { overlay?.toast(message) }
        }
        ReplyFiller(target) { delay, action -> main.postDelayed({ action() }, delay) }
            .fill(text, request.value.key.title != null && request.value.key.pkg in adapters)
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

    override fun onInterrupt() { invalidateConversation(); overlay?.hide() }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        invalidateConversation()
        main.removeCallbacksAndMessages(null)
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

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

    }
}
