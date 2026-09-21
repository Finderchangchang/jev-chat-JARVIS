package com.jev.probe.feishu

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * FeishuClient 本地单测：起一个 127.0.0.1 的 HTTP 桩模拟飞书开放平台，
 * 验证 token 缓存/刷新、鉴权头、查询参数、分页排序与错误处理。不发真实网络请求。
 */
class FeishuClientTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var client: FeishuClient

    private val tokenHits = AtomicInteger(0)
    private val messagesHits = AtomicInteger(0)
    private val tokenShouldFail = AtomicBoolean(false)

    /** /im/v1/messages 的应答队列：弹尽为止，弹尽走默认成功应答。 */
    private val messageResponses = ArrayDeque<String>()

    @Volatile private var lastAuth: String? = null
    @Volatile private var lastQuery: String? = null

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/open-apis/auth/v3/tenant_access_token/internal") { ex ->
            tokenHits.incrementAndGet()
            val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            if (tokenShouldFail.get() || reqBody.contains("\"cli_bad\"")) {
                respond(ex, """{"code":99991663,"msg":"app_id or app_secret is invalid"}""")
            } else {
                respond(ex, """{"code":0,"msg":"ok","tenant_access_token":"t-test-token","expire":7200}""")
            }
        }
        server.createContext("/open-apis/im/v1/messages") { ex ->
            messagesHits.incrementAndGet()
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            lastQuery = ex.requestURI.rawQuery
            respond(ex, if (messageResponses.isEmpty()) defaultMessagesPage() else messageResponses.poll())
        }
        server.createContext("/open-apis/im/v1/chats") { ex ->
            respond(ex, """{"code":0,"msg":"success","data":{"has_more":false,"items":[
                {"chat_id":"oc_g1","name":"项目群","external":false},
                {"chat_id":"oc_g2","name":"家人群","external":false}]}}""")
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        client = FeishuClient("cli_test", "secret_test", baseUrl = base)
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun defaultMessagesPage(): String =
        """{"code":0,"msg":"success","data":{"has_more":false,"items":[
            {"message_id":"om_2","msg_type":"text","create_time":"1700000002000","deleted":false,
             "chat_id":"oc_g1","sender":{"id":"ou_me","id_type":"open_id","sender_type":"user"},
             "body":{"content":"{\"text\":\"我回复了\"}"}},
            {"message_id":"om_1","msg_type":"text","create_time":"1700000001000","deleted":false,
             "chat_id":"oc_g1","sender":{"id":"ou_wife","id_type":"open_id","sender_type":"user"},
             "body":{"content":"{\"text\":\"出来吃饭吗 @_user_1\"}"},
             "mentions":[{"key":"@_user_1","id":"ou_x","id_type":"open_id","name":"老公"}]}]}}"""

    // ---------------------------------------------------------------- tests

    @Test
    fun `token is fetched once and cached across calls`() {
        client.tenantToken()
        client.tenantToken()
        client.listChats()
        assertEquals(1, tokenHits.get())
    }

    @Test
    fun `listChats parses group list`() {
        val chats = client.listChats()
        assertEquals(2, chats.size)
        assertEquals("项目群", chats[0].name)
        assertEquals("oc_g1", chats[0].chatId)
    }

    @Test
    fun `latestMessages returns ascending order with parsed text`() {
        val msgs = client.latestMessages("oc_g1", 10)
        assertEquals(listOf("om_1", "om_2"), msgs.map { it.messageId })
        assertEquals("出来吃饭吗 @老公", msgs[0].text) // @提及占位替换成真名
        assertEquals(1700000001000L, msgs[0].createTimeMs) // 毫秒字符串 → Long
        assertEquals("other", FeishuMapper.sideOf(msgs[0].senderType, msgs[0].senderId, "ou_me"))
    }

    @Test
    fun `messages carry auth header and query params`() {
        client.latestMessages("oc_g1", 10)
        assertEquals("Bearer t-test-token", lastAuth)
        val q = lastQuery.orEmpty()
        assertTrue(q.contains("container_id=oc_g1"))
        assertTrue(q.contains("sort_type=ByCreateTimeDesc"))
        assertTrue(q.contains("page_size=10"))
    }

    @Test
    fun `messagesSince sends second level start_time ascending`() {
        val msgs = client.messagesSince("oc_g1", startSeconds = 1699999999L)
        assertTrue(lastQuery.orEmpty().contains("start_time=1699999999"))
        assertTrue(lastQuery.orEmpty().contains("sort_type=ByCreateTimeAsc"))
        assertEquals(2, msgs.size)
    }

    @Test
    fun `getMessage parses single item`() {
        messageResponses.add(
            """{"code":0,"msg":"success","data":{"items":[
                {"message_id":"om_9","msg_type":"image","create_time":"1700000009000","deleted":false,
                 "chat_id":"oc_g1","sender":{"id":"ou_w","id_type":"open_id","sender_type":"user"},
                 "body":{"content":"{\"image_key\":\"img_v2\"}"}}]}}""")
        val m = client.getMessage("om_9")
        assertEquals("om_9", m!!.messageId)
        assertEquals("[图片]", m.text)
    }

    @Test
    fun `invalid token triggers forced refresh and retry`() {
        messageResponses.add("""{"code":99991663,"msg":"token invalid"}""")
        val msgs = client.latestMessages("oc_g1", 10)
        assertEquals(2, tokenHits.get()) // 初次获取 + 强制刷新
        assertEquals(2, messagesHits.get()) // 失败一次 + 重试成功
        assertEquals(2, msgs.size)
    }

    @Test
    fun `rate limit retries once then succeeds`() {
        messageResponses.add("""{"code":99991400,"msg":"too many requests"}""")
        val msgs = client.latestMessages("oc_g1", 10)
        assertEquals(2, messagesHits.get())
        assertEquals(2, msgs.size)
    }

    @Test
    fun `business error raises typed exception with hint`() {
        messageResponses.add("""{"code":230002,"msg":"Bot can not be outside the group"}""")
        try {
            client.latestMessages("oc_bad", 10)
            fail("should throw FeishuApiException")
        } catch (e: FeishuApiException) {
            assertEquals(230002, e.code)
            assertTrue(e.message!!.contains("机器人不在该群"))
            assertTrue(FeishuClient.readableError(e).contains("机器人不在该群"))
        }
    }

    @Test
    fun `bad secret raises readable auth exception`() {
        val bad = FeishuClient("cli_bad", "bad", baseUrl = base)
        try {
            bad.listChats()
            fail("should throw FeishuApiException")
        } catch (e: FeishuApiException) {
            assertTrue(e.message!!.contains("获取飞书凭证失败"))
        }
    }
}
