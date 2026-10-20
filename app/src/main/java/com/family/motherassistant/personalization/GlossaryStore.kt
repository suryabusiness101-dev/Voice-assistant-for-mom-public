package com.family.motherassistant.personalization

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A small local vocabulary of words this one user actually uses — contact names as she
 * says them, place names, stock command phrases — persisted as a flat JSON file in
 * `filesDir`. No Room / DB dependency: the data set is tiny and read once per turn.
 *
 * Contact names are auto-populated from her Android address book (see [syncFromContacts]);
 * anything else is added by hand via [addManual]. The whole thing is rendered into a compact
 * "known names/phrases" block ([promptBlock]) that rides along with each Gemini request so
 * mis-hears on familiar words self-correct.
 */
class GlossaryStore(context: Context) {

    enum class Kind(val json: String) {
        NAME("name"), PHRASE("phrase"), PLACE("place");

        companion object {
            fun from(s: String?): Kind = values().firstOrNull { it.json == s } ?: PHRASE
        }
    }

    data class Entry(val term: String, val kind: Kind, val fromContacts: Boolean)

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private var cache: MutableList<Entry>? = null

    @Synchronized
    fun all(): List<Entry> = load().toList()

    /**
     * Add a hand-entered term. Returns false if blank or already present (case-insensitive).
     */
    @Synchronized
    fun addManual(term: String, kind: Kind): Boolean {
        val clean = term.trim()
        if (clean.isEmpty()) return false
        val list = load()
        if (list.any { it.term.equals(clean, ignoreCase = true) }) return false
        list.add(Entry(clean, kind, fromContacts = false))
        save(list)
        return true
    }

    /**
     * Replace every contacts-sourced entry with a fresh read of her address book, leaving
     * manual entries untouched. Caller must already hold `READ_CONTACTS`. Returns the number
     * of contact names now stored. A transient empty read is ignored (never wipes).
     */
    @Synchronized
    fun syncFromContacts(): Int {
        val names = queryContactNames()
        val list = load()
        if (names.isEmpty()) return list.count { it.fromContacts }

        list.removeAll { it.fromContacts }
        for (n in names) {
            if (list.any { it.term.equals(n, ignoreCase = true) }) continue
            list.add(Entry(n, Kind.NAME, fromContacts = true))
        }
        save(list)
        return names.size
    }

    /** Compact, length-capped rendering for the Gemini prompt. Empty string when nothing is known. */
    fun promptBlock(): String {
        val list = all()
        if (list.isEmpty()) return ""

        val sb = StringBuilder()
        fun line(label: String, kind: Kind, cap: Int) {
            val items = list.filter { it.kind == kind }.map { it.term }
            if (items.isEmpty()) return
            sb.append(label).append(": ").append(items.take(cap).joinToString("، ")).append('\n')
        }
        line("known names", Kind.NAME, MAX_NAMES_IN_PROMPT)
        line("known places", Kind.PLACE, MAX_OTHER_IN_PROMPT)
        line("known phrases", Kind.PHRASE, MAX_OTHER_IN_PROMPT)
        return sb.toString().trimEnd()
    }

    private fun queryContactNames(): List<String> {
        val out = LinkedHashSet<String>()
        val proj = arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        try {
            appContext.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                proj,
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} != 0",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                if (idx < 0) return emptyList()
                while (c.moveToNext() && out.size < MAX_CONTACTS_STORED) {
                    val name = c.getString(idx)?.trim().orEmpty()
                    if (name.isNotEmpty() && name.length <= MAX_NAME_LEN) out.add(name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "contact read failed: ${e.message}")
        }
        return out.toList()
    }

    private fun load(): MutableList<Entry> {
        cache?.let { return it }
        val list = mutableListOf<Entry>()
        try {
            if (file.exists()) {
                val arr = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val term = o.optString("term").trim()
                    if (term.isEmpty()) continue
                    list.add(Entry(term, Kind.from(o.optString("kind")), o.optString("source") == "contacts"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "glossary load failed, starting empty: ${e.message}")
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
                        .put("term", e.term)
                        .put("kind", e.kind.json)
                        .put("source", if (e.fromContacts) "contacts" else "manual")
                )
            }
            file.writeText(JSONObject().put("entries", arr).toString())
        } catch (e: Exception) {
            Log.w(TAG, "glossary save failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GlossaryStore"
        private const val FILE_NAME = "glossary.json"
        private const val MAX_CONTACTS_STORED = 200
        private const val MAX_NAME_LEN = 40
        private const val MAX_NAMES_IN_PROMPT = 40
        private const val MAX_OTHER_IN_PROMPT = 20
    }
}
