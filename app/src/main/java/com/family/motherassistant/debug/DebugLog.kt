package com.family.motherassistant.debug

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A private, on-device rolling log of every turn — timestamp, what she said, which intent it
 * resolved to, and what actually happened — for my own review only, never spoken or shown to
 * her. Mirrors [com.family.motherassistant.personalization.CorrectionLog]'s pattern: flat JSON
 * file in `filesDir`, newest first, capped at [MAX]. No new dependency, no network call.
 *
 * Reached only via a 5-second long-press on the main screen's title (see MainActivity) —
 * no visible hint, no password, not linked from anywhere else in the UI.
 */
class DebugLog(context: Context) {

    data class Entry(val ts: Long, val heard: String, val intent: String, val outcome: String)

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private var cache: MutableList<Entry>? = null

    @Synchronized
    fun add(heard: String, intent: String, outcome: String) {
        val list = load()
        list.add(0, Entry(System.currentTimeMillis(), heard.trim(), intent.trim(), outcome.trim()))
        save(list.take(MAX).toMutableList())
    }

    @Synchronized
    fun all(): List<Entry> = load()

    private fun load(): MutableList<Entry> {
        cache?.let { return it }
        val list = mutableListOf<Entry>()
        try {
            if (file.exists()) {
                val arr = JSONObject(file.readText()).optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        Entry(
                            o.optLong("ts"),
                            o.optString("heard"),
                            o.optString("intent"),
                            o.optString("outcome")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "debug log load failed, starting empty: ${e.message}")
        }
        cache = list
        return list
    }

    private fun save(list: MutableList<Entry>) {
        cache = list
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject()
                        .put("ts", e.ts)
                        .put("heard", e.heard)
                        .put("intent", e.intent)
                        .put("outcome", e.outcome)
                )
            }
            file.writeText(JSONObject().put("items", arr).toString())
        } catch (e: Exception) {
            Log.w(TAG, "debug log save failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DebugLog"
        private const val FILE_NAME = "debug_log.json"
        private const val MAX = 150
    }
}
