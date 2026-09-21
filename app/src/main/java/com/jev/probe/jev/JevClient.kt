package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to an OpenRouter-compatible API: one generative call to draft 3 candidate replies, then a
 * single Jev "decisions" call carrying all 7 judgment questions plus the ranking
 * question (speculative fan-out). Uses HttpURLConnection only (no deps).
 *
 * The key is passed in per call; it is never logged.
 */
class JevClient(private val key: String, private val replyModel: String, apiBaseUrl: String) {

    private val apiBaseUrl = apiBaseUrl.trim().trimEnd('/')
    private val hasV1Path = apiBaseUrl.endsWith("/v1", ignoreCase = true)
    private val modelsUrl = if (hasV1Path) "$apiBaseUrl/models" else "$apiBaseUrl/v1/models"
    private val chatUrl = if (hasV1Path) "$apiBaseUrl/chat/completions" else "$apiBaseUrl/v1/chat/completions"

    /** Fetch model IDs from an OpenAI-compatible /v1/models endpoint. */
    fun listModels(): List<String> {
        val data = getJson(modelsUrl).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                if (id.isNotEmpty()) add(id)
            }
        }.distinct().sorted()
    }

    /** Analyze using the standard OpenAI-compatible chat completions endpoint. */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
        val start = System.currentTimeMillis()
        try {
            val state = JevQuestions.buildState(snapshot, relationship)
            val questions = JevQuestions.judge()
            val system = "You are a conversation analysis assistant. Return ONLY valid JSON, with no markdown. " +
                "Use the question criteria to classify the chat. JSON keys: " +
                "true_intent, danger_level, she_needs, should_reply_now, best_action, tension_resolved, literal_question. " +
                "Choice values are objects {choice, confidence, probabilities}; score values are objects {score, confidence, legend}. " +
                "For noul values return {noul: 0.0} or {noul: 1.0}."
            val user = "STATE:\n$state\n\nQUESTIONS:\n$questions"
            val answers = chatJson(replyModel, system, user)
            return Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(), latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            return Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = readableError(e))
        }
    }

    /** Draft 3 candidate replies (generative model) then Jev-rank them. Slower. */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = generateCandidates(snapshot, relationship)
        val system = "You rank candidate replies for a chat. Return ONLY valid JSON with " +
            "best_reply: {probabilities: {reply_a: number, reply_b: number, reply_c: number}}. " +
            "Probabilities must be between 0 and 1 and sum approximately to 1."
        val user = "STATE:\n${JevQuestions.buildState(snapshot, relationship)}\n\nCANDIDATES:\n" +
            candidates.mapIndexed { i, v -> "reply_${('a'.code + i).toChar()}: $v" }.joinToString("\n") +
            "\n\nQUESTION:\n${JevQuestions.rankQuestion(candidates)}"
        val answer = chatJson(replyModel, system, user).optJSONObject("best_reply")
        return parseRanked(answer, candidates)
    }

    /** Convenience for the settings connectivity test: judge + replies, sequential. */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }

    /** Ask a generative model for exactly 3 varied candidate replies (Chinese). */
    private fun generateCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("temperature", 0.8)
        val resp = postJson(chatUrl, body)
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
        return parseThree(content)
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

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    private fun chatJson(model: String, system: String, user: String): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.2)
        val resp = postJson(chatUrl, body)
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) throw RuntimeException("模型未返回 JSON")
        return JSONObject(content.substring(start, end + 1))
    }

    private fun getJson(urlStr: String): JSONObject {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 25000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("HTTP-Referer", "https://jev-assistant.local")
                setRequestProperty("X-Title", "Jev Assistant")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
            return JSONObject(text)
        } finally {
            conn?.disconnect()
        }
    }

    /** POST JSON with one retry chain for 429/529 (exponential backoff). */
    private fun postJson(urlStr: String, body: JSONObject): JSONObject {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("HTTP-Referer", "https://jev-assistant.local")
                    setRequestProperty("X-Title", "Jev Assistant")
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    attempt++
                    Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
                return JSONObject(text)
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("HTTP 4") == true) throw e // client error: no retry
                attempt++
                if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw lastErr ?: RuntimeException("request failed")
    }

    private fun readableError(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("HTTP 401") -> "密钥无效或未设置（401）"
            m.contains("HTTP 4") -> "请求被拒：$m"
            m.contains("timed out") || m.contains("timeout") -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") || m.contains("Failed to connect") -> "无法连接网络"
            else -> "分析失败：$m"
        }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
