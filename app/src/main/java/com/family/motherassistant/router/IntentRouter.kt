package com.family.motherassistant.router

import android.util.Log
import com.family.motherassistant.actions.AccessibilityActions
import com.family.motherassistant.actions.SystemActions
import com.family.motherassistant.actions.Troubleshooter
import com.family.motherassistant.gemini.GeminiClient
import com.family.motherassistant.tts.SpeechOutput

/**
 * The typed intent Gemini proposed. Gemini's raw JSON is mapped to exactly one of these
 * before anything acts on it, so only known, code-defined branches ever run — no dynamic
 * execution from model output.
 */
sealed class AssistantIntent {
    abstract val spokenReplyHindi: String

    data class SetRingerMode(val mode: String, override val spokenReplyHindi: String) : AssistantIntent()
    data class SaveContact(val name: String, val phone: String, override val spokenReplyHindi: String) : AssistantIntent()
    data class CallContact(val name: String, override val spokenReplyHindi: String) : AssistantIntent()
    data class SendSms(val name: String, val message: String, override val spokenReplyHindi: String) : AssistantIntent()
    data class SetAlarm(val hour: Int, val minute: Int, override val spokenReplyHindi: String) : AssistantIntent()
    data class OpenApp(val appName: String, override val spokenReplyHindi: String) : AssistantIntent()
    data class ShareOnScreen(
        val targetApp: String,
        val targetContact: String?,
        override val spokenReplyHindi: String
    ) : AssistantIntent()

    /** Feature A (Hotstar only for now) — main-app-trigger only, see [AssistantController]. */
    data class SearchAndOpen(
        val appName: String,
        val searchQuery: String,
        override val spokenReplyHindi: String
    ) : AssistantIntent()

    /** Feature B (Hotstar only for now) — main-app-trigger only, see [AssistantController]. */
    data class SwitchProfile(val appName: String, override val spokenReplyHindi: String) : AssistantIntent()

    /** Opens a live Google search — for informational/current-events questions, not actions. */
    data class SearchWeb(val query: String, override val spokenReplyHindi: String) : AssistantIntent()

    /** Opens a YouTube search — for a video/song/channel she names. */
    data class SearchYoutube(val query: String, override val spokenReplyHindi: String) : AssistantIntent()

    /** Fix ladder for a misbehaving app — see [Troubleshooter]. [answer] is "" for a fresh complaint. */
    data class TroubleshootApp(
        val appName: String,
        val symptom: String,
        val answer: String,
        override val spokenReplyHindi: String
    ) : AssistantIntent()

    data class CasualChat(override val spokenReplyHindi: String) : AssistantIntent()

    /**
     * [pendingIntent] / [pendingContext] are set when Gemini was only missing one detail of an
     * otherwise clear request (which contact, what message text, morning/evening). The Activity
     * turns them into next-turn follow-up context so her answer completes the original request.
     */
    data class Clarify(
        override val spokenReplyHindi: String,
        val pendingIntent: String = "",
        val pendingContext: String = ""
    ) : AssistantIntent()

    data class Unsupported(val functionName: String, override val spokenReplyHindi: String) : AssistantIntent()

    companion object {
        fun from(result: GeminiClient.Result): AssistantIntent {
            val a = result.args
            val reply = result.spokenReplyHindi
            return when (result.functionName) {
                "set_ringer_mode" -> SetRingerMode(a.optString("mode", "normal"), reply)
                "save_contact" -> SaveContact(a.optString("name"), a.optString("phone_number"), reply)
                "call_contact" -> CallContact(a.optString("name"), reply)
                "send_sms" -> SendSms(a.optString("name"), a.optString("message"), reply)
                "set_alarm" -> SetAlarm(a.optInt("hour"), a.optInt("minute"), reply)
                "open_app" -> OpenApp(a.optString("app_name"), reply)
                "share_onscreen", "share_onscreen_content" -> ShareOnScreen(
                    a.optString("target_app").ifBlank { "WhatsApp" },
                    a.optString("target").ifBlank { a.optString("target_contact") }.ifBlank { null },
                    reply
                )
                "search_and_open" -> SearchAndOpen(a.optString("app_name"), a.optString("search_query"), reply)
                "switch_profile" -> SwitchProfile(a.optString("app_name"), reply)
                "search_web" -> SearchWeb(a.optString("query"), reply)
                "search_youtube" -> SearchYoutube(a.optString("query"), reply)
                "troubleshoot_app" -> TroubleshootApp(
                    a.optString("app_name"), a.optString("symptom"), a.optString("answer"), reply
                )
                "casual_chat" -> CasualChat(reply)
                "clarify" -> Clarify(reply, result.pendingIntent, result.pendingContext)
                else -> Unsupported(result.functionName, reply)
            }
        }
    }
}

/**
 * Deterministic dispatch layer — Gemini decides *what*, this class decides *how*.
 *
 * Phase 2 wires the Tier 1 intents (ringer, save/call contact, SMS, alarm) into
 * [SystemActions]; Phase 3 wires share_onscreen into [AccessibilityActions]. Each of
 * those speaks a confirmation that reflects what *actually* happened — not Gemini's
 * pre-composed `spoken_reply_hindi`, which is discarded for those outcomes so a missing
 * permission or an unknown name can never be announced as success. Intents with no
 * action yet (open_app, casual_chat, clarify) still just speak Gemini's reply.
 *
 * [handle] returns an [Outcome]: a [SystemActions.FollowUp] when the Activity must request
 * a permission or open the DND-access screen before the command can succeed (the caller acts
 * on it and the user simply repeats the command), and/or a [SystemActions.Pending] when this
 * turn ended by asking her a follow-up question that her next utterance should answer.
 */
class IntentRouter(
    private val tts: SpeechOutput,
    private val actions: SystemActions,
    private val a11y: AccessibilityActions,
    private val troubleshooter: Troubleshooter
) {

    data class Outcome(
        val followUp: SystemActions.FollowUp? = null,
        val pending: SystemActions.Pending? = null,
        /** See [SystemActions.Result.asyncShareStarted]. */
        val asyncShareStarted: Boolean = false,
        /** The spoken line actually used this turn — the real outcome, not Gemini's pre-composed
         *  guess (see class doc). Consumed only by [AssistantController]'s private debug log. */
        val spokenOutcome: String = "",
        /** See [SystemActions.Result.debugNote] — debug log only. */
        val debugNote: String = ""
    )

    /**
     * @param shareSourceOverride for [AssistantIntent.ShareOnScreen] only — the source app package
     *        locked in by [com.family.motherassistant.core.AssistantController] when the share was
     *        first recognized, so a clarify round-trip can't let it drift. Ignored otherwise.
     * @param onSpoken optional callback invoked once this turn's spoken reply has finished playing
     *        (or was dropped) — lets a foreground-service host know it is safe to wind down.
     */
    fun handle(
        intent: AssistantIntent,
        shareSourceOverride: String? = null,
        onSpoken: (() -> Unit)? = null
    ): Outcome {
        val result = when (intent) {
            is AssistantIntent.SetRingerMode -> {
                log("set_ringer_mode mode=${intent.mode}")
                actions.setRingerMode(intent.mode)
            }
            is AssistantIntent.SaveContact -> {
                log("save_contact name=${intent.name}")
                actions.saveContact(intent.name, intent.phone)
            }
            is AssistantIntent.CallContact -> {
                log("call_contact name=${intent.name}")
                actions.callContact(intent.name)
            }
            is AssistantIntent.SendSms -> {
                log("send_sms name=${intent.name}")
                actions.sendSms(intent.name, intent.message)
            }
            is AssistantIntent.SetAlarm -> {
                log("set_alarm ${intent.hour}:${intent.minute}")
                actions.setAlarm(intent.hour, intent.minute)
            }
            is AssistantIntent.OpenApp -> {
                log("open_app app=${intent.appName}")                     // TODO Phase 3: SystemActions.openApp
                SystemActions.Result(intent.spokenReplyHindi)
            }
            is AssistantIntent.ShareOnScreen -> {
                log("share_onscreen target_app=${intent.targetApp} target=${intent.targetContact} source=$shareSourceOverride")
                a11y.shareOnScreen(intent.targetApp, intent.targetContact, shareSourceOverride)
            }
            is AssistantIntent.SearchAndOpen -> {
                log("search_and_open app=${intent.appName} query=${intent.searchQuery}")
                a11y.searchAndOpen(intent.appName, intent.searchQuery)
            }
            is AssistantIntent.SwitchProfile -> {
                log("switch_profile app=${intent.appName}")
                a11y.switchProfile(intent.appName)
            }
            is AssistantIntent.SearchWeb -> {
                log("search_web query=${intent.query}")
                actions.searchWeb(intent.query)
            }
            is AssistantIntent.SearchYoutube -> {
                log("search_youtube query=${intent.query}")
                actions.searchYoutube(intent.query)
            }
            is AssistantIntent.TroubleshootApp -> {
                log("troubleshoot_app app=${intent.appName} symptom=${intent.symptom} answer=${intent.answer}")
                troubleshooter.handle(intent.appName, intent.symptom, intent.answer)
            }
            is AssistantIntent.CasualChat -> {
                log("casual_chat")                                        // no action — just speaks the reply
                SystemActions.Result(intent.spokenReplyHindi)
            }
            is AssistantIntent.Clarify -> {
                log("clarify")                                            // TODO Phase 4: FallbackAction
                SystemActions.Result(
                    intent.spokenReplyHindi,
                    pending = if (intent.pendingContext.isNotBlank()) {
                        SystemActions.Pending(intent.pendingIntent, intent.pendingContext)
                    } else null
                )
            }
            is AssistantIntent.Unsupported -> {
                log("unsupported fn=${intent.functionName}")
                SystemActions.Result(intent.spokenReplyHindi)
            }
        }
        if (result.asyncShareStarted) {
            // A share was just handed off to the AccessibilityService's state machine — its
            // outcome (success or a specific failure) isn't known yet, so nothing is spoken
            // here. Speaking an "in progress" line now risked colliding with a near-instant
            // failure (QUEUE_FLUSH racing two speak() calls fired milliseconds apart — the
            // minimised-player false positive) and, worse, meant a genuine
            // DONE/FAILED confirmation was never the *only* thing she could have heard for this
            // command. AssistantController's shareResultListener speaks the real, final outcome
            // once the flow actually finishes — that is now the sole spoken line for this turn.
            // The "please wait" text still reaches her via status (see AssistantController.dispatch).
            onSpoken?.invoke()
        } else {
            tts.speak(result.spokenReplyHindi, onSpoken)
        }
        return Outcome(
            result.followUp, result.pending, result.asyncShareStarted, result.spokenReplyHindi, result.debugNote
        )
    }

    private fun log(what: String) = Log.i(TAG, "intent -> $what")

    companion object {
        private const val TAG = "IntentRouter"
    }
}
