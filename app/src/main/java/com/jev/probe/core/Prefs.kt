package com.jev.probe.core

import android.content.Context
import android.util.Log

/**
 * App-private config store. Holds the three API routes (judge / reply / vision),
 * the relationship description used in Jev's state, the conversation whitelist,
 * plus the context (D stage) and OCR (B stage) switches.
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Only key *lengths* are ever logged.
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * Only the real config migrates — and only the real config logs it. The
     * throwaway instances behind the settings test buttons and the KB self-check
     * have nothing to carry over, and used to print one migration line per tap.
     */
    init { if (prefsName == PREFS_MAIN) { migrateIfNeeded(); unseedBochaDefaultIfUnconfigured() } }

    /**
     * v1.2 -> v1.3: the single `openrouter_key` becomes the judge route's key.
     * `reply_model` keeps its old storage key, so it carries over untouched.
     */
    private fun migrateIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_V13, false)) return   // runs exactly once
        val legacy = sp.getString(K_LEGACY_KEY, "") ?: ""
        val current = sp.getString(K_JUDGE_KEY, "") ?: ""
        val e = sp.edit().putBoolean(K_MIGRATED_V13, true)
        if (current.isBlank() && legacy.isNotBlank()) {
            e.putString(K_JUDGE_KEY, legacy)
            Log.i(TAG, "prefs migrated judgeKey.len=${legacy.length}")
        } else {
            Log.i(TAG, "prefs migrated judgeKey.len=${current.length} (no legacy key to copy)")
        }
        e.apply()
    }

    /**
     * Fresh install only: default the judge route to Bocha Jev (limited-time free).
     * Runs ONLY when there is no judge config whatsoever — the provider key was
     * never written AND both the current judge key and the legacy OpenRouter key
     * are blank. Any existing or migrated user is left completely untouched, so an
     * OpenRouter key can never be redirected to jev.bocha.cn. The [judgeProvider]
     * getter default stays OpenRouter on purpose; this only seeds a truly new sp.
     */
    /**
     * v1.4.0 seeded fresh installs to Bocha Jev; v1.4.1 restores OpenRouter as the
     * default (Bocha stays available, now second in the list). Undo that earlier
     * auto-seed exactly once, and only when the user never entered a key and never
     * picked a provider by hand — a saved key, or any non-Bocha provider, means a
     * real choice we must not touch. Fresh installs now get no seed at all: the
     * getters already default to OpenRouter.
     */
    private fun unseedBochaDefaultIfUnconfigured() {
        if (sp.getBoolean(K_UNSEEDED_BOCHA, false)) return
        val e = sp.edit().putBoolean(K_UNSEEDED_BOCHA, true)
        val prov = sp.getString(K_JUDGE_PROVIDER, null)
        val key = sp.getString(K_JUDGE_KEY, "") ?: ""
        if (prov == PROVIDER_BOCHA && key.isBlank()) {
            e.remove(K_JUDGE_PROVIDER).remove(K_JUDGE_BASE).remove(K_JUDGE_MODEL)
            Log.i(TAG, "prefs: reverted auto-seeded bocha default to openrouter")
        }
        e.apply()
    }

    // ---------------------------------------------------------------- judge

    /** "bocha" | "openrouter" | "typesafe" | "vercel" | "zen" | "custom". */
    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_OPENROUTER) ?: PROVIDER_OPENROUTER
        set(v) = sp.edit().putString(K_JUDGE_PROVIDER, v.trim()).apply()

    /** Host root; the path is appended per provider (see [judgeEndpoint]). */
    var judgeBaseUrl: String
        get() = sp.getString(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_OPENROUTER) ?: DEFAULT_JUDGE_BASE_OPENROUTER
        set(v) = sp.edit().putString(K_JUDGE_BASE, v.trim()).apply()

    var judgeKey: String
        get() = sp.getString(K_JUDGE_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_JUDGE_KEY, v.trim()).apply()

    var judgeModel: String
        get() = sp.getString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_OPENROUTER) ?: DEFAULT_JUDGE_MODEL_OPENROUTER
        set(v) = sp.edit().putString(K_JUDGE_MODEL, v.trim()).apply()

    /** Back-compat alias so older call sites keep compiling. */
    var openRouterKey: String
        get() = judgeKey
        set(v) { judgeKey = v }

    // ---------------------------------------------------------------- reply

    /** OpenAI-compatible base, up to and including `/v1`. */
    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim()).apply()

    /** Blank = fall back to [judgeKey]. */
    var replyKey: String
        get() = sp.getString(K_REPLY_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_REPLY_KEY, v.trim()).apply()

    /** Generative model for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // --------------------------------------------------------------- vision

    /**
     * Blank = the OpenRouter vision default. Deliberately does NOT follow
     * [replyBaseUrl]: a reply host like DeepSeek has no vision endpoint, so
     * inheriting it would silently break OCR.
     */
    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, DEFAULT_VISION_BASE) ?: DEFAULT_VISION_BASE
        set(v) = sp.edit().putString(K_VISION_BASE, v.trim()).apply()

    /** Blank = fall back to [replyKey] then [judgeKey]. */
    var visionKey: String
        get() = sp.getString(K_VISION_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_VISION_KEY, v.trim()).apply()

    var visionModel: String
        get() = sp.getString(K_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL
        set(v) = sp.edit().putString(K_VISION_MODEL, v.trim()).apply()

    // -------------------------------------------------------- context (D)

    /**
     * Record per-contact history and inject it into analysis. Default OFF:
     * nothing about the user's chats is written to disk unless they opt in
     * (v1.3 revision, D stage).
     */
    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_CTX_ENABLED, v).apply()

    /** How many recent history entries to inject. */
    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = sp.edit().putInt(K_CTX_COUNT, v).apply()

    /** Auto-summarize a contact once enough history accumulates. */
    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SUMMARY, v).apply()

    // ------------------------------------------------------------ OCR (B)

    /** "mlkit" | "vision". */
    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = sp.edit().putString(K_OCR_ENGINE, v.trim()).apply()

    /** Run generic OCR capture on apps with no dedicated adapter. */
    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = sp.edit().putBoolean(K_OCR_UNKNOWN, v).apply()

    /** Fall back to OCR when an adapted app's node tree comes back empty. */
    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    /** Auto-analyze in OCR mode (default off: OCR costs a screenshot each time). */
    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------------------- existing

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

    // ------------------------------------------------------------- helpers

    /** Reply route key, falling back to the judge key. */
    fun effectiveReplyKey(): String = replyKey.ifBlank { judgeKey }

    /** Vision route key, falling back to reply then judge. */
    fun effectiveVisionKey(): String = visionKey.ifBlank { effectiveReplyKey() }

    /** Full POST URL for the Jev decisions call, per provider. */
    fun judgeEndpoint(): String {
        val base = judgeBaseUrl.trim().trimEnd('/')
        return when (judgeProvider) {
            PROVIDER_BOCHA -> "$base/v1/systemone"    // same path as TypeSafe
            PROVIDER_TYPESAFE -> "$base/v1/systemone"
            PROVIDER_VERCEL -> "$base/v1/systemone"   // TypeSafe-compatible gateway
            PROVIDER_ZEN -> "$base/v1/systemone"      // TypeSafe-compatible gateway
            PROVIDER_CUSTOM -> judgeBaseUrl.trim()   // user supplies the full URL
            else -> "$base/alpha/decisions"
        }
    }

    /** Full POST URL for the OpenAI-compatible chat completions call. */
    fun replyEndpoint(): String = "${replyBaseUrl.trim().trimEnd('/')}/chat/completions"

    /** Same shape as [replyEndpoint]; blank falls back to the OpenRouter default. */
    fun visionEndpoint(): String {
        val base = visionBaseUrl.trim().ifBlank { DEFAULT_VISION_BASE }
        return "${base.trimEnd('/')}/chat/completions"
    }

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: the judge route is the one that must be configured. */
    fun hasKey(): Boolean = judgeKey.isNotBlank()

    companion object {
        private const val TAG = "JEVASSIST"

        /** The one real config file. Anything else is a scratch instance. */
        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_UNSEEDED_BOCHA = "unseeded_bocha_v141"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        const val PROVIDER_BOCHA = "bocha"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_VERCEL = "vercel"
        const val PROVIDER_ZEN = "zen"
        const val PROVIDER_CUSTOM = "custom"

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        // Judge route presets.
        // Bocha Jev: same protocol/path as TypeSafe (/v1/systemone). Limited-time free.
        const val DEFAULT_JUDGE_BASE_BOCHA = "https://jev.bocha.cn"
        const val DEFAULT_JUDGE_MODEL_BOCHA = "bocha-jev-v1"
        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"
        const val DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai"
        const val DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest"
        // Vercel AI Gateway's TypeSafe-compatible API. Same /v1/systemone body
        // and noul answers as TypeSafe direct; model id is the gateway's.
        const val DEFAULT_JUDGE_BASE_VERCEL = "https://ai-gateway.vercel.sh/typesafe"
        const val DEFAULT_JUDGE_MODEL_VERCEL = "typesafe-ai/jev"
        // OpenCode Zen's TypeSafe-compatible API. Same /v1/systemone body and
        // noul answers; jev-1.13 is free on output ($0.042/M input, ~1k tokens
        // per judgment), jev-1.13-free is fully free but capability-limited.
        const val DEFAULT_JUDGE_BASE_ZEN = "https://opencode.ai/zen"
        const val DEFAULT_JUDGE_MODEL_ZEN = "jev-1.13"

        // Reply route presets (OpenAI-compatible chat completions).
        const val DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"

        // Vision route preset (OpenRouter region-available; user may change).
        const val DEFAULT_VISION_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_VISION_MODEL = "qwen/qwen2.5-vl-72b-instruct"
        const val DASHSCOPE_VISION_MODEL = "qwen-vl-max"

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
