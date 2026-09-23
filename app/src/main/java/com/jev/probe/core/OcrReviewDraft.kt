package com.jev.probe.core

/** 校对仅修改当前截图的识别结果，不写入聊天历史。 */
class OcrReviewDraft(messages: List<Msg>) {
    private val entries = messages.toMutableList()

    fun switchSide(index: Int): String {
        val current = entries[index]
        val next = if (current.side == "me") "other" else "me"
        entries[index] = current.copy(side = next)
        return next
    }

    fun updateText(index: Int, text: String) {
        entries[index] = entries[index].copy(text = text)
    }

    fun confirmedMessages(): List<Msg> = entries
        .map { it.copy(text = it.text.trim()) }
        .filter { it.text.isNotEmpty() }
}
