package com.family.motherassistant.personalization

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A short rolling memory of "she said X, she actually meant Y" pairs, newest first, capped at
 * [MAX]. Persisted as a flat JSON file in `filesDir`. The most recent [FEW_SHOT] pairs are
 * rendered ([promptBlock]) as few-shot examples appended to the Gemini prompt, so a mistake
 * flagged once tends not to repeat.
 */
class CorrectionLog(context: Context) {

    data class Correction(val heard: String, val meant: String, val ts: Long)

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private var cache: MutableList<Correction>? = null

    /** Record a correction. Newest goes first; exact duplicate pairs are collapsed; list is capped. */
    @Synchronized
    fun add(heard: String, meant: String) {
        val h = heard.trim()
        val m = meant.trim()
        if (m.isEmpty()) return

        val list = load()
        list.add(0, Correction(h, m, System.currentTimeMillis()))

        val seen = HashSet<Pair<String, String>>()
        val deduped = list
            .filter { seen.add(it.heard.lowercase() to it.meant.lowercase()) }
            .take(MAX)
            .toMutableList()
        save(deduped)
    }

    @Synchronized
    fun recent(n: Int = FEW_SHOT): List<Correction> = load().take(n)

    /** Few-shot block for the Gemini prompt. Empty string when there are no corrections yet. */
    fun promptBlock(): String {
        val items = recent()
        if (items.isEmpty()) return ""

        val sb = StringBuilder("past mis-hears to learn from (do not repeat these mistakes):\n")
        for (c in items) {
            val heard = if (c.heard.isBlank()) "(unclear audio)" else c.heard.take(MAX_FIELD_LEN)
            sb.append("- she said: \"").append(heard)
                .append("\" -> she meant: \"").append(c.meant.take(MAX_FIELD_LEN)).append("\"\n")
        }
        return sb.toString().trimEnd()
    }

    private fun load(): MutableList<Correction> {
        cache?.let { return it }
        val list = mutableListOf<Correction>()
        try {
            if (file.exists()) {
                val arr = JSONObject(file.readText()).optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val meant = o.optString("meant").trim()
                    if (meant.isEmpty()) continue
                    list.add(Correction(o.optString("heard").trim(), meant, o.optLong("ts")))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "correction log load failed, starting empty: ${e.message}")
        }
        cache = list
        return list
    }

    private fun save(list: MutableList<Correction>) {
        cache = list
        try {
            val arr = JSONArray()
            for (c in list) {
                arr.put(
                    JSONObject()
                        .put("heard", c.heard)
                        .put("meant", c.meant)
                        .put("ts", c.ts)
                )
            }
            file.writeText(JSONObject().put("items", arr).toString())
        } catch (e: Exception) {
            Log.w(TAG, "correction log save failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CorrectionLog"
        private const val FILE_NAME = "corrections.json"
        private const val MAX = 30
        private const val FEW_SHOT = 8
        private const val MAX_FIELD_LEN = 120
    }
}
