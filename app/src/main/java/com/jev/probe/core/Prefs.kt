package com.jev.probe.core

import android.content.Context

/**
 * App-private config store. Holds the OpenRouter key, model choices, the
 * relationship description used in Jev's state, and the conversation whitelist.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Hardening to EncryptedSharedPreferences is
 * a follow-up; on the user's own device app-private storage is the MVP bar.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("jev_assistant", Context.MODE_PRIVATE)

    var openRouterKey: String
        get() = sp.getString(K_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_KEY, v.trim()).apply()

    /** Generative model for drafting the 3 candidate replies (OpenRouter chat). */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    /** Free-text describing who the other person is; goes into Jev's state. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    // ---- 飞书消息源（开放平台 API，只读） ----

    /** 飞书消息源开关（轮询飞书开放平台）。 */
    var feishuEnabled: Boolean
        get() = sp.getBoolean(K_FEISHU_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_FEISHU_ENABLED, v).apply()

    /** 飞书自建应用 App ID。 */
    var feishuAppId: String
        get() = sp.getString(K_FEISHU_APP_ID, "") ?: ""
        set(v) = sp.edit().putString(K_FEISHU_APP_ID, v.trim()).apply()

    /** 飞书自建应用 App Secret（与 OpenRouter key 同级：App 私有存储，不出设备、不进日志）。 */
    var feishuAppSecret: String
        get() = sp.getString(K_FEISHU_APP_SECRET, "") ?: ""
        set(v) = sp.edit().putString(K_FEISHU_APP_SECRET, v.trim()).apply()

    /** 我自己的飞书 open_id，用于区分消息是谁发的；留空则除机器人外都算对方。 */
    var feishuMyOpenId: String
        get() = sp.getString(K_FEISHU_MY_OPEN_ID, "") ?: ""
        set(v) = sp.edit().putString(K_FEISHU_MY_OPEN_ID, v.trim()).apply()

    /** 轮询间隔（秒），10..300。 */
    var feishuPollSec: Int
        get() = sp.getInt(K_FEISHU_POLL_SEC, DEFAULT_FEISHU_POLL_SEC).coerceIn(10, 300)
        set(v) = sp.edit().putInt(K_FEISHU_POLL_SEC, v.coerceIn(10, 300)).apply()

    /** 飞书会话白名单：群名包含关键词才监控；空 = 机器人所在的所有群。 */
    var feishuWhitelist: Set<String>
        get() = sp.getStringSet(K_FEISHU_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_FEISHU_WHITELIST, v).apply()

    fun isFeishuAllowed(chatName: String?): Boolean {
        val wl = feishuWhitelist
        if (wl.isEmpty()) return true
        if (chatName == null) return false
        return wl.any { chatName.contains(it) }
    }

    fun hasFeishuCreds(): Boolean = feishuAppId.isNotBlank() && feishuAppSecret.isNotBlank()

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    fun hasKey(): Boolean = openRouterKey.isNotBlank()

    companion object {
        private const val K_KEY = "openrouter_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"
        private const val K_FEISHU_ENABLED = "feishu_enabled"
        private const val K_FEISHU_APP_ID = "feishu_app_id"
        private const val K_FEISHU_APP_SECRET = "feishu_app_secret"
        private const val K_FEISHU_MY_OPEN_ID = "feishu_my_open_id"
        private const val K_FEISHU_POLL_SEC = "feishu_poll_sec"
        private const val K_FEISHU_WHITELIST = "feishu_whitelist"

        // Reply drafting model on OpenRouter. DeepSeek is region-available in CN,
        // strong in Chinese, and cheap (Gemini/OpenAI are region-blocked here).
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"

        /** 飞书轮询默认间隔（秒）。 */
        const val DEFAULT_FEISHU_POLL_SEC = 20
    }
}
