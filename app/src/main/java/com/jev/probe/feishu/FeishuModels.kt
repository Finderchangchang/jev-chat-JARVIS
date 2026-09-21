package com.jev.probe.feishu

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import org.json.JSONArray
import org.json.JSONObject

/** 机器人所在的一个飞书会话（群；飞书限制：群列表 API 不含单聊 p2p）。 */
data class FeishuChat(
    val chatId: String,
    val name: String,
    val external: Boolean
)

/** 一条飞书消息（body.content 已解析成可读文本）。 */
data class FeishuMessage(
    val messageId: String,
    val chatId: String,
    val msgType: String,
    val text: String,
    val senderId: String,
    val senderType: String,
    val createTimeMs: Long,
    val deleted: Boolean
)

/** 飞书业务错误（code != 0）。message 自带可读中文描述。 */
class FeishuApiException(val code: Int, message: String) : Exception(message)

/**
 * 飞书消息 → 通用 ChatSnapshot 的纯转换逻辑（无 Android 依赖，可本地单测）。
 * 侧别约定与微信模式完全一致：side = "me" / "other"。
 */
object FeishuMapper {

    /** 非文本消息类型 → 方括号占位文本（与飞书 msg_type 对应）。 */
    private val PLACEHOLDER = mapOf(
        "image" to "[图片]",
        "audio" to "[语音]",
        "media" to "[视频]",
        "file" to "[文件]",
        "sticker" to "[表情包]",
        "interactive" to "[卡片消息]",
        "share_chat" to "[群名片]",
        "share_user" to "[个人名片]",
        "share_calendar_event" to "[日历邀请]",
        "calendar" to "[日历邀请]",
        "email" to "[邮件]",
        "cloud_file" to "[云文档]"
    )

    /**
     * 把消息 body.content（JSON 字符串）转成可读文本：
     * - text：取 text 字段；@提及占位（@_user_N）用 mentions 里的真实姓名替换
     * - post（富文本）：递归收集 text/a 片段，标题作前缀
     * - 其它类型：占位符，保证 Jev 拿到的都是可读文字
     */
    fun extractText(msgType: String, content: String, mentions: JSONArray?): String {
        if (content.isBlank()) return PLACEHOLDER[msgType] ?: "[$msgType]"
        val obj = try { JSONObject(content) } catch (_: Exception) { return content.trim() }
        when (msgType) {
            "text" -> {
                var t = obj.optString("text")
                if (t.isBlank()) return PLACEHOLDER[msgType] ?: "[$msgType]"
                if (mentions != null) {
                    for (i in 0 until mentions.length()) {
                        val m = mentions.optJSONObject(i) ?: continue
                        t = t.replace(m.optString("key"), "@" + m.optString("name"))
                    }
                }
                return t.trim()
            }
            "post" -> {
                val out = StringBuilder()
                val title = obj.optString("title")
                if (title.isNotBlank()) out.append(title).append("：")
                walkRichText(obj.opt("content"), out)
                val s = out.toString().trim()
                return s.ifBlank { PLACEHOLDER[msgType] ?: "[$msgType]" }
            }
            else -> return PLACEHOLDER[msgType] ?: "[$msgType]"
        }
    }

    /** 递归收集富文本里的 text/a/at/img 片段（兼容任意嵌套层级）。 */
    private fun walkRichText(node: Any?, out: StringBuilder) {
        when (node) {
            is JSONObject -> {
                when (node.optString("tag")) {
                    "text", "a" -> out.append(node.optString("text")).append(' ')
                    "at" -> out.append('@').append(node.optString("user_name").ifBlank { "提及" }).append(' ')
                    "img" -> out.append("[图片] ")
                    "media" -> out.append("[视频] ")
                    "emotion" -> out.append("[表情] ")
                    else -> node.keys().forEach { walkRichText(node.opt(it), out) }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walkRichText(node.opt(i), out)
        }
    }

    /** 谁发的："me"（我自己/应用机器人）或 "other"（对方）。 */
    fun sideOf(senderType: String, senderId: String, myOpenId: String): String = when {
        senderType == "app" -> "me" // 应用/机器人发的消息不算对方
        myOpenId.isNotBlank() && senderId == myOpenId -> "me"
        else -> "other"
    }

    /** 消息列表 → 通用快照。过滤撤回（deleted）与系统消息。 */
    fun toSnapshot(chatName: String?, messages: List<FeishuMessage>, myOpenId: String): ChatSnapshot {
        val msgs = messages
            .filter { !it.deleted && it.msgType != "system" }
            .map { Msg(sideOf(it.senderType, it.senderId, myOpenId), it.text) }
        return ChatSnapshot(chatName?.ifBlank { null }, msgs, source = "feishu")
    }
}
