package com.family.motherassistant.actions

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.util.Log
import com.family.motherassistant.R
import java.net.URLEncoder

/**
 * Tier 1 actions — direct Android APIs, no Accessibility Service. Gemini decides *what*
 * (via [com.family.motherassistant.router.IntentRouter]); this class decides *how*, and —
 * critically — reports back what actually happened.
 *
 * Every method returns a deterministic [Result]:
 *  - the spoken Hindi line always reflects the *real* outcome (success / missing permission /
 *    name not found / failure), never Gemini's optimistic pre-composed guess;
 *  - [Result.followUp], when set, tells the Activity to ask for a permission or open the
 *    DND-access settings screen, after which the user can simply repeat the command.
 *
 * Safety principle carried over from Phase 1: when a contact name is unknown or matches
 * several people, nothing is dialled or texted — the reply asks her to say it again.
 */
class SystemActions(private val context: Context) {

    data class Result(
        val spokenReplyHindi: String,
        val followUp: FollowUp? = null,
        /**
         * Set when this action ended by asking her a follow-up question (an ambiguous
         * contact name, a missing message). The Activity remembers it for exactly the
         * next turn so her answer completes the original request. Session-only.
         */
        val pending: Pending? = null,
        /**
         * True only when a `share_onscreen` actually handed off to
         * [com.family.motherassistant.service.AssistantAccessibilityService.beginShareFlow] —
         * i.e. its async [com.family.motherassistant.service.AssistantAccessibilityService.shareResultListener]
         * callback is guaranteed to fire eventually. [com.family.motherassistant.core.AssistantController]
         * uses this (not "no followUp and no pending") to decide whether to set `shareRunning`,
         * because [com.family.motherassistant.actions.AccessibilityActions.shareOnScreen] has several
         * terminal failures (no foreground app seen, source is WhatsApp itself, no launch intent)
         * that also return with no followUp/pending but never start a flow — treating those as
         * "handed off" left `shareRunning` stuck true forever, blocking the bubble/button trigger
         * for the rest of the session. Unused/false for every other action.
         */
        val asyncShareStarted: Boolean = false,
        /**
         * Extra detail for the private [com.family.motherassistant.debug.DebugLog] only — never
         * spoken. Used by `troubleshoot_app` to record which rung she reached and whether it
         * fixed the problem. Empty for every other action.
         */
        val debugNote: String = ""
    )

    /** A question this turn left open — [context] is a short English line for Gemini's next call. */
    data class Pending(val originalIntent: String, val context: String)

    /** Something the Activity must do on the UI thread before the action can succeed. */
    sealed class FollowUp {
        /** A runtime permission is missing — request it, then she repeats the command. */
        data class RequestPermission(val permission: String) : FollowUp()

        /** Ringer changes need one-time Notification Policy (DND) access — open that screen. */
        object OpenDndAccessSettings : FollowUp()

        /**
         * Phase 3 share needs the Accessibility Service enabled — open Settings →
         * Accessibility. The parked intent re-runs from [MainActivity.onResume] once the
         * service reports itself connected.
         */
        object OpenAccessibilitySettings : FollowUp()
    }

    // ---- ringer -----------------------------------------------------------------

    fun setRingerMode(mode: String): Result {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Any ringer change that crosses the silent/vibrate boundary toggles DND on API 24+,
        // which the platform blocks unless the app holds Notification Policy access.
        if (!nm.isNotificationPolicyAccessGranted) {
            return Result(str(R.string.act_ringer_needs_dnd), FollowUp.OpenDndAccessSettings)
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val (ringerMode, confirm) = when (mode.lowercase()) {
            "silent" -> AudioManager.RINGER_MODE_SILENT to R.string.act_ringer_silent
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE to R.string.act_ringer_vibrate
            "normal" -> AudioManager.RINGER_MODE_NORMAL to R.string.act_ringer_normal
            else -> return Result(str(R.string.act_ringer_failed))
        }
        return try {
            am.ringerMode = ringerMode
            Result(str(confirm))
        } catch (e: Exception) {
            Log.e(TAG, "setRingerMode($mode) failed", e)
            Result(str(R.string.act_ringer_failed))
        }
    }

    // ---- save contact ---------------------------------------------------------

    fun saveContact(name: String, phoneNumber: String): Result {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            return Result(
                str(R.string.act_contact_needs_perm),
                FollowUp.RequestPermission(Manifest.permission.WRITE_CONTACTS)
            )
        }
        val cleanName = name.trim()
        if (cleanName.isBlank() || cleanName.length > 60 || cleanName.none { it.isLetter() }) {
            return Result(str(R.string.act_contact_bad_name))
        }
        val cleanNumber = normalizeNumber(phoneNumber)
        if (cleanNumber == null) {
            return Result(str(R.string.act_contact_bad_number))
        }

        // Insert against a real account (the Google account existing contacts already use)
        // so the new contact syncs and shows in the default Contacts view. A null-account
        // raw contact is "device only" and Google/Motorola Contacts hide it by default —
        // that was the Phase 2 "saved successfully but nowhere to be seen" bug.
        val account = pickContactAccount()

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, cleanName)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, cleanNumber)
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                )
                .build()
        )

        return try {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawContactUri = results.firstOrNull()?.uri
            if (rawContactUri == null) {
                // applyBatch returned without throwing but produced no raw-contact URI —
                // a silent no-op, not a success. Never inspecting this was the Phase 2 bug.
                Log.e(TAG, "saveContact: applyBatch returned no raw-contact URI (results=$results)")
                Result(str(R.string.act_contact_failed))
            } else {
                Log.i(
                    TAG,
                    "saveContact: created raw contact at $rawContactUri " +
                        "(account=${account.name ?: "<local>"}/${account.type ?: "<local>"})"
                )
                Result(str(R.string.act_contact_saved))
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveContact failed", e)
            Result(str(R.string.act_contact_failed))
        }
    }

    /** An account to attach a new raw contact to. Both fields null = a local ("device only") contact. */
    private data class TargetAccount(val name: String?, val type: String?)

    /**
     * Pick the account a newly saved contact should belong to. Preference order:
     *  1. the `com.google` account most of her existing contacts already use (syncs, always visible);
     *  2. failing that, whatever account type is most common among her contacts;
     *  3. failing that — or with no READ_CONTACTS — a local contact (null account).
     *
     * Read straight from the contacts provider (needs only the READ_CONTACTS the app already
     * requests at setup), so no AccountManager / GET_ACCOUNTS dependency and it adapts to
     * whatever account her phone is actually signed into.
     */
    private fun pickContactAccount(): TargetAccount {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return TargetAccount(null, null)
        return try {
            val counts = HashMap<Pair<String, String>, Int>()
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts.ACCOUNT_NAME,
                    ContactsContract.RawContacts.ACCOUNT_TYPE
                ),
                "${ContactsContract.RawContacts.DELETED} = 0",
                null,
                null
            )?.use { c ->
                val nIdx = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
                val tIdx = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                if (nIdx >= 0 && tIdx >= 0) {
                    while (c.moveToNext()) {
                        val n = c.getString(nIdx) ?: continue
                        val t = c.getString(tIdx) ?: continue
                        counts[n to t] = (counts[n to t] ?: 0) + 1
                    }
                }
            }
            val best = counts.entries.filter { it.key.second == "com.google" }.maxByOrNull { it.value }
                ?: counts.entries.maxByOrNull { it.value }
            if (best != null) TargetAccount(best.key.first, best.key.second) else TargetAccount(null, null)
        } catch (e: Exception) {
            Log.w(TAG, "pickContactAccount failed; saving as a local contact", e)
            TargetAccount(null, null)
        }
    }

    // ---- call ---------------------------------------------------------------

    fun callContact(name: String): Result {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return Result(
                str(R.string.act_call_needs_perm),
                FollowUp.RequestPermission(Manifest.permission.READ_CONTACTS)
            )
        }
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return Result(
                str(R.string.act_call_needs_perm),
                FollowUp.RequestPermission(Manifest.permission.CALL_PHONE)
            )
        }
        return when (val match = resolveContact(name)) {
            is ContactMatch.None -> Result(str(R.string.act_call_not_found))
            is ContactMatch.Many -> Result(
                str(R.string.act_call_ambiguous),
                pending = Pending(
                    "call_contact",
                    "she asked to call \"$name\", which matches several saved contacts " +
                        "(${match.names.joinToString(", ")}); her next words say which one to call"
                )
            )
            is ContactMatch.One -> placeCall(match.number, str(R.string.act_call_placing, match.name))
        }
    }

    /**
     * troubleshoot_app's last rung: call the configured helper. A set [number] is always dialled
     * directly; [name] is then only the spoken label. Only when [number] is blank is [name] looked
     * up in contacts, and then only an exact whole-name match counts — never the phonetic/fuzzy
     * tiers, so a sound-alike contact can't get the call. `debugNote` records the route
     * ("via number" / "via name …") — never the number itself.
     */
    fun callHelper(name: String, number: String): Result {
        if (name.isBlank() && number.isBlank()) return Result(str(R.string.act_call_not_found))
        if (number.isNotBlank()) {
            if (!hasPermission(Manifest.permission.CALL_PHONE)) {
                return Result(
                    str(R.string.act_call_needs_perm),
                    FollowUp.RequestPermission(Manifest.permission.CALL_PHONE)
                )
            }
            val spoken = if (name.isBlank()) str(R.string.act_call_placing_home) else str(R.string.act_call_placing, name)
            return placeCall(number, spoken, "via number")
        }
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return Result(
                str(R.string.act_call_needs_perm),
                FollowUp.RequestPermission(Manifest.permission.READ_CONTACTS)
            )
        }
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return Result(
                str(R.string.act_call_needs_perm),
                FollowUp.RequestPermission(Manifest.permission.CALL_PHONE)
            )
        }
        return when (val match = resolveContact(name, exactOnly = true)) {
            is ContactMatch.One -> placeCall(match.number, str(R.string.act_call_placing, match.name), "via name (exact match)")
            // No pending follow-up on purpose: re-asking would go through call_contact's fuzzy tiers.
            is ContactMatch.None -> Result(str(R.string.act_call_helper_not_found, name), debugNote = "via name: no exact match, no call")
            is ContactMatch.Many -> Result(str(R.string.act_call_helper_not_found, name), debugNote = "via name: several exact matches, no call")
        }
    }

    private fun placeCall(number: String, spokenOnSuccess: String, debugNote: String = ""): Result {
        val ok = dial(number)
        val note = if (debugNote.isEmpty() || ok) debugNote else "$debugNote, call failed"
        return Result(if (ok) spokenOnSuccess else str(R.string.act_call_failed), debugNote = note)
    }

    private fun dial(number: String): Boolean = try {
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(TAG, "call failed", e)
        false
    }

    // ---- send SMS ----------------------------------------------------------

    /**
     * Prepare an SMS in the default Messages app with the recipient and body prefilled —
     * she taps "send" herself. Deliberately *not* `SmsManager.sendTextMessage()`: Google's
     * enhanced fraud protection auto-denies the `SEND_SMS` runtime permission for sideloaded
     * apps in some regions (India included), so a direct send is dead on arrival on her phone.
     * `Intent.ACTION_SENDTO` with an `smsto:` URI needs no dangerous permission and sidesteps
     * the block entirely. READ_CONTACTS is still needed to turn
     * the spoken name into a number.
     */
    fun sendSms(name: String, message: String): Result {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return Result(
                str(R.string.act_sms_needs_perm),
                FollowUp.RequestPermission(Manifest.permission.READ_CONTACTS)
            )
        }
        val text = message.trim()
        if (text.isBlank()) {
            return Result(str(R.string.act_sms_empty))
        }
        return when (val match = resolveContact(name)) {
            is ContactMatch.None -> Result(str(R.string.act_sms_not_found))
            is ContactMatch.Many -> Result(
                str(R.string.act_sms_ambiguous),
                pending = Pending(
                    "send_sms",
                    "she asked to send the message \"$text\" to \"$name\", which matches several saved " +
                        "contacts (${match.names.joinToString(", ")}); her next words say which person — " +
                        "send that same message to the one she names"
                )
            )
            is ContactMatch.One -> try {
                val intent = Intent(
                    Intent.ACTION_SENDTO,
                    Uri.fromParts("smsto", match.number, null)
                ).apply {
                    putExtra("sms_body", text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Result(str(R.string.act_sms_ready, match.name))
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "no app handles ACTION_SENDTO (smsto:)", e)
                Result(str(R.string.act_sms_failed))
            } catch (e: Exception) {
                Log.e(TAG, "sendSms failed", e)
                Result(str(R.string.act_sms_failed))
            }
        }
    }

    // ---- alarm -----------------------------------------------------------

    fun setAlarm(hour: Int, minute: Int): Result {
        if (hour !in 0..23 || minute !in 0..59) {
            return Result(str(R.string.act_alarm_bad_time))
        }
        // No resolveActivity() precheck — under Android 11+ package visibility that would
        // report "no clock app" even when one exists. Just start it and catch the miss.
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result(str(R.string.act_alarm_set, spokenTime(hour, minute)))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "no clock app handles ACTION_SET_ALARM", e)
            Result(str(R.string.act_alarm_no_clock))
        } catch (e: Exception) {
            Log.e(TAG, "setAlarm failed", e)
            Result(str(R.string.act_alarm_failed))
        }
    }

    // ---- web / youtube search -----------------------------------------------

    /**
     * Open a live Google search for [query] — she gets a real, current answer instead of
     * Gemini guessing from its own possibly-outdated knowledge. No special permission needed;
     * an implicit `https` [Intent.ACTION_VIEW] resolves to whatever handles web links (Chrome
     * on her phone).
     */
    fun searchWeb(query: String): Result {
        val q = query.trim()
        if (q.isBlank()) return Result(str(R.string.act_search_web_empty))
        return try {
            val url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result(str(R.string.act_search_web_opening, q))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "searchWeb: no app handles the search URL", e)
            Result(str(R.string.act_search_web_failed))
        } catch (e: Exception) {
            Log.e(TAG, "searchWeb failed", e)
            Result(str(R.string.act_search_web_failed))
        }
    }

    /** Same idea as [searchWeb], but a YouTube search-results URL — opens in the YouTube app if installed. */
    fun searchYoutube(query: String): Result {
        val q = query.trim()
        if (q.isBlank()) return Result(str(R.string.act_search_youtube_empty))
        return try {
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result(str(R.string.act_search_youtube_opening, q))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "searchYoutube: no app handles the search URL", e)
            Result(str(R.string.act_search_youtube_failed))
        } catch (e: Exception) {
            Log.e(TAG, "searchYoutube failed", e)
            Result(str(R.string.act_search_youtube_failed))
        }
    }

    // ---- contact matching --------------------------------------------------

    private sealed class ContactMatch {
        object None : ContactMatch()
        data class Many(val names: List<String>) : ContactMatch()
        data class One(val name: String, val number: String) : ContactMatch()
    }

    /**
     * Resolve [query] to a saved contact with a phone number, in ordered tiers. The first
     * tier that *any* contact reaches wins, and only that tier's survivors are checked for
     * ambiguity.
     *
     * Why tiers: Gemini's transcription of a spoken name wobbles between attempts — the same
     * contact can come back spelled several different ways. A char-only match is too brittle
     * for that; a flat loose match rings the wrong person. So:
     *
     *   T0  whole name equal after normalization      → returned immediately (exact)
     *   T1  substring either way, or a shared whole token (still char-equal)
     *   T2  whole name sounds the same (Soundex)
     *   T3  a shared token sounds the same, or the whole strings are a small typo apart
     *
     * Char beats phonetic at the same granularity, so a name still prefers a contact that
     * contains it over a phonetic neighbour. Phonetic tiers are gated on length ≥ 4 so short
     * names still need a near-exact match.
     *
     * Coverage: for a **multi-word** query, a contact that accounts for *more* of the words she
     * said outranks one that only shares a generic token (such as a carrier name like "jio").
     * Only kicks in when the query has ≥2 tokens and some contact
     * covers ≥2 — single-name lookups fall straight through to the tier order above.
     *
     * More than one distinct person still tied at the end → [ContactMatch.Many] (ask again);
     * nothing reaches any tier → [ContactMatch.None].
     *
     * [exactOnly] stops after T0: only a whole-name match counts, and two exact-name contacts with
     * different numbers are [ContactMatch.Many] rather than "first one wins".
     */
    private fun resolveContact(query: String, exactOnly: Boolean = false): ContactMatch {
        val q = normalizeName(query)
        if (q.isBlank()) return ContactMatch.None

        val rows = ArrayList<Pair<String, String>>() // display name -> number
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numIdx < 0) return ContactMatch.None
            while (it.moveToNext()) {
                val name = it.getString(nameIdx)?.trim().orEmpty()
                val num = it.getString(numIdx)?.trim().orEmpty()
                if (name.isNotBlank() && num.isNotBlank()) rows.add(name to num)
            }
        }
        if (rows.isEmpty()) {
            Log.w(TAG, "resolveContact(query=\"$query\" norm=\"$q\"): 0 phone rows read — permission or empty phonebook")
            return ContactMatch.None
        }

        if (exactOnly) {
            val exact = rows.filter { (name, _) -> normalizeName(name) == q }.distinctBy { digitsOnly(it.second) }
            Log.i(TAG, "resolveContact(query=\"$query\" norm=\"$q\"): exact-only -> ${exact.size} match(es) (rows=${rows.size})")
            return when (exact.size) {
                0 -> ContactMatch.None
                1 -> ContactMatch.One(exact[0].first, dialable(exact[0].second))
                else -> ContactMatch.Many(exact.map { it.first })
            }
        }

        // T0 — exact whole-name match wins outright, ahead of the tiered pass and its
        // ambiguity check, so longer names that merely contain the query can't make it ambiguous.
        rows.firstOrNull { (name, _) -> normalizeName(name) == q }?.let {
            Log.i(TAG, "resolveContact(query=\"$query\" norm=\"$q\"): T0 exact -> \"${it.first}\" (rows=${rows.size})")
            return ContactMatch.One(it.first, dialable(it.second))
        }

        val qTokens = q.split(' ').filter { it.isNotBlank() }
        // Phonetic signatures, only worth computing for names long enough not to collide wildly.
        val qKey = if (q.replace(" ", "").length >= 4) phoneticKey(q) else ""
        val qTokenKeys = qTokens.filter { it.length >= 4 }.map { soundex(it) }.filter { it.isNotEmpty() }

        class Score(val coverage: Int, val tier: Int)

        fun scoreOf(name: String): Score {
            val n = normalizeName(name)
            if (n.isBlank()) return Score(0, 99)
            val nTokens = n.split(' ').filter { it.isNotBlank() }
            val nTokenKeys = nTokens.filter { it.length >= 4 }.map { soundex(it) }.filter { it.isNotEmpty() }

            // coverage — how many distinct query words this contact accounts for (char, sound, or typo).
            val coverage = qTokens.count { qt ->
                val k = if (qt.length >= 4) soundex(qt) else ""
                nTokens.any { it == qt } ||
                    (k.isNotEmpty() && nTokenKeys.contains(k)) ||
                    nTokens.any { levenshtein(qt, it) <= typoBudget(qt) }
            }

            // tier — overall match quality; lower is better, 99 = no match at all.
            val tier = when {
                n.contains(q) || q.contains(n) -> 1                                   // T1 substring
                nTokens.any { nt -> qTokens.any { it == nt } } -> 1                    // T1 shared whole token
                qKey.isNotEmpty() && phoneticKey(n) == qKey -> 2                       // T2 whole name sounds the same
                nTokenKeys.any { nk -> qTokenKeys.contains(nk) } -> 3                  // T3 a token sounds the same
                qTokens.any { qt -> nTokens.any { nt -> levenshtein(qt, nt) <= typoBudget(qt) } } -> 3
                levenshtein(n, q) <= typoBudget(q) -> 3                               // T3 whole string a typo apart
                else -> 99
            }
            return Score(coverage, tier)
        }

        val scored = rows.map { it to scoreOf(it.first) }.filter { it.second.tier < 99 }
        if (scored.isEmpty()) {
            Log.w(TAG, "resolveContact(query=\"$query\" norm=\"$q\"): no match across ${rows.size} contacts -> None")
            return ContactMatch.None
        }

        // Multi-word query: keep only the contacts that cover the most of what she said, so a
        // generic shared token ("jio", "airtel") can't flood the result with near-strangers.
        val coverageDecides = qTokens.size >= 2 && scored.any { it.second.coverage >= 2 }
        val topCov = if (coverageDecides) scored.maxOf { it.second.coverage } else 0
        val pool = if (coverageDecides) scored.filter { it.second.coverage == topCov } else scored

        val bestTier = pool.minOf { it.second.tier }
        val winners = pool.filter { it.second.tier == bestTier }.map { it.first }
        val how = if (coverageDecides) "cov$topCov/T$bestTier" else "T$bestTier"

        // Collapse to distinct people: same normalized name AND same digits = one contact.
        val distinct = winners.distinctBy { (name, num) -> normalizeName(name) + "|" + digitsOnly(num) }
        val distinctPeople = distinct.distinctBy { (name, _) -> normalizeName(name) }
        return if (distinctPeople.size == 1) {
            distinct.first().let {
                Log.i(TAG, "resolveContact(query=\"$query\" norm=\"$q\"): $how -> \"${it.first}\" (rows=${rows.size})")
                ContactMatch.One(it.first, dialable(it.second))
            }
        } else {
            Log.w(
                TAG,
                "resolveContact(query=\"$query\" norm=\"$q\"): $how ambiguous -> " +
                    distinctPeople.joinToString(", ") { "\"${it.first}\"" }
            )
            ContactMatch.Many(distinctPeople.map { it.first })
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun str(resId: Int): String = context.getString(resId)
    private fun str(resId: Int, arg: String): String = context.getString(resId, arg)

    private fun normalizeName(s: String): String =
        s.lowercase()
            // keep letters, digits, AND combining marks (\p{M}) — stripping the marks shattered
            // Devanagari/Odia grapheme clusters (a conjunct like "र्थ" fell apart). Names should reach Gemini
            // romanized (see SystemPrompt), but don't corrupt one if it doesn't.
            .replace(Regex("[^\\p{L}\\p{M}\\p{Nd} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun digitsOnly(s: String): String = s.filter { it.isDigit() }

    /** Strip a contact number down to what the dialer / Messages app want — no spaces or dashes. */
    private fun dialable(s: String): String = s.filter { it.isDigit() || it in "+*#,;" }

    /** Keep an optional leading `+` and 5–15 digits; reject anything else as not a phone number. */
    private fun normalizeNumber(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("[\\s()\\-.]"), "")
        val ok = Regex("^\\+?\\d{5,15}$").matches(trimmed)
        return if (ok) trimmed else null
    }

    /** Longer words tolerate more mis-hearing; short ones must be near-exact. */
    private fun typoBudget(token: String): Int = when {
        token.length <= 3 -> 0
        token.length <= 6 -> 1
        else -> 2
    }

    /**
     * Whole-name phonetic signature: [soundex] of each whitespace token, joined. Latin only —
     * a non-Latin token (Devanagari / Odia) soundexes to "" so an all-non-Latin name yields a
     * blank key and the phonetic tiers are simply skipped for it (Levenshtein still applies).
     */
    private fun phoneticKey(normalized: String): String =
        normalized.split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { soundex(it) }
            .trim()

    /**
     * American Soundex (letter + 3 digits). Collapses the vowel wobble and consonant swaps that
     * Odia→text transcription throws off (e.g. "ph"/"f"). Vowels
     * break a run of same-coded consonants; H and W do not. Returns "" if the word has no A–Z.
     */
    private fun soundex(word: String): String {
        val w = word.uppercase().filter { it in 'A'..'Z' }
        if (w.isEmpty()) return ""
        val sb = StringBuilder()
        var prevCode = soundexCode(w[0])
        for (i in 1 until w.length) {
            val c = w[i]
            val code = soundexCode(c)
            if (code != 0 && code != prevCode) {
                sb.append(code)
                if (sb.length == 3) break
            }
            if (c != 'H' && c != 'W') prevCode = code
        }
        return (w[0] + sb.toString()).padEnd(4, '0').substring(0, 4)
    }

    private fun soundexCode(c: Char): Int = when (c) {
        'B', 'F', 'P', 'V' -> 1
        'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> 2
        'D', 'T' -> 3
        'L' -> 4
        'M', 'N' -> 5
        'R' -> 6
        else -> 0
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** "सुबह 7 बजे", "शाम 6 बजकर 30 मिनट" — natural for the hi-IN TTS to read. */
    private fun spokenTime(hour: Int, minute: Int): String {
        val daypart = when (hour) {
            in 4..11 -> "सुबह"
            in 12..16 -> "दोपहर"
            in 17..20 -> "शाम"
            else -> "रात"
        }
        val raw12 = hour % 12
        val h12 = if (raw12 == 0) 12 else raw12
        return if (minute == 0) "$daypart $h12 बजे" else "$daypart $h12 बजकर $minute मिनट"
    }

    companion object {
        private const val TAG = "SystemActions"
    }
}
