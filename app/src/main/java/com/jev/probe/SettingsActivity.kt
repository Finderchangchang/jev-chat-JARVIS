package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.feishu.FeishuClient
import com.jev.probe.feishu.FeishuPollerService
import com.jev.probe.jev.JevClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(header("设置"))

        // --- 接口 ---
        root.addView(section("接口"))
        val card1 = card()
        card1.addView(label("OpenRouter 密钥"))
        val keyEdit = edit(prefs.openRouterKey, "sk-or-v1-...", password = true)
        card1.addView(keyEdit)
        card1.addView(label("回复生成模型"))
        val modelEdit = edit(prefs.replyModel, Prefs.DEFAULT_REPLY_MODEL)
        card1.addView(modelEdit)
        root.addView(card1)

        // --- 飞书（可选消息源） ---
        root.addView(section("飞书"))
        val cardF = card()
        val feishuToggle = toggleRow("启用飞书消息源（轮询开放平台）", prefs.feishuEnabled)
        cardF.addView(feishuToggle)
        cardF.addView(label("App ID（自建应用）"))
        val fAppIdEdit = edit(prefs.feishuAppId, "cli_...")
        cardF.addView(fAppIdEdit)
        cardF.addView(label("App Secret"))
        val fSecretEdit = edit(prefs.feishuAppSecret, "应用密钥", password = true)
        cardF.addView(fSecretEdit)
        cardF.addView(label("我的 Open ID（区分谁发的消息，可留空）"))
        val fOpenIdEdit = edit(prefs.feishuMyOpenId, "ou_...")
        cardF.addView(fOpenIdEdit)
        cardF.addView(label("轮询间隔（秒，10–300）"))
        val fPollEdit = edit(prefs.feishuPollSec.toString(), Prefs.DEFAULT_FEISHU_POLL_SEC.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        cardF.addView(fPollEdit)
        cardF.addView(label("会话白名单（群名关键词，每行一个，空=所有群）"))
        val fWlEdit = edit(prefs.feishuWhitelist.joinToString("\n"), "留空则监控机器人所在的所有群").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        cardF.addView(fWlEdit)
        cardF.addView(text(
            "机器人只能读到它所在的群。飞书后台需：开通机器人能力，授予 im:chat:readonly、" +
                "im:message:readonly、im:message.group_msg，发布版本并把机器人加进群。",
            11f, sub
        ))
        root.addView(cardF)

        // --- 分析 ---
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

        // --- 外观 ---
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

        // --- Actions ---
        val result = text("", 13f, sub).apply { setPadding(0, dp(12), 0, dp(4)) }
        root.addView(primaryBtn("保存") {
            prefs.openRouterKey = keyEdit.text.toString()
            prefs.replyModel = modelEdit.text.toString().ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            prefs.relationship = relEdit.text.toString().ifBlank { Prefs.DEFAULT_REL }
            prefs.whitelist = wlEdit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.overlayOpacity = seek.progress + 60
            prefs.feishuEnabled = (feishuToggle.tag as? Boolean) ?: false
            prefs.feishuAppId = fAppIdEdit.text.toString()
            prefs.feishuAppSecret = fSecretEdit.text.toString()
            prefs.feishuMyOpenId = fOpenIdEdit.text.toString()
            prefs.feishuPollSec = fPollEdit.text.toString().toIntOrNull() ?: Prefs.DEFAULT_FEISHU_POLL_SEC
            prefs.feishuWhitelist = fWlEdit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            FeishuPollerService.refresh(this) // 按新配置重启/停止飞书轮询
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })
        root.addView(secondaryBtn("连通测试") {
            val key = keyEdit.text.toString().trim()
            val model = modelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            if (key.isBlank()) { result.text = "请先填密钥"; return@secondaryBtn }
            result.text = "测试中…"
            worker.execute {
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在"), Msg("other", "那你说说昨天答应我的事")))
                val a = JevClient(key, model).analyze(demo, prefs.relationship)
                main.post {
                    result.text = if (a.error != null) "失败：${a.error}"
                    else "成功：意图=${a.trueIntent?.choice ?: "?"}，候选=${a.rankedReplies.size} 条，耗时 ${a.latencyMs}ms"
                }
            }
        })
        root.addView(secondaryBtn("飞书连通测试") {
            val appId = fAppIdEdit.text.toString().trim()
            val secret = fSecretEdit.text.toString().trim()
            if (appId.isBlank() || secret.isBlank()) { result.text = "请先填飞书 App ID / Secret"; return@secondaryBtn }
            result.text = "飞书测试中…"
            worker.execute {
                val msg = try {
                    val c = FeishuClient(appId, secret)
                    val chats = c.listChats()
                    if (chats.isEmpty()) "连接成功，但机器人不在任何群里（把机器人加进群再试）"
                    else {
                        val first = chats.first()
                        val latest = c.latestMessages(first.chatId, 3)
                        val preview = latest.lastOrNull()?.text?.take(30) ?: "（群内暂无消息）"
                        "连接成功：${chats.size} 个群；最近【${first.name}】：$preview"
                    }
                } catch (e: Exception) { "失败：${FeishuClient.readableError(e)}" }
                main.post { result.text = msg }
            }
        })
        root.addView(result)

        setContentView(scroll)
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

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
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

    private fun secondaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(12), Color.WHITE, stroke = true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }
}
