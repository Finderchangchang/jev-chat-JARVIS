package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class JevClient(prefs: Prefs) {

    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis =
        judgeClient.judge(snapshot, relationship)

    /** Draft 3 candidates on the reply route, then rank them on the judge route. */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = replyClient.draft(snapshot, relationship)
        return judgeClient.rank(snapshot, relationship, candidates)
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}
