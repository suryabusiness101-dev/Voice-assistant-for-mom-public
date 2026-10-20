package com.family.motherassistant.gemini

import com.family.motherassistant.actions.Troubleshooter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini function-calling tool declarations. Gemini decides WHICH function and fills its args;
 * the local [com.family.motherassistant.router.IntentRouter] decides how — or whether — to run it.
 *
 * Every function carries a required `spoken_reply_hindi` string so [com.family.motherassistant.tts.SpeechOutput]
 * always has something warm to say back, whichever intent fires.
 *
 * Phase 1 registers the tools but wires no real actions — the point is proving the round-trip
 * (audio in → structured intent + Hindi reply → spoken back).
 */
object IntentSchema {

    fun declarations(): JSONArray {
        val fns = JSONArray()

        fns.put(
            fn(
                name = "set_ringer_mode",
                description = "Change the phone's ringer mode (ringtone on, silent, or vibrate).",
                required = listOf("mode"),
                prop("mode", "string", enum = listOf("normal", "silent", "vibrate"))
            )
        )

        fns.put(
            fn(
                name = "save_contact",
                description = "Save a new phone contact.",
                required = listOf("name", "phone_number"),
                prop("name", "string"),
                prop("phone_number", "string")
            )
        )

        fns.put(
            fn(
                name = "call_contact",
                description = "Place a phone call to a saved contact by name.",
                required = listOf("name"),
                prop("name", "string")
            )
        )

        fns.put(
            fn(
                name = "send_sms",
                description = "Send a text message (SMS) to a saved contact by name.",
                required = listOf("name", "message"),
                prop("name", "string"),
                prop("message", "string")
            )
        )

        fns.put(
            fn(
                name = "set_alarm",
                description = "Set a clock alarm at a specific time of day (24-hour clock).",
                required = listOf("hour", "minute"),
                prop("hour", "integer"),
                prop("minute", "integer")
            )
        )

        fns.put(
            fn(
                name = "open_app",
                description = "Open an installed app by its common name (e.g. WhatsApp, YouTube).",
                required = listOf("app_name"),
                prop("app_name", "string")
            )
        )

        fns.put(
            fn(
                name = "share_onscreen",
                description = "Share whatever she is currently viewing in another app (a video, a reel, a " +
                    "photo) into a WhatsApp contact or group. Use only when she names who or which group " +
                    "to send it to.",
                required = listOf("target"),
                prop(
                    "target", "string",
                    description = "The WhatsApp contact or group name to share to, as she says it."
                ),
                prop(
                    "target_app", "string",
                    description = "Optional. The app to share into. Defaults to WhatsApp; leave empty " +
                        "unless she names a different app."
                )
            )
        )

        fns.put(
            fn(
                name = "search_and_open",
                description = "Search for specific content (a show, movie, or video) inside a named app and open " +
                    "the top matching result — e.g. finding a show on Hotstar. Currently only works with Hotstar. " +
                    "Only use when she names both the app and what to look for.",
                required = listOf("app_name", "search_query"),
                prop("app_name", "string", description = "The app to search in, e.g. Hotstar."),
                prop("search_query", "string", description = "The show, movie, or video name to search for, as she said it.")
            )
        )

        fns.put(
            fn(
                name = "switch_profile",
                description = "Switch to her own profile inside a named app when she has landed on the wrong " +
                    "profile (e.g. accidentally on the Kids profile on Hotstar) and wants back on her own. " +
                    "Currently only works with Hotstar. No profile name is needed — this always navigates to the " +
                    "profile switcher and picks whichever profile is not the Kids profile.",
                required = listOf("app_name"),
                prop("app_name", "string", description = "The app whose profile to switch, e.g. Hotstar.")
            )
        )

        fns.put(
            fn(
                name = "search_web",
                description = "Search the web for current information she is asking about — news, current " +
                    "events, prices, facts, or any 'what is/when is/how much is' question whose answer can " +
                    "change over time and you might get wrong by answering from memory. Opens a live web " +
                    "search with her question so she gets a real, current result — do not try to answer such " +
                    "questions yourself. Only for looking something up, not for an action like calling or " +
                    "messaging someone or opening an app to use it.",
                required = listOf("query"),
                prop("query", "string", description = "What to search for, in her own words.")
            )
        )

        fns.put(
            fn(
                name = "search_youtube",
                description = "Search YouTube for a video, song, or channel she names, and open the search " +
                    "results. Use only when she clearly wants to watch or listen to something on YouTube — " +
                    "not for a plain factual question (use `search_web` for that).",
                required = listOf("query"),
                prop("query", "string", description = "What to search for on YouTube, in her own words.")
            )
        )

        fns.put(
            fn(
                name = "troubleshoot_app",
                description = "Use when she complains that an app on her phone is not working properly — " +
                    "won't open, shows a black/blank screen, keeps closing, has no sound, keeps buffering, " +
                    "or won't let her log in (e.g. \"Hotstar kahinki chaluni\"). The app walks her through " +
                    "fixes one step at a time. Also use it for her yes/no reply when a PENDING FOLLOW-UP " +
                    "note says a troubleshoot_app step is waiting for her answer.",
                required = listOf("app_name", "symptom"),
                prop(
                    "app_name", "string",
                    description = "The app that is misbehaving, as she names it, in the Latin alphabet " +
                        "(e.g. Hotstar, YouTube, WhatsApp)."
                ),
                prop(
                    "symptom", "string",
                    description = "What is going wrong. Use other if unclear.",
                    enum = Troubleshooter.SYMPTOMS
                ),
                prop(
                    "answer", "string",
                    description = "Only when answering a pending troubleshoot_app question: yes, no, or " +
                        "unclear, as that note defines them. Omit it entirely for a fresh complaint.",
                    enum = listOf("yes", "no", "unclear")
                )
            )
        )

        fns.put(
            fn(
                name = "casual_chat",
                description = "Use for greetings, small talk, or casual conversation that is not a command or a task " +
                    "(e.g. \"how are you\", \"what did you eat\", \"it's cold today\"). Just reply warmly and briefly " +
                    "in `spoken_reply_hindi`; no action is taken.",
                required = emptyList()
            )
        )

        fns.put(
            fn(
                name = "clarify",
                description = "Use when the request is ambiguous, risky, or unsupported — ask her to repeat or explain " +
                    "instead of guessing. If you are only missing ONE detail of an otherwise clear request (the words " +
                    "of a message, which of two contacts she means, a morning/evening time), also fill `pending_intent` " +
                    "and `pending_context` so her reply on the next turn can be read as the answer.",
                required = emptyList(),
                prop(
                    "pending_intent", "string",
                    description = "When this clarify is waiting on one missing detail of an otherwise clear request, " +
                        "the function to call once she answers: send_sms, call_contact, save_contact, set_alarm, " +
                        "set_ringer_mode, open_app, troubleshoot_app. Leave empty for open-ended confusion."
                ),
                prop(
                    "pending_context", "string",
                    description = "One short English line — what is already known and what you just asked her for. " +
                        "E.g. \"contact name known, need the message text\" or \"asked which of two similar " +
                        "contacts she means for a call\". Leave empty if this is not a fill-in-one-blank clarify."
                )
            )
        )

        return fns
    }

    private fun fn(
        name: String,
        description: String,
        required: List<String>,
        vararg properties: Pair<String, JSONObject>
    ): JSONObject {
        val props = JSONObject()
        for ((key, schema) in properties) props.put(key, schema)
        props.put(
            "spoken_reply_hindi",
            JSONObject()
                .put("type", "string")
                .put("description", "Short warm reply in simple Hindi for TTS to read aloud.")
        )
        props.put(
            "heard_text",
            JSONObject()
                .put("type", "string")
                .put(
                    "description",
                    "Faithful word-for-word transcription of exactly what she said, in her own " +
                        "language (Odia or Hindi). Used to learn from mistakes — do not paraphrase."
                )
        )

        val params = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray(required + "spoken_reply_hindi" + "heard_text"))

        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", params)
    }

    private fun prop(
        name: String,
        type: String,
        description: String? = null,
        enum: List<String>? = null
    ): Pair<String, JSONObject> {
        val schema = JSONObject().put("type", type)
        if (description != null) schema.put("description", description)
        if (enum != null) schema.put("enum", JSONArray(enum))
        return name to schema
    }
}
