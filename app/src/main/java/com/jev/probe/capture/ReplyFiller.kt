package com.jev.probe.capture

/** Input retries never retain nodes and revalidate the request before every action. */
internal class ReplyFiller(
    private val target: Target,
    private val later: (Long, () -> Unit) -> Unit
) {
    interface Target {
        fun isCurrent(): Boolean
        fun read(): String?
        fun set(text: String): Boolean
        fun focus(): Boolean
        fun paste(): Boolean
        fun copy(text: String)
        fun toast(message: String)
    }

    private fun valid(): Boolean {
        if (target.isCurrent()) return true
        target.toast("会话已变化，请重新分析后填入")
        return false
    }

    private fun copied(text: String) {
        if (!valid()) return
        target.copy(text)
        target.toast("已复制，长按输入框粘贴")
    }

    private fun verify(text: String, onFailure: () -> Unit) {
        later(150) {
            if (valid()) {
                if (target.read() == text) target.toast("已填入，确认后自己发送")
                else onFailure()
            }
        }
    }

    fun fill(text: String, allowWrite: Boolean) {
        if (!valid()) return
        if (!allowWrite) { copied(text); return }
        target.set(text)
        verify(text) {
            if (!valid()) return@verify
            if (!target.focus()) { copied(text); return@verify }
            later(300) {
                if (valid()) {
                    target.set(text)
                    verify(text) paste@{
                        if (!valid()) return@paste
                        target.copy(text)
                        if (!valid()) return@paste
                        if (target.set("")) {
                            if (!valid()) return@paste
                            target.paste()
                            verify(text) { copied(text) }
                        } else copied(text)
                    }
                }
            }
        }
    }
}
