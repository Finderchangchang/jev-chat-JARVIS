package com.jev.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.VisionClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")

    /** Selected provider index per card, held so Save can read it back. */
    private var judgeProviderIdx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened judgeKey.len=${prefs.judgeKey.length}" +
            " replyKey.len=${prefs.replyKey.length} visionKey.len=${prefs.visionKey.length}")
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 接口 ===================
        root.addView(section("接口"))

        // --- 判断接口（Jev） ---
        val judgeCard = card()
        judgeCard.addView(cardTitle("判断接口（Jev）"))
        judgeCard.addView(text("读对方消息、给意图判断和候选排序。必须配置。", 12f, sub))

        val judgeBaseEdit = edit(prefs.judgeBaseUrl, Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
        val judgeModelEdit = edit(prefs.judgeModel, Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
        judgeProviderIdx = when (prefs.judgeProvider) {
            Prefs.PROVIDER_TYPESAFE -> 1
            Prefs.PROVIDER_CUSTOM -> 2
            else -> 0
        }
        judgeCard.addView(pills(
            listOf("OpenRouter", "TypeSafe 直连", "自定义"), judgeProviderIdx) { idx ->
            judgeProviderIdx = idx
            when (idx) {
                0 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
                }
                1 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_TYPESAFE)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE)
                }
                // custom: leave whatever the user typed
            }
        })
        judgeCard.addView(label("Base URL"))
        judgeCard.addView(judgeBaseEdit)
        judgeCard.addView(text("OpenRouter 拼 /alpha/decisions；TypeSafe 拼 /v1/systemone；自定义按原样 POST。",
            11f, sub))
        judgeCard.addView(label("密钥"))
        judgeCard.addView(edit(prefs.judgeKey, "sk-...", password = true).also { judgeKeyEdit = it })
        judgeCard.addView(label("模型"))
        judgeCard.addView(judgeModelEdit)
        val judgeResult = resultText()
        judgeCard.addView(cardBtn("测试判断") {
            val base = judgeBaseEdit.text.toString().trim()
            val key = judgeKeyEdit.text.toString().trim()
            val model = judgeModelEdit.text.toString().trim()
            if (key.isBlank()) { judgeResult.text = "请先填密钥"; return@cardBtn }
            judgeResult.text = "测试中…"
            val probe = draftPrefs {
                judgeProvider = providerOf(judgeProviderIdx)
                judgeBaseUrl = base
                judgeKey = key
                judgeModel = model.ifBlank { Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER }
            }
            worker.execute {
                val t0 = System.currentTimeMillis()
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在")))
                val a = JudgeClient(probe).judge(demo, prefs.relationship)
                val ms = System.currentTimeMillis() - t0
                main.post {
                    judgeResult.text = if (a.error != null) "失败（${ms}ms）：${a.error}"
                    else "成功 ${ms}ms · 意图=${a.trueIntent?.choice ?: "?"}" +
                        "（置信 ${pct(a.trueIntent?.confidence)}）"
                }
            }
        })
        judgeCard.addView(judgeResult)
        root.addView(judgeCard)

        // --- 回复接口 ---
        val replyCard = card()
        replyCard.addView(cardTitle("回复接口"))
        replyCard.addView(text("生成 3 条候选回复。任何 OpenAI 兼容地址，填到 /v1 为止。", 12f, sub))

        val replyBaseEdit = edit(prefs.replyBaseUrl, Prefs.DEFAULT_REPLY_BASE)
        val replyModelEdit = edit(prefs.replyModel, Prefs.DEFAULT_REPLY_MODEL)
        val replyIdx = when (prefs.replyBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_REPLY_BASE -> 0
            Prefs.DEEPSEEK_BASE -> 1
            Prefs.DASHSCOPE_BASE -> 2
            else -> 3
        }
        replyCard.addView(pills(
            listOf("OpenRouter", "DeepSeek 官方", "通义兼容", "自定义"), replyIdx) { idx ->
            when (idx) {
                0 -> { replyBaseEdit.setText(Prefs.DEFAULT_REPLY_BASE); replyModelEdit.setText(Prefs.DEFAULT_REPLY_MODEL) }
                1 -> { replyBaseEdit.setText(Prefs.DEEPSEEK_BASE); replyModelEdit.setText(Prefs.DEEPSEEK_MODEL) }
                2 -> { replyBaseEdit.setText(Prefs.DASHSCOPE_BASE); replyModelEdit.setText(Prefs.DASHSCOPE_MODEL) }
            }
        })
        replyCard.addView(label("Base URL"))
        replyCard.addView(replyBaseEdit)
        replyCard.addView(label("密钥"))
        replyCard.addView(edit(prefs.replyKey, "留空则用判断接口密钥", password = true).also { replyKeyEdit = it })
        replyCard.addView(label("模型"))
        replyCard.addView(replyModelEdit)
        val replyResult = resultText()
        replyCard.addView(cardBtn("测试回复") {
            val base = replyBaseEdit.text.toString().trim()
            val model = replyModelEdit.text.toString().trim()
            val probe = draftPrefs {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = base.ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                replyModel = model.ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            }
            if (probe.effectiveReplyKey().isBlank()) { replyResult.text = "请先填密钥（或填判断接口密钥）"; return@cardBtn }
            replyResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    ReplyClient(probe).summarize("请只回复两个字：收到")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    replyResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        replyCard.addView(replyResult)
        root.addView(replyCard)

        // --- 视觉接口 ---
        val visionCard = card()
        visionCard.addView(cardTitle("视觉接口（OCR 用，可先不填）"))
        visionCard.addView(text("读不到控件树的 App 走截图识别。B 阶段才用到，现在填不填都不影响。", 12f, sub))

        val visionBaseEdit = edit(prefs.visionBaseUrl, Prefs.DEFAULT_VISION_BASE)
        val visionModelEdit = edit(prefs.visionModel, Prefs.DEFAULT_VISION_MODEL)
        val visionIdx = when (prefs.visionBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_VISION_BASE -> 0
            Prefs.DASHSCOPE_BASE -> 1
            else -> 2
        }
        visionCard.addView(pills(
            listOf("OpenRouter", "通义兼容", "自定义"), visionIdx) { idx ->
            when (idx) {
                0 -> { visionBaseEdit.setText(Prefs.DEFAULT_VISION_BASE); visionModelEdit.setText(Prefs.DEFAULT_VISION_MODEL) }
                1 -> { visionBaseEdit.setText(Prefs.DASHSCOPE_BASE); visionModelEdit.setText(Prefs.DASHSCOPE_VISION_MODEL) }
            }
        })
        visionCard.addView(label("Base URL"))
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("密钥"))
        visionCard.addView(edit(prefs.visionKey, "留空则用回复接口密钥", password = true).also { visionKeyEdit = it })
        visionCard.addView(label("模型"))
        visionCard.addView(visionModelEdit)
        val visionResult = resultText()
        visionCard.addView(cardBtn("测试视觉") {
            val visionBase = visionBaseEdit.text.toString().trim()
            if (!VisionClient.supportsVision(visionBase.ifBlank { Prefs.DEFAULT_VISION_BASE })) {
                visionResult.text = GUARD_NO_VISION
                return@cardBtn
            }
            val probe = draftPrefs {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                visionBaseUrl = visionBase
                visionKey = visionKeyEdit.text.toString().trim()
                visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }
            }
            if (probe.effectiveVisionKey().isBlank()) { visionResult.text = "请先填密钥（或填回复/判断接口密钥）"; return@cardBtn }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        visionCard.addView(visionResult)
        root.addView(visionCard)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述（给 Jev 判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)
        root.addView(card2)

        // =================== 外观 ===================
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            prefs.judgeProvider = providerOf(judgeProviderIdx)
            prefs.judgeBaseUrl = judgeBaseEdit.text.toString().trim()
                .ifBlank { Prefs.DEFAULT_JUDGE_BASE_OPENROUTER }
            prefs.judgeKey = judgeKeyEdit.text.toString()
            prefs.judgeModel = judgeModelEdit.text.toString().trim()
                .ifBlank { Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER }

            prefs.replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
            prefs.replyKey = replyKeyEdit.text.toString()
            prefs.replyModel = replyModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }

            prefs.visionBaseUrl = visionBaseEdit.text.toString().trim()
            prefs.visionKey = visionKeyEdit.text.toString()
            prefs.visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }

            prefs.relationship = relEdit.text.toString()   // blank stays blank, on purpose
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    // Held as fields because several test buttons read each other's key box.
    private lateinit var judgeKeyEdit: EditText
    private lateinit var replyKeyEdit: EditText
    private lateinit var visionKeyEdit: EditText

    private fun providerOf(idx: Int) = when (idx) {
        1 -> Prefs.PROVIDER_TYPESAFE
        2 -> Prefs.PROVIDER_CUSTOM
        else -> Prefs.PROVIDER_OPENROUTER
    }

    /**
     * A throwaway [Prefs] view carrying exactly what is in the boxes right now,
     * so a test button probes the typed values rather than the saved ones. It
     * writes to a separate scratch SharedPreferences file, never the real one.
     */
    private fun draftPrefs(fill: Prefs.() -> Unit): Prefs {
        getSharedPreferences(SCRATCH, MODE_PRIVATE).edit().clear().commit()
        return Prefs(ScratchContext(this)).apply(fill)
    }

    /** Redirects every SharedPreferences lookup to the scratch file. */
    private class ScratchContext(base: android.content.Context) :
        android.content.ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences =
            super.getSharedPreferences(SCRATCH, mode)
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return VisionClient.encodeJpeg(bmp)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else sub)
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = round(dp(9), if (on) accent else pillOff)
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** DeepSeek's official API has no vision model; say so instead of a 400. */
        private const val GUARD_NO_VISION =
            "该接口不支持视觉（DeepSeek 官方没有 image_url），请换 OpenRouter 或通义兼容"

        /** Scratch prefs file the test buttons probe with; never the real config. */
        private const val SCRATCH = "jev_probe_scratch"

    }
}
