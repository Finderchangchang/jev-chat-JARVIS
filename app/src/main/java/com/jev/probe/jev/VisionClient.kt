package com.jev.probe.jev

import android.graphics.Bitmap
import android.util.Base64
import com.jev.probe.core.Prefs
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The vision route: an OpenAI-compatible `/chat/completions` endpoint that
 * accepts `image_url` content parts. B stage wires it to the screenshot
 * pipeline (see docs/v1.3-plan.md "OCR 分层").
 *
 * Reads visionBaseUrl / visionKey / visionModel from [Prefs]. The base URL does
 * not inherit from the reply route (a reply host may have no vision model); the
 * key still falls back reply -> judge.
 *
 * DeepSeek official (`https://api.deepseek.com/v1`) is supported: `deepseek-flash`
 * takes standard `image_url` parts, so the same body works there. Earlier
 * versions of this file hard-blocked DeepSeek on the belief that its API had no
 * vision model; that is no longer true and the guard is gone. Note that
 * `deepseek-v4-pro` does NOT accept images, so the model name matters.
 *
 * Wire format notes that cost real debugging time:
 * - JPEG, not PNG: a screenshot as PNG base64 is several times larger.
 * - `Base64.NO_WRAP`: Android's default inserts newlines, which corrupts the
 *   data URL.
 * - The image part goes BEFORE the text part — DashScope's compatible-mode
 *   rejects the other order, and DeepSeek's docs show text-first. Both orders
 *   are accepted by DeepSeek; image-first is kept because DashScope requires it,
 *   and one order that works on every host beats two host-specific paths.
 */
class VisionClient(private val prefs: Prefs) {

    /**
     * Send a screenshot and get the transcribed dialog back as plain text.
     * B stage will parse this into bubbles; A stage only proves the route works.
     *
     * @param imageBase64Jpeg base64 of a JPEG, without the `data:` prefix.
     */
    fun extractDialog(imageBase64Jpeg: String): String = ask(
        imageBase64Jpeg,
        "你是聊天截图转写助手。把图中聊天气泡按从上到下的顺序转写成文本，" +
            "每行一条，格式 `我：正文` 或 `对方：正文`。只输出转写结果，不要解释。"
    )

    /** Generic single-question call against the image (used by the settings test). */
    fun ask(imageBase64Jpeg: String, prompt: String): String {
        val url = prefs.visionEndpoint()
        // Image first, then text: DashScope compatible-mode requires this order.
        val content = JSONArray()
            .put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64Jpeg")))
            .put(JSONObject().put("type", "text").put("text", prompt))
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", prefs.visionModel)
            .put("messages", messages)
            .put("temperature", 0.0)
            .apply {
                // Transcription is a read-out task, not a reasoning one, and
                // DeepSeek defaults thinking mode ON at effort=high — which on a
                // screenshot is a long wait for nothing. See DeepSeekDialect.
                if (DeepSeekDialect.isDeepSeek(url)) {
                    put("thinking", JSONObject().put("type", "disabled"))
                }
            }
        val resp = HttpJson.post(url, prefs.effectiveVisionKey(), body, Route.VISION, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    companion object {
        /** Bitmap -> JPEG base64 in the exact form [ask] expects. */
        fun encodeJpeg(bitmap: Bitmap, quality: Int = 80): String {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        /**
         * True when the model name is one DeepSeek serves text-only, so the
         * settings page can warn before spending a round trip on a guaranteed
         * 400. This is a *warning*, not a block: the model box is free text and
         * DeepSeek may ship further vision models, so an unrecognised name is
         * allowed through and the API has the final say.
         */
        fun looksTextOnlyDeepSeekModel(model: String): Boolean =
            model.trim().equals("deepseek-v4-pro", ignoreCase = true)

        /** The DeepSeek vision-capable model, pre-filled by the settings preset. */
        fun deepSeekVisionModel(): String = Prefs.DEEPSEEK_VISION_MODEL
    }
}
