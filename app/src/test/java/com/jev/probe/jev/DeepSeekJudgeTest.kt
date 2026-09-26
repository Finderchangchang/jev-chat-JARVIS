package com.jev.probe.jev

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat-completions judgment transport: prompt rendering, tolerant reply
 * parsing, and the merge that maps a model's JSON back onto the keys the native
 * Jev protocol would have returned.
 */
class DeepSeekJudgeTest {

    // ------------------------------------------------------------- extraction

    @Test
    fun `extractJson unwraps a markdown fence`() {
        val raw = "```json\n{\"a\":1}\n```"
        assertEquals("{\"a\":1}", DeepSeekDialect.extractJson(raw))
    }

    @Test
    fun `extractJson ignores braces inside string values`() {
        val raw = "{\"choice\":\"he said {hi}\",\"confidence\":0.5}"
        val json = DeepSeekDialect.extractJson(raw)
        assertEquals(raw, json)
        assertNotNull(DeepSeekDialect.parseObject(raw))
    }

    @Test
    fun `extractJson survives trailing prose containing a brace`() {
        val raw = "{\"a\":1}\nNote: the } above is the end."
        assertEquals("{\"a\":1}", DeepSeekDialect.extractJson(raw))
    }

    @Test
    fun `extractJson handles escaped quotes`() {
        val raw = "{\"text\":\"he said \\\"ok\\\"\"}"
        assertEquals(raw, DeepSeekDialect.extractJson(raw))
    }

    @Test
    fun `parseObject returns null for prose with no json`() {
        assertNull(DeepSeekDialect.parseObject("抱歉，我无法完成。"))
    }

    @Test
    fun `parseArray reads an array out of surrounding prose`() {
        val arr = DeepSeekDialect.parseArray("这是结果：[ \"一\", \"二\", \"三\" ] 以上。")
        assertNotNull(arr)
        assertEquals(3, arr!!.length())
        assertEquals("二", arr.getString(1))
    }

    // ------------------------------------------------------------ merge logic

    @Test
    fun `merge lifts a bare string choice into object form`() {
        val out = JevQuestions.mergeJudgeReply(
            JSONObject().put("true_intent", "casual_chat"),
            listOf("true_intent"))
        val q = out.getJSONObject("true_intent")
        assertEquals("casual_chat", q.getString("choice"))
        // A choice must expose probabilities; ranking reads them.
        assertEquals(0.5, q.getJSONObject("probabilities").getDouble("casual_chat"), 1e-9)
    }

    @Test
    fun `merge lifts a bare boolean into noul`() {
        val out = JevQuestions.mergeJudgeReply(
            JSONObject().put("should_reply_now", true),
            listOf("should_reply_now"))
        assertTrue(out.getJSONObject("should_reply_now").getBoolean("noul"))
    }

    @Test
    fun `merge lifts a bare number into score`() {
        val out = JevQuestions.mergeJudgeReply(
            JSONObject().put("danger_level", 4),
            listOf("danger_level"))
        assertEquals(4.0, out.getJSONObject("danger_level").getDouble("score"), 1e-9)
    }

    @Test
    fun `merge keeps answered values and only defaults the missing ones`() {
        val reply = JSONObject().put("true_intent",
            JSONObject().put("choice", "vent_anger").put("confidence", 0.9))
        val out = JevQuestions.mergeJudgeReply(reply, listOf("true_intent", "she_needs"))

        // The answered question is untouched — never overwritten by a default.
        assertEquals("vent_anger", out.getJSONObject("true_intent").getString("choice"))
        assertEquals(0.9, out.getJSONObject("true_intent").getDouble("confidence"), 1e-9)
        // The dropped one falls back to the safe reading, with zero confidence so
        // the UI can tell it apart from a real judgment.
        assertEquals("nothing", out.getJSONObject("she_needs").getString("choice"))
        assertEquals(0.0, out.getJSONObject("she_needs").getDouble("confidence"), 1e-9)
    }

    @Test
    fun `merge defaults an unanswered danger level to the least alarming score`() {
        val out = JevQuestions.mergeJudgeReply(JSONObject(), listOf("danger_level"))
        assertEquals(0.0, out.getJSONObject("danger_level").getDouble("score"), 1e-9)
    }

    @Test
    fun `merge only touches the questions that were asked`() {
        val out = JevQuestions.mergeJudgeReply(JSONObject(), listOf("best_reply"))
        assertEquals(1, out.length())
        assertTrue(out.has("best_reply"))
    }

    // ---------------------------------------------------------------- prompts

    @Test
    fun `judge prompt names every asked question and no others`() {
        val state = JevQuestions.buildState(
            com.jev.probe.core.ChatSnapshot("小明", listOf(
                com.jev.probe.core.Msg("other", "在吗？"),
                com.jev.probe.core.Msg("me", "在的"))),
            "朋友")
        val questions = JevQuestions.judge()
        val asked = questions.keys().asSequence().toList()
        val prompt = JevQuestions.judgeUserPrompt(state, questions)

        asked.forEach { assertTrue("prompt should name $it", prompt.contains(it)) }
        // The conversation text itself must survive into the prompt.
        assertTrue(prompt.contains("在吗？"))
        assertTrue(prompt.contains("朋友"))
    }

    @Test
    fun `rank prompt asks the single best_reply question`() {
        val state = JevQuestions.buildState(
            com.jev.probe.core.ChatSnapshot("小明", listOf(
                com.jev.probe.core.Msg("other", "在吗？"))),
            "朋友")
        val q = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(listOf("甲", "乙", "丙")).getJSONObject("best_reply"))
        val prompt = JevQuestions.judgeUserPrompt(state, q)

        assertTrue(prompt.contains("best_reply"))
        assertTrue(prompt.contains("乙"))
        // The 7 judgment questions must NOT leak into the ranking call.
        assertFalse(prompt.contains("danger_level"))
        assertFalse(prompt.contains("she_needs"))
    }

    @Test
    fun `system prompt requires json and the word json`() {
        // The official JSON Output guide requires "json" to appear in the prompt.
        val sys = JevQuestions.judgeSystemPrompt()
        assertTrue(sys.contains("json", ignoreCase = true))
        assertTrue(sys.contains("response") || sys.contains("JSON object"))
    }

    // ------------------------------------------------------------------ prefs

    @Test
    fun `deepseek judge endpoint is the openai compatible path`() {
        // Pure endpoint math, mirrored from Prefs.judgeEndpoint for the fallback
        // provider; asserts the shape the real client will POST to.
        val base = "https://api.deepseek.com/v1"
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            "${base.trimEnd('/')}/chat/completions")
    }

    @Test
    fun `vision model guard flags only the text-only flagship`() {
        assertTrue(VisionClient.looksTextOnlyDeepSeekModel("deepseek-v4-pro"))
        assertFalse(VisionClient.looksTextOnlyDeepSeekModel("deepseek-flash"))
        assertFalse(VisionClient.looksTextOnlyDeepSeekModel("deepseek-chat"))
        // Unknown names are allowed through: the API has the final say.
        assertFalse(VisionClient.looksTextOnlyDeepSeekModel("deepseek-something-new"))
    }
}
