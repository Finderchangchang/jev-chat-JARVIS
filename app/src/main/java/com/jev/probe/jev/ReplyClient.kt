package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    /** Exactly 3 varied candidate replies in Chinese. */
    fun draft(snapshot: ChatSnapshot, relationship: String): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        return parseThree(chat(sys, user, temperature = 0.8))
    }

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(system: String, user: String, temperature: Double): String {
        val url = prefs.replyEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        // Fallback: split lines.
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }
}
