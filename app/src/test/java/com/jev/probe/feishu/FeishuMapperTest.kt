package com.jev.probe.feishu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** FeishuMapper 纯转换逻辑的本地单测（无 Android 依赖）。 */
class FeishuMapperTest {

    private fun mentions(vararg pairs: Pair<String, String>): JSONArray {
        val arr = JSONArray()
        pairs.forEach { (key, name) -> arr.put(JSONObject().put("key", key).put("name", name)) }
        return arr
    }

    @Test
    fun `text message replaces mention keys with real names`() {
        val t = FeishuMapper.extractText(
            "text", """{"text":"谢谢 @_user_1 的帮助"}""", mentions("@_user_1" to "小张"))
        assertEquals("谢谢 @小张 的帮助", t)
    }

    @Test
    fun `post message concatenates title and rich text fragments`() {
        val content = """{"title":"周报","content":{"post":{"zh_cn":""" +
            """[{"tag":"text","text":"本周完成"},{"tag":"a","text":"文档链接"},""" +
            """{"tag":"img","image_key":"img_v2"}]}}}"""
        val t = FeishuMapper.extractText("post", content, null)
        assertEquals("周报：本周完成 文档链接 [图片]", t)
    }

    @Test
    fun `non text messages map to placeholders`() {
        assertEquals("[图片]", FeishuMapper.extractText("image", """{"image_key":"vk"}""", null))
        assertEquals("[表情包]", FeishuMapper.extractText("sticker", "", null))
        assertEquals("[邮件]", FeishuMapper.extractText("email", "{}", null))
        assertEquals("[unknown_thing]", FeishuMapper.extractText("unknown_thing", "{}", null))
    }

    @Test
    fun `bad json content falls back to raw text`() {
        assertEquals("随便一串", FeishuMapper.extractText("text", "随便一串", null))
    }

    @Test
    fun `sideOf distinguishes me other and app sender`() {
        assertEquals("me", FeishuMapper.sideOf("app", "cli_bot", "ou_me"))
        assertEquals("me", FeishuMapper.sideOf("user", "ou_me", "ou_me"))
        assertEquals("other", FeishuMapper.sideOf("user", "ou_you", "ou_me"))
        // 我的 open_id 未配置时，除机器人外都按对方处理
        assertEquals("other", FeishuMapper.sideOf("user", "ou_you", ""))
    }

    @Test
    fun `snapshot filters deleted and system messages and keeps order`() {
        val msgs = listOf(
            msg("om_1", "user", "ou_a", "早", 1000L),
            msg("om_2", "user", "ou_me", "早！", 2000L),
            msg("om_3", "user", "ou_b", "被撤回的一条", 3000L, deleted = true),
            msg("om_4", "user", "ou_c", "join", 4000L, isSystem = true)
        )
        val s = FeishuMapper.toSnapshot("测试群", msgs, "ou_me")
        assertEquals("feishu", s.source)
        assertEquals("测试群", s.title)
        assertEquals(listOf("早", "早！"), s.messages.map { it.text })
        assertEquals("me", s.latestFrom) // 最新一条是我发的 → 不触发自动分析
    }

    private fun msg(
        id: String,
        senderType: String,
        senderId: String,
        text: String,
        ts: Long,
        deleted: Boolean = false,
        isSystem: Boolean = false
    ) = FeishuMessage(
        messageId = id, chatId = "oc_1",
        msgType = if (isSystem) "system" else "text",
        text = text, senderId = senderId, senderType = senderType,
        createTimeMs = ts, deleted = deleted
    )
}
