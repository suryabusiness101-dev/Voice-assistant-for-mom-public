package com.family.motherassistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import com.family.motherassistant.BuildConfig
import com.family.motherassistant.R
import com.family.motherassistant.net.Connectivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `troubleshoot_app` — walks her through a fixed, deterministic fix ladder when an app
 * misbehaves (first real case: Hotstar showing a black screen after loading, fixed by clearing
 * cache, then data, then logging in again). Gemini only recognizes the complaint and reads her
 * yes/no answers; *which* step comes next is decided here, from [LADDER], never by the model.
 *
 * One rung is spoken per turn. Each turn ends with a [SystemActions.Pending] so her next reply
 * ("haan, chal gaya" / "nahi, abhi bhi nahi") comes back as `troubleshoot_app` with an
 * `answer`, through the existing pending-clarification mechanism.
 *
 * Nothing here touches another app's data. Android gives no third-party app a way to clear a
 * specific other app's cache or data, or to force-stop it (the relevant
 * AOSP permissions are signature/privileged-only) — so the cache/data rungs only open that app's App Info
 * screen and *speak* the taps; she makes them herself. The Accessibility Service is
 * deliberately not used to automate those taps in this version.
 *
 * The clear-data rung is destructive (she gets logged out), so it is always split in two:
 * a spoken warning + "do you have your login and OTP?" question, and only an explicit "yes"
 * opens the screen. "No" skips straight to offering a call; anything unclear re-asks. There is
 * no path that reaches the clear-data instruction without that confirmation.
 *
 * Progress (app, rung, what was asked) is kept in a tiny SharedPreferences record for
 * [SESSION_TTL_MS] — the one deliberate exception to session-only state, because the "restart
 * your phone" rung kills this process: without it, a "still not working" after the reboot would
 * start again from rung 1. Nothing personal is stored; it expires on its own.
 */
class Troubleshooter(private val context: Context, private val actions: SystemActions) {

    enum class Kind { CHECK_INTERNET, SPOKEN, APP_INFO, PLAY_STORE, CONFIRMED_CLEAR_DATA, OFFER_CALL }

    /** One ladder step. [lineRes] is spoken with the app label as `%1$s`; [manualLineRes] is
     *  the fallback line when the screen this rung opens could not be opened. The `xiaomi*`
     *  variants, when set, replace them on Xiaomi/Redmi/POCO phones — see [isXiaomi]. */
    data class Rung(
        val id: String,
        val kind: Kind,
        val lineRes: Int,
        val manualLineRes: Int = 0,
        val xiaomiLineRes: Int = 0,
        val xiaomiManualLineRes: Int = 0
    ) {
        fun line(xiaomi: Boolean): Int = if (xiaomi && xiaomiLineRes != 0) xiaomiLineRes else lineRes
        fun manualLine(xiaomi: Boolean): Int =
            if (xiaomi && xiaomiManualLineRes != 0) xiaomiManualLineRes else manualLineRes
    }

    private enum class Awaiting { FIX_RESULT, DATA_CONFIRM, CALL_ANSWER }

    private data class Session(
        val pkg: String,
        val label: String,
        val symptom: String,
        val rung: Int,
        val awaiting: Awaiting,
        val updatedAt: Long
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * @param appName the app she named ("Hotstar") — any app; resolved by launcher label.
     * @param symptom one of [SYMPTOMS]; logged only, the ladder is the same for every symptom.
     * @param answer "" for a fresh complaint, else "yes" / "no" / "unclear" — her reply to the
     *        question the previous rung asked.
     */
    fun handle(appName: String, symptom: String, answer: String): SystemActions.Result {
        val ans = answer.trim().lowercase(Locale.US)
        val named = appName.trim()
        val resolved = if (named.isNotBlank()) AppResolver.resolve(context, named) else null
        val saved = load()
        val isAnswer = ans in ANSWERS
        val session = saved?.takeIf {
            named.isBlank() || resolved?.packageName == it.pkg || (resolved == null && isAnswer)
        }
        Log.i(TAG, "troubleshoot: app=\"$named\" -> $resolved symptom=$symptom answer=\"$ans\" saved=$saved")
        return if (session == null) start(named, resolved, symptom) else advance(session, ans)
    }

    // ---- start ---------------------------------------------------------------

    private fun start(named: String, app: AppResolver.App?, symptom: String): SystemActions.Result {
        clear()
        if (named.isBlank()) {
            return SystemActions.Result(
                str(R.string.ts_which_app),
                pending = SystemActions.Pending(
                    "troubleshoot_app",
                    "she says an app is not working but did not say which one; her next words " +
                        "name the app — call troubleshoot_app with that app_name"
                ),
                debugNote = "no app named"
            )
        }
        if (app == null) {
            return SystemActions.Result(
                str(R.string.ts_not_installed, named),
                pending = SystemActions.Pending(
                    "troubleshoot_app",
                    "she said the app \"$named\" is not working, but no installed app has that " +
                        "name; she was asked to say the app's name again — call troubleshoot_app " +
                        "with the name she says"
                ),
                debugNote = "app \"$named\" not found among installed apps"
            )
        }
        val signals = collectSignals(app)
        Log.i(TAG, "troubleshoot: start ${app.label} (${app.packageName}) symptom=$symptom $signals")
        val s = Session(
            app.packageName, app.label, symptom.ifBlank { "other" }, 0,
            Awaiting.FIX_RESULT, System.currentTimeMillis()
        )
        val r = enterRung(s, 0, prefix = "")
        return r.copy(debugNote = "start ${app.label}(${app.packageName}) symptom=${s.symptom} $signals | ${r.debugNote}")
    }

    /** Facts fixed code can check without asking her — logged, never spoken. */
    private fun collectSignals(app: AppResolver.App): String {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(app.packageName, 0)
        } catch (e: Exception) {
            Log.w(TAG, "troubleshoot: getPackageInfo failed", e)
            null
        }
        val lastUpdate = info?.lastUpdateTime?.let {
            val days = (System.currentTimeMillis() - it) / DAY_MS
            "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it))}(${days}d ago)"
        } ?: "?"
        val freeMb = try {
            StatFs(Environment.getDataDirectory().path).availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            -1L
        }
        return "signals[internet=${Connectivity.hasInternet(context)} installed=true " +
            "version=${info?.versionName ?: "?"} lastUpdate=$lastUpdate freeStorage=${freeMb}MB]"
    }

    // ---- her answer to the previous rung -------------------------------------

    private fun advance(s: Session, ans: String): SystemActions.Result = when (s.awaiting) {
        Awaiting.FIX_RESULT -> when (ans) {
            "yes" -> {
                clear()
                SystemActions.Result(str(R.string.ts_fixed, s.label), debugNote = "FIXED at ${tag(s.rung)}")
            }
            // "" = a fresh "still not working" with no pending note — e.g. after the phone
            // restart rung wiped the in-memory follow-up. Same as "no": move one rung on.
            "no", "" -> {
                val next = s.rung + 1
                val prefix = if (next == CALL_RUNG) str(R.string.ts_still_broken) else str(R.string.ts_next)
                val r = enterRung(s, next, prefix)
                r.copy(debugNote = "not fixed at ${tag(s.rung)} -> ${r.debugNote}")
            }
            else -> reask(s, str(R.string.ts_reask_fixed, s.label), "unclear answer at ${tag(s.rung)}, re-asked")
        }
        Awaiting.DATA_CONFIRM -> when (ans) {
            "yes" -> clearData(s)
            "no" -> {
                val r = enterRung(s, CALL_RUNG, str(R.string.ts_clear_data_skipped))
                r.copy(debugNote = "declined clear data (no login/OTP) -> ${r.debugNote}")
            }
            // Never skip the confirmation: anything but a clear yes/no asks again, warning included.
            else -> reask(s, str(R.string.ts_reask_clear_data, s.label), "clear-data confirmation unclear, re-asked")
        }
        Awaiting.CALL_ANSWER -> when (ans) {
            "yes" -> {
                val r = actions.callHelper(BuildConfig.HELPER_CONTACT_NAME, BuildConfig.HELPER_PHONE_NUMBER)
                // A missing call permission parks this intent for a re-run — keep the session so
                // the retry still lands here.
                if (r.followUp == null) clear() else save(s.copy(updatedAt = System.currentTimeMillis()))
                r.copy(debugNote = "NOT FIXED — reached ${tag(s.rung)}; she accepted the call" +
                    (if (r.debugNote.isNotEmpty()) " ${r.debugNote}" else "") +
                    if (r.followUp != null) " (waiting on permission)" else "")
            }
            "no" -> {
                clear()
                SystemActions.Result(
                    str(R.string.ts_call_declined),
                    debugNote = "NOT FIXED — reached ${tag(s.rung)}; she declined the call"
                )
            }
            else -> reask(s, offerCallLine(), "call offer unclear, re-asked")
        }
    }

    /** Names the helper when a name is configured; a number-only setup just offers "a call home". */
    private fun offerCallLine(): String {
        val name = BuildConfig.HELPER_CONTACT_NAME
        return if (name.isBlank()) str(R.string.ts_offer_call_home) else str(R.string.ts_offer_call, name)
    }

    private fun reask(s: Session, line: String, note: String): SystemActions.Result {
        val updated = s.copy(updatedAt = System.currentTimeMillis())
        save(updated)
        return SystemActions.Result(line, pending = pendingFor(updated), debugNote = note)
    }

    // ---- enter a rung ----------------------------------------------------------

    private fun enterRung(s: Session, index: Int, prefix: String): SystemActions.Result {
        val i = index.coerceAtMost(CALL_RUNG)
        val rung = LADDER[i]
        val label = s.label
        var awaiting = Awaiting.FIX_RESULT
        var note = tag(i)
        val line: String = when (rung.kind) {
            Kind.CHECK_INTERNET -> {
                if (Connectivity.hasInternet(context)) {
                    val r = enterRung(s, i + 1, join(prefix, str(R.string.ts_internet_ok)))
                    return r.copy(debugNote = "${tag(i)} ok -> ${r.debugNote}")
                }
                note += " offline"
                str(rung.lineRes, label)
            }
            Kind.SPOKEN -> str(rung.lineRes, label)
            Kind.APP_INFO -> {
                if (isXiaomi) note += " (xiaomi wording)"
                if (openAppInfo(s.pkg)) {
                    str(rung.line(isXiaomi), label)
                } else {
                    note += " (App Info did not open)"
                    str(rung.manualLine(isXiaomi), label)
                }
            }
            Kind.PLAY_STORE -> if (openPlayStore(s.pkg)) {
                str(rung.lineRes, label)
            } else {
                note += " (Play Store did not open)"
                str(rung.manualLineRes, label)
            }
            Kind.CONFIRMED_CLEAR_DATA -> {
                awaiting = Awaiting.DATA_CONFIRM
                note += " asked for login/OTP confirmation"
                str(rung.lineRes, label)
            }
            Kind.OFFER_CALL -> {
                if (BuildConfig.HELPER_CONTACT_NAME.isBlank() && BuildConfig.HELPER_PHONE_NUMBER.isBlank()) {
                    clear()
                    return SystemActions.Result(
                        join(prefix, str(R.string.ts_offer_call_no_helper)),
                        debugNote = "NOT FIXED — reached ${tag(i)}; no HELPER_CONTACT_NAME or HELPER_PHONE_NUMBER configured"
                    )
                }
                awaiting = Awaiting.CALL_ANSWER
                offerCallLine()
            }
        }
        val updated = s.copy(rung = i, awaiting = awaiting, updatedAt = System.currentTimeMillis())
        save(updated)
        return SystemActions.Result(join(prefix, line), pending = pendingFor(updated), debugNote = note)
    }

    /** Only reachable from [Awaiting.DATA_CONFIRM] + an explicit "yes". */
    private fun clearData(s: Session): SystemActions.Result {
        val opened = openAppInfo(s.pkg)
        val lineRes = when {
            isXiaomi && opened -> R.string.ts_clear_data_xiaomi
            isXiaomi -> R.string.ts_clear_data_manual_xiaomi
            opened -> R.string.ts_clear_data
            else -> R.string.ts_clear_data_manual
        }
        val line = str(lineRes, s.label)
        val updated = s.copy(awaiting = Awaiting.FIX_RESULT, updatedAt = System.currentTimeMillis())
        save(updated)
        return SystemActions.Result(
            line,
            pending = pendingFor(updated),
            debugNote = "${tag(s.rung)} confirmed login/OTP, clear data instructed" +
                (if (isXiaomi) " (xiaomi wording)" else "") +
                if (opened) "" else " (App Info did not open)"
        )
    }

    private fun pendingFor(s: Session): SystemActions.Pending {
        val head = "troubleshooting app \"${s.label}\", step ${s.rung + 1} of ${LADDER.size} (${LADDER[s.rung].id}): "
        val tail = " Call troubleshoot_app with app_name=\"${s.label}\" and answer="
        val text = when (s.awaiting) {
            Awaiting.FIX_RESULT -> head + "she was just given one fix and asked whether ${s.label} works now." +
                tail + "\"yes\" if it works now, \"no\" if it still does not work, \"unclear\" if her reply does not say."
            Awaiting.DATA_CONFIRM -> head + "she was warned that clearing ${s.label}'s data logs her out, and asked " +
                "whether she has her login details and OTP ready." + tail + "\"yes\" only if she clearly says she " +
                "has them, \"no\" if she does not, \"unclear\" otherwise."
            Awaiting.CALL_ANSWER -> head + "no fix worked and she was asked whether to call " +
                "${BuildConfig.HELPER_CONTACT_NAME.ifBlank { "someone at home" }} for help." + tail + "\"yes\" if she wants the call, \"no\" if " +
                "not, \"unclear\" otherwise."
        }
        return SystemActions.Pending("troubleshoot_app", text)
    }

    // ---- screens she makes the taps on ----------------------------------------

    private fun openAppInfo(pkg: String): Boolean = launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)),
        "App Info"
    )

    private fun openPlayStore(pkg: String): Boolean =
        launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(PLAY_STORE_PKG),
            "Play Store"
        ) || launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")),
            "Play Store (web link)"
        )

    private fun launch(intent: Intent, what: String): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w(TAG, "troubleshoot: could not open $what", e)
        false
    }

    // ---- persistence (see class doc) ---------------------------------------------

    private fun load(): Session? {
        val raw = prefs.getString(KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val s = Session(
                o.getString("pkg"), o.getString("label"), o.optString("symptom"),
                o.getInt("rung").coerceIn(0, CALL_RUNG), Awaiting.valueOf(o.getString("awaiting")),
                o.getLong("updatedAt")
            )
            if (System.currentTimeMillis() - s.updatedAt > SESSION_TTL_MS) {
                clear()
                null
            } else s
        } catch (e: Exception) {
            Log.w(TAG, "troubleshoot: bad saved session, discarding: ${e.message}")
            clear()
            null
        }
    }

    private fun save(s: Session) {
        prefs.edit().putString(
            KEY,
            JSONObject()
                .put("pkg", s.pkg).put("label", s.label).put("symptom", s.symptom)
                .put("rung", s.rung).put("awaiting", s.awaiting.name).put("updatedAt", s.updatedAt)
                .toString()
        ).apply()
    }

    private fun clear() = prefs.edit().remove(KEY).apply()

    private fun join(prefix: String, line: String): String =
        listOf(prefix.trim(), line.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private fun tag(i: Int): String = "rung=${i + 1}/${LADDER.size} ${LADDER[i].id}"

    private fun str(resId: Int): String = context.getString(resId)
    private fun str(resId: Int, arg: String): String = context.getString(resId, arg)

    companion object {
        private const val TAG = "MotherAssistant"
        private const val PREFS = "troubleshoot"
        private const val KEY = "session"
        private const val SESSION_TTL_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val PLAY_STORE_PKG = "com.android.vending"
        private val ANSWERS = setOf("yes", "no", "unclear")

        /** Must match the `symptom` enum in [com.family.motherassistant.gemini.IntentSchema]. */
        val SYMPTOMS = listOf(
            "not_opening", "black_screen", "crashing", "no_sound", "buffering", "login_problem", "other"
        )

        /** The fix ladder, in order. Cheapest/least disruptive first; clear data last, then a call. */
        val LADDER = listOf(
            Rung("internet_check", Kind.CHECK_INTERNET, R.string.ts_internet_offline),
            Rung("force_close_reopen", Kind.SPOKEN, R.string.ts_force_close),
            Rung("restart_phone", Kind.SPOKEN, R.string.ts_restart_phone),
            Rung(
                "clear_cache", Kind.APP_INFO, R.string.ts_clear_cache, R.string.ts_clear_cache_manual,
                R.string.ts_clear_cache_xiaomi, R.string.ts_clear_cache_manual_xiaomi
            ),
            Rung("update_play_store", Kind.PLAY_STORE, R.string.ts_update, R.string.ts_update_manual),
            Rung("clear_data", Kind.CONFIRMED_CLEAR_DATA, R.string.ts_clear_data_confirm),
            Rung("call_helper", Kind.OFFER_CALL, R.string.ts_offer_call)
        )
        private val CALL_RUNG = LADDER.indexOfFirst { it.kind == Kind.OFFER_CALL }

        /**
         * MIUI/HyperOS App Info has no "Clear cache" under Storage: the bottom-bar "Clear data"
         * button opens a small menu with "Clear all data" *above* "Clear cache" (confirmed on a
         * Xiaomi/HyperOS device). POCO and Redmi phones report "Xiaomi" here; the
         * other two names are matched defensively.
         */
        private val isXiaomi: Boolean =
            Build.MANUFACTURER.lowercase(Locale.US) in setOf("xiaomi", "redmi", "poco")
    }
}
