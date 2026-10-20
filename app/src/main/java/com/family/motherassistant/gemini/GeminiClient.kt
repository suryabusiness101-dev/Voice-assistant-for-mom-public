package com.family.motherassistant.gemini

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Gemini returned HTTP 429 — the free-tier request quota or rate limit is spent.
 * Distinct from a generic [IOException] so the caller can say "too many questions,
 * try again later" instead of the catch-all error. Not a code bug: the daily quota
 * resets at midnight US Pacific.
 */
class QuotaExceededException(message: String) : IOException(message)

/**
 * One-shot REST call to the Gemini API: audio in, a single structured function call out.
 * No SDK dependency — plain HTTPS + `org.json`, per the project's tech-stack decision.
 */
class GeminiClient(private val apiKey: String) {

    data class Result(
        val functionName: String,
        val args: JSONObject,
        val spokenReplyHindi: String,
        /** Gemini's own transcription of what she said — used to seed the correction log. */
        val heardText: String = "",
        /**
         * When [functionName] is `clarify` and Gemini was only missing one piece of an
         * otherwise clear request: the function to run once she answers (e.g. `send_sms`),
         * and a short English line of what was known / asked. Both empty for an open-ended
         * clarify. Consumed by [MainActivity] to build the next turn's follow-up context.
         */
        val pendingIntent: String = "",
        val pendingContext: String = ""
    )

    /**
     * Blocking network call — invoke from a background dispatcher.
     *
     * @param sessionContext optional plain-text notes from earlier in the same session
     *        (e.g. "she just set the ringer to silent") so follow-ups like "make it loud again" work.
     *        Not persisted anywhere; caller owns the list's lifetime.
     * @param personalizationBlock optional compact block (glossary + recent corrections) appended
     *        to the system instruction — see [com.family.motherassistant.personalization.Personalization].
     * @param pendingClarificationBlock optional note (built by [PendingClarification]) describing a
     *        question the previous turn asked her, so this turn's audio can be read as the answer
     *        rather than a brand-new request. Session-only; caller clears it after one turn.
     */
    @Throws(IOException::class)
    fun classify(
        audioFile: File,
        sessionContext: List<String> = emptyList(),
        personalizationBlock: String = "",
        pendingClarificationBlock: String = ""
    ): Result {
        require(apiKey.isNotBlank()) { "Gemini API key is not configured" }

        val body = buildRequest(
            audioFile, sessionContext, personalizationBlock, pendingClarificationBlock
        ).toString()

        var attempt = 0
        while (true) {
            try {
                return attemptRequest(body)
            } catch (e: IOException) {
                // Retry only transient network failures (timeout, refused/reset connection) —
                // never QuotaExceededException or the plain IOException thrown below for a
                // non-2xx HTTP status, both of which are genuine API responses, not connectivity
                // blips, and should fail fast exactly as before.
                val transient = e is SocketTimeoutException || e is ConnectException || e is SocketException
                if (!transient || attempt >= MAX_RETRIES) throw e
                val delayMs = RETRY_BASE_DELAY_MS * (1L shl attempt)
                Log.w(TAG, "transient network error (attempt ${attempt + 1}/$MAX_RETRIES), retrying in ${delayMs}ms: ${e.message}")
                Thread.sleep(delayMs)
                attempt++
            }
        }
    }

    @Throws(IOException::class)
    private fun attemptRequest(body: String): Result {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code == HTTP_TOO_MANY_REQUESTS) {
                throw QuotaExceededException("Gemini HTTP 429 (quota/rate limit): ${payload.take(500)}")
            }
            if (code !in 200..299) {
                throw IOException("Gemini HTTP $code: ${payload.take(500)}")
            }
            return parseResult(payload)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildRequest(
        audioFile: File,
        sessionContext: List<String>,
        personalizationBlock: String,
        pendingClarificationBlock: String
    ): JSONObject {
        val audioB64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)

        val userParts = JSONArray()
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "audio/aac")
                        .put("data", audioB64)
                )
            )
            .put(
                JSONObject().put(
                    "text",
                    "यह उनकी बात है। ध्यान से सुनिए, समझिए और सूची में से ठीक एक function call कीजिए।"
                )
            )

        val contents = JSONArray()
        for (note in sessionContext) {
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", "पिछली बात: $note")))
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", userParts))

        val sysParts = JSONArray().put(JSONObject().put("text", SystemPrompt.TEXT))
        if (personalizationBlock.isNotBlank()) {
            sysParts.put(JSONObject().put("text", personalizationBlock))
        }
        if (pendingClarificationBlock.isNotBlank()) {
            sysParts.put(JSONObject().put("text", pendingClarificationBlock))
        }

        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", sysParts))
            .put("contents", contents)
            .put(
                "tools",
                JSONArray().put(JSONObject().put("functionDeclarations", IntentSchema.declarations()))
            )
            .put(
                "toolConfig",
                JSONObject().put("functionCallingConfig", JSONObject().put("mode", "ANY"))
            )
            .put("generationConfig", JSONObject().put("temperature", 0.2))
    }

    private fun parseResult(payload: String): Result {
        val root = JSONObject(payload)

        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason")
            Log.w(TAG, "no candidates in response; blockReason=$blockReason")
            return clarifyResult()
        }

        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return clarifyResult()

        for (i in 0 until parts.length()) {
            val call = parts.getJSONObject(i).optJSONObject("functionCall") ?: continue
            val name = call.optString("name")
            if (name.isBlank()) continue
            val args = call.optJSONObject("args") ?: JSONObject()
            val reply = args.optString("spoken_reply_hindi").ifBlank {
                if (name == "clarify") DEFAULT_CLARIFY else DEFAULT_OK
            }
            return Result(
                name,
                args,
                reply,
                args.optString("heard_text"),
                args.optString("pending_intent"),
                args.optString("pending_context")
            )
        }

        Log.w(TAG, "candidate carried no functionCall part: ${payload.take(300)}")
        return clarifyResult()
    }

    private fun clarifyResult() = Result("clarify", JSONObject(), DEFAULT_CLARIFY)

    companion object {
        private const val TAG = "GeminiClient"

        /** `java.net.HttpURLConnection` has no constant for 429. */
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** Retries for a transient network failure (timeout/reset) — not for 429 or a bad-request HTTP status. */
        private const val MAX_RETRIES = 2

        /** Base delay for exponential backoff between retries: 500ms, then 1000ms. */
        private const val RETRY_BASE_DELAY_MS = 500L

        /**
         * Free-tier model with audio input + function calling. Swap here if quota/model changes.
         *
         * History:
         * - Phase 0-2: `gemini-2.5-flash`.
         * - 2026-09-04: `gemini-2.5-flash-lite`, after `2.5-flash` hit its free-tier daily cap.
         * - 2026-09-06: `gemini-3.5-flash-lite`. AI Studio usage charts showed the **whole 2.5
         *   generation** (`2.5-flash` AND `2.5-flash-lite`) pinned to a hard ~20 requests/day
         *   account-wide ceiling — Google's 2025-12-07 free-tier quota cut hit the 2.5 series
         *   hard, and the lite variant shares that squeezed pool, so the earlier swap bought
         *   nothing. Gemini 3.x has its own, far larger free-tier allowance.
         *
         * `gemini-3.5-flash-lite` (GA, released 2026-07-21) verified against Google's current
         * model docs before this swap: supported inputs are "Text, Image, Video, Audio, and PDF"
         * (audio input intact), function calling / tool use listed as Supported. The 3.5
         * generation also brought improved audio-ASR quality, which is directly useful for the
         * still-untuned Odia understanding. Request/response shape, `toolConfig.mode=ANY`, and
         * inline `audio/aac` input are standard `generateContent` surface, unchanged across models.
         *
         * Fallback if 3.5-flash-lite ever regresses on this prompt/schema: `gemini-3.1-flash-lite`
         * (also GA, also audio + function calling, positioned as the stable long-term low-cost
         * option). The bare `gemini-3-flash-lite` string does not exist — 3.x lite ships as point
         * releases (`3.1`, `3.5`).
         */
        private const val MODEL = "gemini-3.5-flash-lite"

        private const val DEFAULT_CLARIFY = "माफ़ कीजिए, मैं ठीक से समझ नहीं पाई। कृपया दोबारा बोलिए।"
        private const val DEFAULT_OK = "ठीक है।"
    }
}
