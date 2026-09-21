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

        // Reply drafting model on OpenRouter. DeepSeek is region-available in CN,
        // strong in Chinese, and cheap (Gemini/OpenAI are region-blocked here).
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
