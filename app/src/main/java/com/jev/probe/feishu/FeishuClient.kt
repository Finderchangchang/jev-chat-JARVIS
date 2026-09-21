package com.jev.probe.feishu

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 飞书开放平台客户端（零依赖，HttpURLConnection + org.json，风格与 JevClient 一致）。
 *
 * 覆盖「对话分析」所需的全部读接口：
 * - POST /open-apis/auth/v3/tenant_access_token/internal （token，缓存 + 过期前自动刷新）
 * - GET  /open-apis/im/v1/chats                          （机器人所在群列表，自动翻页）
 * - GET  /open-apis/im/v1/messages                       （会话历史消息，分页/时间过滤/双向排序）
 * - GET  /open-apis/im/v1/messages/{message_id}          （单条消息详情）
 *
 * 刻意不实现任何「写」接口（发消息/建群等）：本应用绝不代替用户向对方发出消息。
 * 密钥只经构造参数传入，不落盘、不打日志。
 *
 * 接口事实来源（2026-09 核对官方文档）：
 * - start_time / end_time 为秒级时间戳；create_time 返回毫秒字符串
 * - 群消息读取需 im:message:readonly + im:message.group_msg（应用身份）
 * - 限流 1000 次/分钟、50 次/秒；限流错误码 99991400
 */
class FeishuClient(
    private val appId: String,
    private val appSecret: String,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val lock = Any()
    private var cachedToken: String? = null
    private var tokenValidUntil = 0L

    /** 获取 tenant_access_token；缓存到过期前 5 分钟，forceRefresh 强制刷新。 */
    fun tenantToken(forceRefresh: Boolean = false): String {
        synchronized(lock) {
            val t = cachedToken
            if (!forceRefresh && t != null && System.currentTimeMillis() < tokenValidUntil) return t
            val body = JSONObject().put("app_id", appId).put("app_secret", appSecret)
            val resp = httpJson("POST", PATH_TOKEN, null, body, bearer = null)
            val code = resp.optInt("code", -1)
            if (code != 0) {
                throw FeishuApiException(code, "获取飞书凭证失败：${resp.optString("msg")}（检查 App ID / App Secret）")
            }
            val token = resp.optString("tenant_access_token")
            if (token.isBlank()) throw FeishuApiException(-1, "飞书凭证为空")
            val expireSec = resp.optLong("expire", 7200L)
            cachedToken = token
            tokenValidUntil = System.currentTimeMillis() + ((expireSec - 300).coerceAtLeast(60)) * 1000
            return token
        }
    }

    /** 机器人所在的群列表（飞书限制：不含单聊），自动翻页（上限 10 页防失控）。 */
    fun listChats(pageSize: Int = 100): List<FeishuChat> {
        val out = ArrayList<FeishuChat>()
        var pageToken: String? = null
        var pages = 0
        while (pages < 10) {
            val q = ArrayList<Pair<String, String>>()
            q.add("page_size" to pageSize.coerceIn(1, 100).toString())
            if (!pageToken.isNullOrBlank()) q.add("page_token" to pageToken!!)
            val data = getData(PATH_CHATS, q)
            val items = data.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                out.add(FeishuChat(o.optString("chat_id"), o.optString("name"), o.optBoolean("external", false)))
            }
            pageToken = if (data.optBoolean("has_more", false)) data.optString("page_token") else null
            if (pageToken.isNullOrBlank()) break
            pages++
        }
        return out
    }

    /** 拉取会话最新 limit 条消息，按时间正序返回（内部用 Desc 排序取最新再翻转）。 */
    fun latestMessages(chatId: String, limit: Int = 10): List<FeishuMessage> {
        val q = listOf(
            "container_id_type" to "chat",
            "container_id" to chatId,
            "sort_type" to "ByCreateTimeDesc",
            "page_size" to limit.coerceIn(1, 50).toString()
        )
        val data = getData(PATH_MESSAGES, q)
        val items = data.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<FeishuMessage>()
        for (i in 0 until items.length()) items.optJSONObject(i)?.let { out.add(parseMessage(it)) }
        out.sortBy { it.createTimeMs }
        return out
    }

    /** 拉取 startSeconds（秒级，含）之后的消息，升序，自动翻页（上限 5 页）。 */
    fun messagesSince(chatId: String, startSeconds: Long, pageSize: Int = 50): List<FeishuMessage> {
        val out = ArrayList<FeishuMessage>()
        var pageToken: String? = null
        var pages = 0
        while (pages < 5) {
            val q = ArrayList<Pair<String, String>>()
            q.add("container_id_type" to "chat")
            q.add("container_id" to chatId)
            q.add("start_time" to startSeconds.toString())
            q.add("sort_type" to "ByCreateTimeAsc")
            q.add("page_size" to pageSize.coerceIn(1, 50).toString())
            if (!pageToken.isNullOrBlank()) q.add("page_token" to pageToken!!)
            val data = getData(PATH_MESSAGES, q)
            val items = data.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { out.add(parseMessage(it)) }
            pageToken = if (data.optBoolean("has_more", false)) data.optString("page_token") else null
            if (pageToken.isNullOrBlank()) break
            pages++
        }
        return out
    }

    /** 单条消息详情（完整读接口覆盖用）。 */
    fun getMessage(messageId: String): FeishuMessage? {
        val data = getData("$PATH_MESSAGES/$messageId", emptyList())
        val items = data.optJSONArray("items") ?: return null
        return items.optJSONObject(0)?.let { parseMessage(it) }
    }

    // ---------------------------------------------------------------- internals

    private fun parseMessage(o: JSONObject): FeishuMessage {
        val sender = o.optJSONObject("sender")
        val msgType = o.optString("msg_type")
        val contentRaw = o.optJSONObject("body")?.optString("content") ?: ""
        return FeishuMessage(
            messageId = o.optString("message_id"),
            chatId = o.optString("chat_id"),
            msgType = msgType,
            text = FeishuMapper.extractText(msgType, contentRaw, o.optJSONArray("mentions")),
            senderId = sender?.optString("id") ?: "",
            senderType = sender?.optString("sender_type") ?: "unknown",
            createTimeMs = o.optLong("create_time", 0L),
            deleted = o.optBoolean("deleted", false)
        )
    }

    /** 带鉴权的 GET：自动处理限流重试与 token 失效刷新（各重试一次）。 */
    private fun getData(path: String, query: List<Pair<String, String>>): JSONObject {
        var resp = httpJson("GET", path, query, null, bearer = tenantToken())
        var code = resp.optInt("code", 0)
        if (code == ERR_RATE_LIMIT) {
            Thread.sleep(800)
            resp = httpJson("GET", path, query, null, bearer = tenantToken())
            code = resp.optInt("code", 0)
        }
        if (code == ERR_TOKEN_INVALID || code == ERR_TOKEN_EXPIRED) {
            resp = httpJson("GET", path, query, null, bearer = tenantToken(forceRefresh = true))
            code = resp.optInt("code", 0)
        }
        if (code != 0) throw FeishuApiException(code, resp.optString("msg"))
        return resp.optJSONObject("data") ?: JSONObject()
    }

    /** 单次 HTTP JSON 请求；业务错误（HTTP 200 + code!=0）原样返回给调用方判断。 */
    private fun httpJson(
        method: String,
        path: String,
        query: List<Pair<String, String>>?,
        body: JSONObject?,
        bearer: String?
    ): JSONObject {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            var conn: HttpURLConnection? = null
            try {
                val url = buildString {
                    append(baseUrl.trimEnd('/')).append(path)
                    if (!query.isNullOrEmpty()) {
                        append('?')
                        append(query.joinToString("&") { (k, v) ->
                            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
                        })
                    }
                }
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10000
                    readTimeout = 20000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) {
                    // HTTP 层失败也可能带飞书业务错误码，能解析就抛业务异常（不重试）
                    val jo = runCatching { JSONObject(text) }.getOrNull()
                    if (jo != null && jo.optInt("code", 0) != 0) {
                        throw FeishuApiException(jo.optInt("code"), jo.optString("msg"))
                    }
                    throw RuntimeException("HTTP $code: ${text.take(160)}")
                }
                return JSONObject(text)
            } catch (e: Exception) {
                if (e is FeishuApiException) throw e // 业务/权限错误，重试无意义
                lastErr = e
                attempt++
                if (attempt < 3) Thread.sleep(600L * attempt)
            } finally {
                conn?.disconnect()
            }
        }
        throw lastErr ?: RuntimeException("飞书请求失败")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://open.feishu.cn"
        private const val PATH_TOKEN = "/open-apis/auth/v3/tenant_access_token/internal"
        private const val PATH_CHATS = "/open-apis/im/v1/chats"
        private const val PATH_MESSAGES = "/open-apis/im/v1/messages"
        private const val ERR_RATE_LIMIT = 99991400
        private const val ERR_TOKEN_INVALID = 99991663
        private const val ERR_TOKEN_EXPIRED = 99991661

        /** 常见业务错误码 → 中文提示前缀。 */
        fun hintFor(code: Int): String = when (code) {
            ERR_RATE_LIMIT -> "飞书限流："
            ERR_TOKEN_INVALID, ERR_TOKEN_EXPIRED -> "飞书凭证失效："
            230002 -> "机器人不在该群："
            230006 -> "应用未开启机器人能力："
            230013 -> "用户不在应用可用范围："
            230027 -> "缺少权限（需 im:message:readonly / im:message.group_msg）："
            231203 -> "该群开了保密模式，无法读取消息："
            else -> "飞书错误(code=$code)："
        }

        /** 把任意异常转成给悬浮窗/设置页看的可读文案。 */
        fun readableError(e: Exception): String {
            if (e is FeishuApiException) return "${hintFor(e.code)}${e.message}"
            val m = e.message ?: e.javaClass.simpleName
            return when {
                m.contains("timed out") || m.contains("timeout") -> "飞书网络超时，请检查连接"
                m.contains("Unable to resolve host") || m.contains("Failed to connect") ||
                    m.contains("ConnectException") -> "无法连接飞书服务器"
                else -> "飞书请求失败：$m"
            }
        }
    }
}
