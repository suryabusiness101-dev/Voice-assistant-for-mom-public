package com.family.motherassistant.personalization

/**
 * Assembles the single compact personalization block that gets appended to the Gemini
 * system prompt: the glossary as a "known names/phrases" list, plus recent corrections as
 * few-shot examples. A hard character cap guarantees it can never balloon the request,
 * however large the glossary grows.
 */
object Personalization {

    private const val MAX_CHARS = 1500

    fun promptBlock(glossary: GlossaryStore, corrections: CorrectionLog): String {
        val g = glossary.promptBlock()
        val c = corrections.promptBlock()
        if (g.isBlank() && c.isBlank()) return ""

        val sb = StringBuilder()
        sb.append("--- extra context about this specific user (use it silently, never read it aloud) ---\n")
        if (g.isNotBlank()) sb.append(g).append('\n')
        if (c.isNotBlank()) sb.append('\n').append(c).append('\n')
        sb.append("When something you hear is close to a known name or phrase above, prefer that.\n")
        sb.append("--- end extra context ---")

        val out = sb.toString()
        return if (out.length <= MAX_CHARS) out else out.take(MAX_CHARS)
    }
}
