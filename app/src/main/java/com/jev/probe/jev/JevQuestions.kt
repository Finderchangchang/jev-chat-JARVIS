package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.kb.LogEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed Jev question set, ported verbatim from tools/jev/questions.py (the
 * wording that passed calibration). Instructions/criteria in English; chat text
 * stays Chinese. The state `from` field uses "me"/"other" (the instructions
 * already refer to "the other person" throughout).
 */
object JevQuestions {

    /**
     * Appended to every question so the D-stage `background` field (relationship,
     * contact notes, knowledge-base hits) reads as given context rather than as
     * an off-topic digression that should be penalized.
     */
    const val BACKGROUND_NOTE =
        " Facts given in background are provided context, not off-topic."

    private fun noul(instructions: String, t: String, f: String) = JSONObject().apply {
        put("type", "noul")
        put("instructions", instructions + BACKGROUND_NOTE)
        put("criteria", JSONObject().put("true", t).put("false", f))
    }

    private fun choice(instructions: String, criteria: Map<String, String>) = JSONObject().apply {
        put("type", "choice")
        put("instructions", instructions + BACKGROUND_NOTE)
        put("criteria", JSONObject().also { c -> criteria.forEach { (k, v) -> c.put(k, v) } })
    }

    private fun score(instructions: String, levels: List<String>) = JSONObject().apply {
        put("type", "score")
        put("instructions", instructions + BACKGROUND_NOTE)
        put("criteria", JSONArray().also { a -> levels.forEach { a.put(it) } })
    }

    /** The 7 judgment questions. Returns a fresh JSONObject each call. */
    fun judge(): JSONObject = JSONObject().apply {
        put("literal_question", noul(
            "Is the other person's latest message meant purely literally, with no subtext? " +
                "Judge from the whole thread, not one sentence in isolation.",
            "The latest message is a straightforward statement, question, or plan " +
                "with no implied accusation, test, sarcasm, hint, or unsaid request.",
            "There is subtext: a test of whether you remember or care, sarcasm, " +
                "an implied complaint, a hint they will not say outright, a trap question, " +
                "an accusation dressed as a question, or a cold/short line that really means blame."
        ))
        put("true_intent", choice(
            "What is the other person's true intent in the latest message, given the full conversation? " +
                "Prefer tone and context over surface wording. " +
                "If they are checking whether you remember something or still care, choose confirm_you_care " +
                "even if the words look like a request to 'say it' or to do something. " +
                "If they already accepted and closed the matter peacefully, choose close_topic. " +
                "Ending the relationship, deleting you, or 'don't talk to me' is vent_anger, never close_topic.",
            linkedMapOf(
                "confirm_you_care" to ("They are testing whether you remember, pay attention, or still care. " +
                    "Signals: 'did you forget again', 'then say it', 'you better', sarcastic 'busy person', " +
                    "asking you to prove you know a past conversation. " +
                    "If they mainly want a new deliverable or a yes on a time, do not use this."),
                "vent_anger" to ("They are angry or hurt and mainly want the feeling acknowledged. " +
                    "They are blaming or raising the temperature; a specific plan is not the main point yet."),
                "request_action" to ("They want a concrete action, time, deliverable, or commitment from you now, " +
                    "and this is a real ask, not a loyalty test."),
                "seek_explanation" to ("They want a factual explanation of why something happened. " +
                    "They asked why or what is going on, not mainly for an apology or a new plan."),
                "casual_chat" to ("Light talk, banter, sharing, teasing with a laugh, or friendly logistics " +
                    "with no emotional test and no conflict. A friend suggesting a meal time can be this " +
                    "if the thread is warm."),
                "close_topic" to ("Peaceful wrap-up only: they accepted an apology, confirmed a happy plan, said thanks, " +
                    "or clearly signaled they need nothing more. " +
                    "Not a breakup, not 'don't contact me', not sarcastic 'I'm used to it'.")
            )
        ))
        put("danger_level", score(
            "How close is this conversation to a fight or to hurting the relationship? " +
                "Match the current scene. " +
                "If they genuinely accepted an apology or confirmed a happy plan, score the cooled-down present, " +
                "not an earlier complaint. " +
                "If an ultimatum (break up, report to the boss, stop covering for you) is still in force " +
                "and has not been withdrawn, stay in that high bin even if the latest line names a specific task.",
            listOf(
                "Light chat or joking; no complaint, no test, no deadline.",
                "Mild tease or a small reminder that is easy to laugh off; a clumsy reply would only feel slightly awkward.",
                "A mild complaint or 'please remember next time' said without heat; they still send warm or practical follow-ups.",
                "Noticeable unhappiness; they mention being forgotten, ignored, or kept waiting, but still give you a chance to make it right.",
                "Sarcasm, cold short replies, or 'you better'; they are testing you, and a sloppy or fake-confident reply will escalate.",
                "Openly upset; they accuse you of not listening or not caring; they expect a real response, not a joke.",
                "Clearly angry and blaming you; a wrong reply will turn this into a fight.",
                "Last-chance warning. They will not cover for you, do not want to keep talking unless this changes, " +
                    "or tell you to finish a named checklist yourself because trust is almost gone.",
                "An ultimatum is already on the table even if they also give a practical next step: " +
                    "break up if you forget again, report you tonight, or stop working together if you miss this.",
                "Active rupture: they said it is over, told you not to reply, deleted you, or are exploding."
            )
        ))
        put("should_reply_now", noul(
            "Should your next message contain substantive content? " +
                "Substantive means: admitting a specific known fault, giving a concrete time/plan/deliverable, " +
                "explaining facts you actually know, or reciting the recalled content they asked you to say. " +
                "This is NOT 'should you send any message'. Timing is irrelevant. " +
                "Answer FALSE if the thing they want you to recite or prove is not present in this snippet " +
                "(you would be guessing). 'Then say it' / 'you better' while you are stalling is FALSE. " +
                "Answer FALSE if they already accepted and closed the topic. " +
                "Answer true only if the needed fact, plan, or named fault is already in this snippet.",
            "The needed fact, named fault, or named time/place is already in this snippet, " +
                "and they are waiting for that substance now.",
            "Do not put substance in the next message: the recalled content is not in this snippet, " +
                "they are testing whether you remember, a holding line is enough, " +
                "saying less is safer, or they already closed the topic."
        ))
        put("best_action", choice(
            "What type of next action is best? Do not decide whether to send a message immediately. " +
                "Ignore timing. Choose only the action type. " +
                "If they asked you to recall a specific past message or event and you have not shown that you actually remember it, " +
                "choose check_history - do not apologize or invent a plan instead.",
            linkedMapOf(
                "check_history" to ("Look up prior chat or facts before taking a position. " +
                    "Use when they ask you to repeat, recall, or prove you remember something specific."),
                "apologize" to ("Lead with a sincere apology for a real mistake or hurt already identified. " +
                    "Not for an unnamed forgotten thing when you should first find out what it was."),
                "give_commitment" to ("Give a concrete promise, deadline, or arrangement they asked for " +
                    "in a conflict or work-pressure setting."),
                "explain" to ("Explain what happened or why, without leading with apology or a new plan."),
                "acknowledge" to ("Show you heard them and care, without new facts, an apology, or a plan. " +
                    "Use for light chat or when they mainly need to feel seen."),
                "say_less" to ("Keep it short or add nothing. Extra words would over-explain, reopen a closed topic, " +
                    "or pour fuel on an ultimatum that told you not to talk."),
                "make_plan" to ("Propose or confirm logistics (time, place, task) for a non-conflict request " +
                    "such as a meal or a meeting.")
            )
        ))
        put("she_needs", choice(
            "What does the other person need from you right now? Judge the LATEST message first. " +
                "If they genuinely accepted (thanks / got it / 没事了 / 那就这样 / 收到了 / 过去了), " +
                "you MUST choose nothing, even if earlier they wanted action or an apology. " +
                "Sarcastic 'I'm used to it', 'whatever', 'I don't want to hear it', 'don't bother coming' " +
                "is NOT genuine satisfaction - do not choose nothing. " +
                "If they asked you to recap a named time/place/date, choose action. " +
                "If they are testing whether you remember or still care, and the content is unnamed, choose care.",
            linkedMapOf(
                "apology" to "They need a sincere apology for hurt or a mistake, and they have not accepted one yet.",
                "action" to ("They need a concrete action, time, commitment, recap of a named fact, or follow-through, " +
                    "and they have not yet accepted one."),
                "explanation" to "They need a clear explanation of what happened or why, and have not received it.",
                "care" to ("They need proof you remember, listen, or care - a loyalty or attention test - " +
                    "not yet a plan or an apology. Sarcastic 'I am used to it' belongs here, not nothing."),
                "nothing" to ("They need nothing further. Genuine acceptance, a peaceful closed topic, " +
                    "warm casual chat with no ask, or a rupture where they told you not to reply. " +
                    "Not sarcasm pretending to be fine.")
            )
        ))
        put("tension_resolved", noul(
            "Has interpersonal tension already been resolved? " +
                "Answer true only if there was never tension, or the other person has clearly accepted, " +
                "cooled down, joked again, or said it is fine. " +
                "A sarcastic 'you better', an unanswered test, leftover blame, or an open ultimatum means false.",
            "No remaining tension: they accepted, joked again, said it's fine, " +
                "confirmed a happy plan, or the chat was never tense.",
            "Tension is still present: they are waiting, testing, angry, sarcastic, " +
                "issuing an ultimatum, or the issue is open."
        ))
    }

    /**
     * Build Jev state from a snapshot (last 10 messages), optionally carrying
     * the D-stage knowledge context.
     *
     * @param background relationship + contact notes + matched knowledge notes.
     * @param history older messages for this contact, already de-duplicated
     *        against what is on screen.
     *
     * Both extra fields are omitted when empty, so a user with no knowledge base
     * sends exactly the same body v1.2 did.
     */
    fun buildState(
        snapshot: ChatSnapshot,
        relationship: String,
        background: String = "",
        history: List<LogEntry> = emptyList()
    ): JSONObject {
        val msgs = JSONArray()
        val last10 = snapshot.messages.takeLast(10)
        for (m in last10) {
            msgs.put(JSONObject().put("from", m.side).put("text", m.text))
        }
        val chat = JSONObject()
            .put("relationship", relationship)
            .put("messages", msgs)
            .put("latest_from", last10.lastOrNull()?.side ?: "other")
        val state = JSONObject().put("chat", chat)
        if (background.isNotBlank()) state.put("background", background)
        if (history.isNotEmpty()) {
            val h = JSONArray()
            history.forEach { h.put(JSONObject().put("from", it.side).put("text", it.text)) }
            state.put("history", h)
        }
        return state
    }

    /** The best_reply ranking question over exactly 3 candidates (Chinese text kept). */
    fun rankQuestion(candidates: List<String>): JSONObject {
        require(candidates.size == 3) { "rankQuestion expects exactly 3 candidates" }
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val criteria = JSONObject()
        keys.forEachIndexed { i, k -> criteria.put(k, candidates[i]) }
        val q = JSONObject().apply {
            put("type", "choice")
            put("instructions",
                "Which candidate reply is the most appropriate next message, " +
                    "given the conversation and the other person's true need? " +
                    "Prefer a reply that matches the best action type. " +
                    "Penalize dismissive, over-promising, or off-topic replies. " +
                    "If the facts are not yet confirmed, prefer the candidate that looks them up " +
                    "instead of faking memory or a vague apology." + BACKGROUND_NOTE)
            put("criteria", criteria)
        }
        return JSONObject().put("best_reply", q)
    }

    // ---------------------------------------------------------------------
    // Prompt rendering for OpenAI-compatible hosts (DeepSeek official).
    //
    // A host without the Jev protocol has to be *asked in prose* for the very
    // same answers. To keep the two transports from drifting, the prompt is
    // generated from the question JSON above rather than hand-written: the
    // instructions, the choice keys and the score legend all come from the same
    // source of truth the native protocol sends, so editing a criterion above
    // changes both routes at once.
    // ---------------------------------------------------------------------

    /** The `choice` / `score` / `noul` type tag of a question object. */
    private fun qType(q: JSONObject): String = q.optString("type")

    /**
     * Render one question into compact prose: the instruction, then the allowed
     * keys with their criteria (or the 0..N score legend).
     */
    private fun renderQuestion(id: String, q: JSONObject): String {
        val sb = StringBuilder()
        sb.append("### ").append(id).append('\n')
        sb.append(q.optString("instructions")).append('\n')
        val criteria = q.opt("criteria")
        when (qType(q)) {
            "noul" -> sb.append("Answer with true or false.\n")
            "score" -> {
                if (criteria is JSONArray) {
                    sb.append("Answer with an integer 0..").append(criteria.length() - 1)
                        .append(" (0 is the mildest).\n")
                    for (i in 0 until criteria.length()) {
                        sb.append("  ").append(i).append(" = ").append(criteria.optString(i)).append('\n')
                    }
                }
            }
            else -> {
                sb.append("Answer with exactly one of: ")
                if (criteria is JSONObject) {
                    sb.append(criteria.keys().asSequence().joinToString(", ")).append('\n')
                } else {
                    sb.append("the listed options\n")
                }
                if (criteria is JSONObject) {
                    criteria.keys().forEach { k ->
                        sb.append("  ").append(k).append(" = ")
                            .append(criteria.optString(k)).append('\n')
                    }
                }
            }
        }
        return sb.toString()
    }

    /** The identity + output contract shared by every chat-completions judgment. */
    fun judgeSystemPrompt(): String =
        "You are Jev, a precise judge of Chinese instant-messaging conversations. " +
            "You read the conversation and answer questions about the other person's intent and needs.\n" +
            "You never write a reply and never give advice; you only classify.\n" +
            "Answer EVERY question you are asked, using ONLY the allowed keys or numbers given for it.\n" +
            "Use the full conversation and the background facts; background facts are provided context, " +
            "not an off-topic digression.\n\n" +
            "Reply with a single JSON object and nothing else — no markdown fence, no commentary.\n" +
            "Its keys are the question ids. The value shape depends on how the question is answered:\n" +
            "  - choice question -> an object {\"choice\": \"<one allowed key>\", \"confidence\": 0.0-1.0}\n" +
            "  - score question  -> an object {\"score\": <number>, \"confidence\": 0.0-1.0}\n" +
            "  - true/false      -> an object {\"noul\": true|false}\n" +
            "Example of the exact JSON shape wanted:\n" +
            "{\"true_intent\": {\"choice\": \"casual_chat\", \"confidence\": 0.8}, " +
            "\"danger_level\": {\"score\": 2, \"confidence\": 0.7}, " +
            "\"should_reply_now\": {\"noul\": true}}"

    /**
     * Render the state and the asked questions into the user message.
     *
     * Only the questions in [questions] are rendered and only they are demanded
     * back, so the ranking call asks one question and the judgment call asks
     * seven without either having to filter afterwards.
     */
    fun judgeUserPrompt(state: JSONObject, questions: JSONObject): String {
        val sb = StringBuilder()
        val chat = state.optJSONObject("chat")
        sb.append("## Conversation\n")
        sb.append("Relationship: ").append(chat?.optString("relationship").orEmpty()).append('\n')
        val msgs = chat?.optJSONArray("messages")
        if (msgs != null) {
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                sb.append(if (m.optString("from") == "me") "我：" else "对方：")
                    .append(m.optString("text")).append('\n')
            }
        }
        val background = state.optString("background")
        if (background.isNotBlank()) {
            sb.append("\n## Background (given facts; not off-topic)\n")
                .append(background).append('\n')
        }
        val history = state.optJSONArray("history")
        if (history != null && history.length() > 0) {
            sb.append("\n## Earlier messages (oldest first)\n")
            for (i in 0 until history.length()) {
                val m = history.optJSONObject(i) ?: continue
                sb.append(if (m.optString("from") == "me") "我：" else "对方：")
                    .append(m.optString("text")).append('\n')
            }
        }
        sb.append("\n## Questions\n")
        val asked = questions.keys().asSequence().toList()
        asked.forEach { id ->
            questions.optJSONObject(id)?.let { sb.append('\n').append(renderQuestion(id, it)) }
        }
        sb.append("\nAnswer ALL of these ids and only these: ")
            .append(asked.joinToString(", ")).append('.')
        return sb.toString()
    }

    /**
     * Normalize a chat-completions judgment reply onto the shape the native
     * protocol returns, so [JudgeClient] parses one thing and only one thing.
     *
     * Two tolerances, both for real model behaviour:
     * - a bare string/number value (`"true_intent": "casual_chat"`) is lifted into
     *   the object form the parser expects;
     * - a question the model chose not to answer (or the rank call's sole
     *   question) falls back to a safe default instead of parsing as null.
     *
     * A dropped answer is a degraded judgment, so it is logged — but the choices
     * that *were* answered stay authoritative and are never overwritten.
     */
    fun mergeJudgeReply(reply: JSONObject, asked: List<String>): JSONObject {
        val out = JSONObject()
        for (id in asked) {
            val raw = reply.opt(id)
            when (raw) {
                is JSONObject -> out.put(id, raw)
                is String -> out.put(id, coerceScalar(id, raw))
                is Number -> out.put(id, coerceScalar(id, raw.toString()))
                is Boolean -> out.put(id, coerceScalar(id, raw.toString()))
                else -> {
                    android.util.Log.w("JEVASSIST", "judge answer missing for '$id'; using default")
                    out.put(id, defaultAnswer(id))
                }
            }
        }
        // The native protocol always reports `probabilities` for a choice; the
        // chat transport has no per-key distribution, so expose the reported
        // confidence as the winning key's probability. Ranking reads it directly.
        for (id in asked) {
            val o = out.optJSONObject(id) ?: continue
            if (o.has("choice") && !o.has("probabilities")) {
                val probs = JSONObject().put(o.optString("choice"), o.optDouble("confidence", 0.5))
                o.put("probabilities", probs)
            }
        }
        return out
    }

    /** A scalar answer for [id] lifted into the object form the parser expects. */
    private fun coerceScalar(id: String, value: String): JSONObject {
        val v = value.trim()
        return when {
            // true/false questions are keyed by `noul` (the native protocol calls
            // the boolean value "noul"; see the JudgeClient reader).
            v.equals("true", true) || v.equals("false", true) ->
                JSONObject().put("noul", v.equals("true", true))
            v.toDoubleOrNull() != null -> JSONObject().put("score", v.toDouble())
            else -> JSONObject().put("choice", v).put("confidence", 0.5)
        }
    }

    /**
     * Fallback for an unanswered question. Deliberately the *least* alarming
     * reading of an unknown scene: no danger asserted, nothing claimed about the
     * other person, and `should_reply_now=false` so the UI never pushes the user
     * to send substance based on a question the model never answered.
     */
    private fun defaultAnswer(id: String): JSONObject = when (id) {
        "danger_level" -> JSONObject().put("score", 0.0).put("confidence", 0.0)
        "should_reply_now", "tension_resolved", "literal_question" ->
            JSONObject().put("noul", false)
        "she_needs" -> JSONObject().put("choice", "nothing").put("confidence", 0.0)
        "best_action" -> JSONObject().put("choice", "say_less").put("confidence", 0.0)
        "best_reply" -> JSONObject().put("choice", "reply_a").put("confidence", 0.0)
        else -> JSONObject().put("choice", "casual_chat").put("confidence", 0.0)
    }
}
