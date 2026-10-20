package com.family.motherassistant.gemini

/**
 * Session-only follow-up state. When a turn ends by asking her a question — a Gemini
 * `clarify` that named the one detail it still needs, or an ambiguous-contact re-ask
 * ("say the full name") — the next turn carries this note so her answer completes the
 * original request instead of being read as a brand-new, unrelated command.
 *
 * Built fresh each time from the intent + a short context line, held in memory by
 * [com.family.motherassistant.MainActivity] for exactly one turn, then cleared. Never
 * written to disk — consistent with the app's no-memorization design; gone on restart.
 */
object PendingClarification {

    /**
     * @param originalIntent the function to finish once she answers (e.g. `send_sms`),
     *        or "" if the pending question is open-ended.
     * @param context one short English line of what is known / what was asked.
     * @return the system-instruction note, or "" when [context] is blank (nothing pending).
     */
    fun block(originalIntent: String, context: String): String {
        if (context.isBlank()) return ""
        return buildString {
            append("PENDING FOLLOW-UP FROM THE PREVIOUS TURN\n")
            append("The previous turn asked her a question and is waiting for her answer.\n")
            if (originalIntent.isNotBlank()) {
                append("- request to finish once she answers: ").append(originalIntent).append('\n')
            }
            append("- what was asked / already known: ").append(context).append('\n')
            append(
                "If this new audio is her answer to that question, finish the original request: " +
                    "take the missing piece from what she just said and call that function. Do NOT " +
                    "route her answer to casual_chat or to another clarify just because it sounds " +
                    "like a plain sentence or a bare name. If instead she has clearly moved on to a " +
                    "different, unrelated command, ignore this note and handle the new command. " +
                    "Never read this note aloud."
            )
        }
    }
}
