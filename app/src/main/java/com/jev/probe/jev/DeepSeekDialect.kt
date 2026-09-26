package com.jev.probe.jev

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts the official DeepSeek API (`https://api.deepseek.com/v1`) to the two
 * protocols this app speaks. DeepSeek is OpenAI-compatible, so the *transport*
 * needs almost nothing — but two documented behaviours need real handling:
 *
 * 1. **Empty content in JSON mode.** The official JSON Output guide states the
 *    API may return an empty `content` when `response_format=json_object` is on
 *    ("API 有概率会返回空的 content"). A single empty answer would otherwise
 *    surface to the user as "judge failed" with no cause, so every JSON-mode
 *    call is retried a couple of times.
 *
 * 2. **No Jev protocol.** DeepSeek serves neither `/alpha/decisions` nor
 *    `/v1/systemone`, so the judgment is re-expressed as a prompt and the reply
 *    is mapped back onto the same answer keys (see [JudgeClient]).
 *
 * Nothing here is DeepSeek-specific in shape — a host that rejects
 * `response_format` is retried without it — so the helpers stay usable if the
 * custom route is ever pointed at a similar API.
 */
object DeepSeekDialect {

    private const val TAG = "JEVASSIST"

    /** How many times a JSON-mode call may be re-sent to dodge an empty content. */
    private const val MAX_EMPTY_RETRIES = 2

    /**
     * True when [url] points at DeepSeek's official host. Used to decide whether
     * the route may assume OpenAI-compatible extensions such as `response_format`.
     */
    fun isDeepSeek(url: String): Boolean =
        url.contains("api.deepseek.com", ignoreCase = true)

    /**
     * Build an OpenAI-compatible chat-completions body.
     *
     * @param jsonMode sets `response_format={"type":"json_object"}`. The official
     *        docs additionally require the word "json" in the prompt and a sample
     *        of the wanted shape, which the callers supply.
     */
    fun chatBody(
        model: String,
        system: String,
        user: String,
        temperature: Double,
        jsonMode: Boolean,
        disableThinking: Boolean
    ): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .apply {
                if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
                if (disableThinking) put("thinking", JSONObject().put("type", "disabled"))
            }
    }

    /**
     * POST a chat completion and return the assistant message content, retrying
     * while DeepSeek hands back an empty `content` in JSON mode.
     *
     * A 4xx that mentions `response_format` (a host that does not implement it)
     * degrades to the same request without JSON mode rather than failing, so an
     * unverified parameter can cost structure but never the whole analysis.
     *
     * @throws ApiException on transport failure, auth failure, or a body that
     *         stays empty through every retry.
     */
    fun complete(
        url: String,
        key: String,
        model: String,
        system: String,
        user: String,
        temperature: Double,
        route: String,
        jsonMode: Boolean
    ): String {
        var useJsonMode = jsonMode
        var attempt = 0
        // DeepSeek turns thinking mode ON by default at effort=high; every call
        // here is short and structured, so the chain of thought is pure latency
        // (~20 s on the judge prompt). Only DeepSeek is sent the field, so a
        // host that does not know it never sees it.
        val disableThinking = isDeepSeek(url)
        while (true) {
            val body = chatBody(model, system, user, temperature, useJsonMode, disableThinking)
            val resp = try {
                HttpJson.post(url, key, body, route, HttpJson.headersFor(url))
            } catch (e: ApiException) {
                val rejected = e.status != null && e.status in 400..499 &&
                    e.snippet.contains("response_format", ignoreCase = true)
                if (useJsonMode && rejected) {
                    Log.w(TAG, "$route rejected response_format (HTTP ${e.status}); retrying plain")
                    useJsonMode = false
                    continue
                }
                throw e
            }
            val content = resp.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isNotBlank()) return content
            if (attempt >= MAX_EMPTY_RETRIES) {
                throw ApiException(route, null, "模型返回了空内容（JSON 模式下偶发，已重试 $attempt 次）")
            }
            attempt++
            Log.w(TAG, "$route empty content in json mode; retry $attempt")
            Thread.sleep(300L * attempt)
        }
    }

    /**
     * The outermost balanced `{...}` or `[...]` in [raw], or null.
     *
     * Models wrap JSON in prose or ```json fences often enough that parsing the
     * raw string is not viable, and a plain `indexOf('{')..lastIndexOf('}')` slice
     * breaks as soon as the model adds a trailing sentence containing a brace.
     * This scans with string-awareness so braces inside string literals do not
     * unbalance the count.
     */
    fun extractJson(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        for (open in charArrayOf('{', '[')) {
            val start = s.indexOf(open)
            if (start < 0) continue
            val end = matchFrom(s, start)
            if (end > start) return s.substring(start, end + 1)
        }
        return null
    }

    /** Index of the bracket closing the one at [start], or -1. */
    private fun matchFrom(s: String, start: Int): Int {
        val open = s[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /**
     * Parse [raw] as a JSON object, tolerating code fences and surrounding prose.
     * Returns null when nothing parseable is present.
     */
    fun parseObject(raw: String?): JSONObject? {
        val json = extractJson(raw) ?: return null
        if (!json.startsWith("{")) return null
        return try { JSONObject(json) } catch (_: Exception) { null }
    }

    /**
     * Parse [raw] as a JSON array, tolerating code fences and surrounding prose.
     * Returns null when nothing parseable is present.
     */
    fun parseArray(raw: String?): JSONArray? {
        val json = extractJson(raw) ?: return null
        if (!json.startsWith("[")) return null
        return try { JSONArray(json) } catch (_: Exception) { null }
    }
}
