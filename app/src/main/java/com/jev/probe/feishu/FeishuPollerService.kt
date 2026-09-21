package com.jev.probe.feishu

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 飞书消息源：进程优先级由 KeepAliveService 保障，本服务以普通 started service
 * 常驻同一进程，按固定间隔轮询飞书开放平台；发现「对方的新消息」（最新一条不是
 * 自己发的）就走与微信相同的 Jev 分析管线，并驱动共享悬浮窗。
 *
 * 水位机制：每个群记住已处理到的 create_time（毫秒）；首次见到的群只登记水位
 * 不分析（避免把历史消息当成新消息轰炸）。忙时回滚水位，下一轮重试，不丢消息。
 *
 * 红线与微信模式一致：只读消息、只给分析；回复只能「复制」，由用户自己粘贴发
 * 送，绝不代发、绝不调用任何写接口。
 */
class FeishuPollerService : Service() {

    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private val main = Handler(Looper.getMainLooper())
    private var schedHandler: Handler? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)

    /** chatId → 已处理到的 create_time(ms)。 */
    private val watermarks = HashMap<String, Long>()
    @Volatile private var lastSnapshot: ChatSnapshot? = null
    private var consecutiveFailures = 0

    private fun submit(task: () -> Unit) {
        try { exec.execute(task) } catch (_: RejectedExecutionException) { }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = OverlayController.get(this)
        overlay?.setManualHandler(SOURCE) { lastSnapshot?.let { analyzeNow(it) } }
        val ht = HandlerThread("feishu-poll")
        ht.start()
        schedHandler = Handler(ht.looper)
        Log.i(TAG, "feishu poller created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 设置变更后走这里重新生效：清退避、尽快跑一轮。scheduleNext 先移除旧任务，不会叠轮。
        consecutiveFailures = 0
        scheduleNext(800)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        schedHandler?.removeCallbacksAndMessages(null)
        schedHandler?.looper?.quitSafely()
        overlay?.setManualHandler(SOURCE, null)
        main.post { if (overlay?.currentSource == SOURCE) overlay?.hide() }
        exec.shutdownNow()
        Log.i(TAG, "feishu poller destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- polling

    private val tick = Runnable { submit { pollTick() } }

    private fun scheduleNext(delayMs: Long) {
        schedHandler?.removeCallbacks(tick)
        schedHandler?.postDelayed(tick, delayMs)
    }

    private fun pollTick() {
        if (!prefs.enabled || !prefs.feishuEnabled) {
            main.post { if (overlay?.currentSource == SOURCE) overlay?.hide() }
            stopSelf()
            return
        }
        if (!prefs.hasKey() || !prefs.hasFeishuCreds()) {
            // 配置不完整：安静等下一轮（设置页保存后会 refresh 触发立即重试）
            scheduleNext(prefs.feishuPollSec * 1000L)
            return
        }
        val client = FeishuClient(prefs.feishuAppId, prefs.feishuAppSecret)
        try {
            val chats = client.listChats().filter { prefs.isFeishuAllowed(it.name) }
            for (chat in chats) {
                val latest = try {
                    client.latestMessages(chat.chatId, 10)
                } catch (e: Exception) {
                    Log.w(TAG, "poll chat ${chat.name} failed: ${e.message}")
                    continue // 单群失败不拖垮整轮
                }
                if (latest.isEmpty()) continue
                val newMax = latest.maxOf { it.createTimeMs }
                val wm = watermarks[chat.chatId]
                if (wm == null) { watermarks[chat.chatId] = newMax; continue } // 首见：只登记水位
                if (newMax <= wm) continue
                watermarks[chat.chatId] = newMax
                if (!prefs.autoAnalyze) continue
                val snapshot = FeishuMapper.toSnapshot(chat.name, latest, prefs.feishuMyOpenId)
                if (snapshot.latestFrom == "other") {
                    if (!analyzeNow(snapshot)) watermarks[chat.chatId] = wm // 忙：回滚水位，下轮重试
                }
            }
            consecutiveFailures = 0
            scheduleNext(prefs.feishuPollSec * 1000L)
        } catch (e: Exception) {
            consecutiveFailures++
            Log.w(TAG, "feishu poll failed: ${e.message}")
            if (consecutiveFailures == 1) { // 只在失败连击的第一报一次，不刷屏
                main.post {
                    overlay?.currentSource = SOURCE
                    overlay?.showError(FeishuClient.readableError(e))
                }
            }
            val backoff = (prefs.feishuPollSec * (1L shl minOf(consecutiveFailures, 3)))
                .coerceAtMost(MAX_BACKOFF_SEC)
            scheduleNext(backoff * 1000L)
        }
    }

    // ---------------------------------------------------------------- analysis

    /** 走 Jev 分析并驱动悬浮窗。返回 false = 已有分析在跑（调用方应回滚水位）。 */
    private fun analyzeNow(snapshot: ChatSnapshot): Boolean {
        if (!prefs.hasKey()) {
            main.post {
                overlay?.currentSource = SOURCE
                overlay?.sourceTag = tag(snapshot)
                overlay?.showError("未设置 OpenRouter 密钥，去设置里填")
            }
            return true
        }
        if (!analyzing.compareAndSet(false, true)) return false
        lastSnapshot = snapshot
        main.post {
            overlay?.currentSource = SOURCE
            overlay?.sourceTag = tag(snapshot)
            overlay?.showLoading()
        }
        submit {
            val client = JevClient(prefs.openRouterKey, prefs.replyModel)
            val judgment = client.judge(snapshot, prefs.relationship)
            main.post {
                if (judgment.error != null) overlay?.showError(judgment.error)
                else overlay?.showJudgment(judgment)
            }
            val ranked = try { client.draftAndRank(snapshot, prefs.relationship) } catch (_: Exception) { emptyList() }
            main.post {
                analyzing.set(false)
                overlay?.showReplies(ranked) { text -> fill(text) }
            }
        }
        return true
    }

    private fun tag(s: ChatSnapshot) = "飞书 · ${s.title ?: "会话"}"

    /** 飞书模式只复制：粘贴发送永远由人手动完成。 */
    private fun fill(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("jev_reply", text))
        overlay?.toast("已复制，去飞书粘贴发送（助手绝不代发）")
    }

    companion object {
        private const val TAG = "JEVASSIST"
        const val SOURCE = "feishu"
        private const val MAX_BACKOFF_SEC = 300L

        /** 按当前设置启动/停止/刷新轮询（主界面、设置页、保活服务都会调）。 */
        fun refresh(ctx: Context) {
            val p = Prefs(ctx)
            val i = Intent(ctx, FeishuPollerService::class.java)
            runCatching {
                if (p.enabled && p.feishuEnabled && p.hasFeishuCreds()) ctx.startService(i)
                else ctx.stopService(i)
            }
        }
    }
}
