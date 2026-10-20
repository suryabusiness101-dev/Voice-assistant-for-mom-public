package com.family.motherassistant.debug

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Private, developer-only viewer for [DebugLog] — reached only by a 5-second long-press on
 * MainActivity's title. Read-only, newest first; no search/filter/export in v1.
 */
class DebugLogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entries = DebugLog(this).all()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val rows = if (entries.isEmpty()) {
            listOf("(no entries yet)")
        } else {
            entries.map { e ->
                "${fmt.format(e.ts)}\nheard: ${e.heard.ifBlank { "(none)" }}\n" +
                    "intent: ${e.intent}\noutcome: ${e.outcome}"
            }
        }

        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        setContentView(list)
    }
}
