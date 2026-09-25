package com.jev.probe.capture

/** Non-blocking fill sequence. [resolve] must validate the live session on every call. */
internal class GuardedInputWriter(
    private val resolve: () -> Input?,
    private val later: (Long, () -> Unit) -> Unit,
    private val copy: (String) -> Unit,
    private val complete: (Boolean) -> Unit
) {
    interface Input {
        val text: String?
        fun setText(text: String): Boolean
        fun focus()
        fun paste()
    }

    fun fill(text: String) {
        val input = resolve() ?: return
        input.setText(text)
        later(150) checkFirst@{
            val fresh = resolve() ?: return@checkFirst
            if (fresh.text == text) { complete(true); return@checkFirst }
            fresh.focus()
            later(300) retry@{
                val focused = resolve() ?: return@retry
                focused.setText(text)
                later(150) checkRetry@{
                    val retryInput = resolve() ?: return@checkRetry
                    if (retryInput.text == text) { complete(true); return@checkRetry }
                    copy(text)
                    if (retryInput.setText("")) {
                        // Clearing can itself trigger navigation; resolve before PASTE too.
                        val pasteInput = resolve() ?: return@checkRetry
                        pasteInput.paste()
                    }
                    later(150) checkPaste@{
                        val after = resolve() ?: return@checkPaste
                        complete(after.text == text)
                    }
                }
            }
        }
    }
}
