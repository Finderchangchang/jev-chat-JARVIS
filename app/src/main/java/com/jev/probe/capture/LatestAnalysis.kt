package com.jev.probe.capture

/** Main-thread state machine. Keep one running pair and only the newest waiting request. */
internal class LatestAnalysis<T> {
    data class Request<T>(val revision: Long, val value: T)
    enum class Part { JUDGMENT, REPLIES }

    private var revision = 0L
    private var context: Pair<ConversationKey, String>? = null
    private var pending: Request<T>? = null
    private var running: Request<T>? = null
    private val completed = mutableSetOf<Part>()
    val hasPending: Boolean get() = pending != null

    fun observe(key: ConversationKey, signature: String): Boolean {
        if (context == (key to signature)) return false
        invalidate()
        context = key to signature
        return true
    }

    fun invalidate() {
        revision++
        context = null
        pending = null
        // Invalidation suppresses output, but does not pretend the HTTP calls finished.
    }

    fun enqueue(value: T) {
        if (context != null) pending = Request(++revision, value)
    }

    fun start(): Request<T>? {
        if (running != null) return null
        val next = pending ?: return null
        pending = null
        running = next
        completed.clear()
        return next
    }

    fun isCurrent(request: Request<T>): Boolean = context != null && request.revision == revision

    fun finish(request: Request<T>, part: Part) {
        if (running != request) return
        completed.add(part)
        if (completed.size == Part.values().size) running = null
    }
}

/** Best identity exposed by adapters; unknown/loading titles must never inherit another chat. */
internal data class ConversationKey(val pkg: String, val windowId: Int, val title: String?) {
    companion object {
        fun confirmedTitle(raw: String?): String? {
            val title = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val placeholders = listOf("连接中", "正在连接", "未连接", "connecting", "加载中", "loading", "同步中", "syncing")
            return title.takeUnless { t -> placeholders.any { t.contains(it, ignoreCase = true) } }
        }
    }
}
