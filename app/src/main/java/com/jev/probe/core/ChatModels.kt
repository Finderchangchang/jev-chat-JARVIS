package com.jev.probe.core

/** One captured chat bubble. side is "me" (right) or "other" (left). */
data class Msg(val side: String, val text: String)

/** A snapshot of the currently-open conversation in whichever chat app is
 *  foreground (see ChatAppAdapter). */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** A stable signature of the last few messages, to detect real changes. */
    fun signature(): String =
        messages.takeLast(6).joinToString("|") { "${it.side}:${it.text}" }
}

/** Jev's judgment result for one snapshot, plus the ranked candidate replies. */
data class Analysis(
    val trueIntent: Choice?,
    val dangerLevel: Score?,
    val sheNeeds: Choice?,
    val shouldReplyNow: Double?,
    val bestAction: Choice?,
    val tensionResolved: Double?,
    val literalQuestion: Double?,
    val rankedReplies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)
data class RankedReply(val text: String, val prob: Double)
