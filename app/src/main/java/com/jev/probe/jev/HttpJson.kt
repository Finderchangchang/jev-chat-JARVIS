package com.jev.probe.jev

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which of the three API routes a failure came from. Used to build error text
 * the user can act on ("判断接口 HTTP 401：…" vs "回复接口 …").
 */
object Route {
    const val JUDGE = "判断接口"
    const val REPLY = "回复接口"
    const val VISION = "视觉接口"
}

/**
 * Carries the route, the HTTP status (null = transport failure) and the first
 * 120 chars of the response body so the settings page can show the real reason.
 */
class ApiException(
    val route: String,
    val status: Int?,
    val snippet: String
) : RuntimeException(buildMessage(route, status, snippet)) {

    companion object {
        fun buildMessage(route: String, status: Int?, snippet: String): String =
            if (status != null) "$route HTTP $status：${snippet.take(120)}"
            else "$route 请求失败：${snippet.take(120)}"
    }
}

/**
 * Shared POST-JSON helper: UTF-8 body, exponential backoff on 429/529, no retry
 * on other 4xx, and every failure normalized to [ApiException]. Keys are passed
 * in per call and never logged.
 */
object HttpJson {

    private const val MAX_ATTEMPTS = 3

    /**
     * @param route one of [Route], used only for error text.
     * @param extraHeaders additional request headers (e.g. OpenRouter attribution).
     */
    fun post(
        url: String,
        key: String,
        body: JSONObject,
        route: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        var attempt = 0
        var last: ApiException? = null
        while (attempt < MAX_ATTEMPTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 40000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    last = ApiException(route, code, "服务繁忙，已重试")
                    attempt++
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                // Branch on the status code FIRST. Reading the body must never be
                // able to lose it: errorStream is null on some failures (and on
                // some OEM stacks), and a read can throw on a truncated response —
                // either way this used to surface as a transport failure with no
                // status, which then got retried even for a 401.
                if (code !in 200..299) {
                    val errText = readBody(conn.errorStream)
                    throw ApiException(route, code, errText.ifBlank { "（响应体为空）" })
                }
                val text = readBody(conn.inputStream)
                if (text.isBlank()) throw ApiException(route, code, "响应体为空")
                return JSONObject(text)
            } catch (e: ApiException) {
                if (e.status != null && e.status in 400..499) throw e  // client error: no retry
                last = e
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } catch (e: Exception) {
                last = ApiException(route, null, describe(e))
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw last ?: ApiException(route, null, "请求失败")
    }

    /** Body text, or "" — a null stream or a read failure never costs us the status code. */
    private fun readBody(stream: java.io.InputStream?): String {
        stream ?: return ""
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    /** OpenRouter wants attribution headers; other hosts reject unknown ones politely. */
    fun headersFor(url: String): Map<String, String> =
        if (url.contains("openrouter.ai", ignoreCase = true))
            mapOf("HTTP-Referer" to "https://jev-assistant.local", "X-Title" to "Jev Assistant")
        else emptyMap()

    /** Human-readable transport failures (no key material ever appears here). */
    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("timed out") || m.contains("timeout", true) -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") -> "域名解析失败，地址填错或无网络"
            m.contains("Failed to connect") || m.contains("ECONNREFUSED") -> "无法连接该地址"
            m.contains("CertPath") || m.contains("SSL") -> "HTTPS 证书校验失败"
            else -> m
        }
    }
}
