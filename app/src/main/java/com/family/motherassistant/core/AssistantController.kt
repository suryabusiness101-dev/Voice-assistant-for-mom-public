package com.family.motherassistant.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.family.motherassistant.BuildConfig
import com.family.motherassistant.R
import com.family.motherassistant.actions.AccessibilityActions
import com.family.motherassistant.actions.SystemActions
import com.family.motherassistant.actions.Troubleshooter
import com.family.motherassistant.capture.VoiceCapture
import com.family.motherassistant.debug.DebugLog
import com.family.motherassistant.gemini.GeminiClient
import com.family.motherassistant.gemini.PendingClarification
import com.family.motherassistant.gemini.QuotaExceededException
import com.family.motherassistant.net.Connectivity
import com.family.motherassistant.personalization.CorrectionLog
import com.family.motherassistant.personalization.GlossaryStore
import com.family.motherassistant.personalization.Personalization
import com.family.motherassistant.router.AssistantIntent
import com.family.motherassistant.router.IntentRouter
import com.family.motherassistant.service.AssistantAccessibilityService
import com.family.motherassistant.tts.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The voice pipeline — record → Gemini → intent routing → spoken reply — lifted out of
 * MainActivity so it can run from any host: the Activity's big button, the floating bubble,
 * or the system Accessibility Button, none of which need MainActivity in the foreground.
 *
 * One process-wide instance ([get]). It owns the single [SpeechOutput] engine, the recorder,
 * the router, the glossary/correction stores, and the session-only follow-up state
 * ([pendingIntent], [pendingClarification], the share source lock) — so a multi-turn
 * clarify or a parked permission survives the Activity being destroyed mid-flow, while still
 * never touching disk (gone on process death, consistent with the no-memorization design).
 *
 * The host registers a [Ui] to receive status/lifecycle callbacks and to handle the two
 * things only a real Activity can do: request a runtime permission, and (cleanly) resume a
 * parked intent after the user returns from a settings screen. The Service's [Ui] escalates
 * those to MainActivity instead.
 */
class AssistantController private constructor(private val app: Context) {

    /** Host callbacks. All delivered on the main thread. Every method has a no-op default. */
    interface Ui {
        fun status(text: String) {}
        fun turnStarted() {}
        fun turnEnded(finalStatus: String) {}
        /** The reply for this turn has finished playing (or was dropped) — safe to wind down. */
        fun turnSpoken() {}
        /** Gemini's transcription of the turn just finished — for the "that's wrong" correction UI. */
        fun heard(text: String?) {}
        /** A runtime permission must be requested (RECORD_AUDIO, or a Tier-1 dangerous perm). */
        fun requestRuntimePermission(permission: String) {}
        /** A special-access settings screen must be opened (DND access / Accessibility). */
        fun openSpecialSettings(followUp: SystemActions.FollowUp) {}
        fun ttsVoiceDataMissing() {}
        fun ttsEngineUnavailable() {}
    }

    private object NoOpUi : Ui

    // An Activity Ui takes precedence over a Service Ui when both are bound (status text goes
    // to the visible screen); TTS is independent of whichever is active.
    @Volatile private var activityUi: Ui? = null
    @Volatile private var serviceUi: Ui? = null
    private val ui: Ui get() = activityUi ?: serviceUi ?: NoOpUi

    fun bindActivityUi(u: Ui) { activityUi = u }
    fun unbindActivityUi(u: Ui) { if (activityUi === u) activityUi = null }
    fun bindServiceUi(u: Ui) { serviceUi = u }
    fun unbindServiceUi(u: Ui) { if (serviceUi === u) serviceUi = null }

    val speech = SpeechOutput(app, object : SpeechOutput.Listener {
        override fun onVoiceDataMissing() { ui.ttsVoiceDataMissing() }
        override fun onEngineUnavailable() { ui.ttsEngineUnavailable() }
    })

    private val voiceCapture = VoiceCapture(app)
    private val glossary = GlossaryStore(app)
    private val corrections = CorrectionLog(app)
    private val debugLog = DebugLog(app)
    private val systemActions = SystemActions(app)
    private val router = IntentRouter(
        speech, systemActions, AccessibilityActions(app), Troubleshooter(app, systemActions)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var busy = false
        private set

    /** True from the moment a share is handed to the AccessibilityService until it reports back. */
    @Volatile
    var shareRunning = false
        private set

    /** Gemini's transcription of the most recent turn — what a "that's wrong" tap corrects. */
    var lastHeard: String? = null
        private set

    private var pendingIntent: AssistantIntent? = null
    private var pendingClarification: String? = null

    /**
     * True only for a turn started from [MainActivity]'s own button. Features A/B
     * (`search_and_open`, `switch_profile`) are main-app-trigger only — she must have opened
     * Maa Assistant directly, not the floating bubble or the Accessibility Button — set once at
     * [startTurn] and checked in [onAudioReady] once Gemini's intent is known.
     */
    @Volatile
    private var turnFromMainApp = false

    /** Source app frozen when a share is first recognized — see [lockShareSourceIfNeeded]. */
    private var shareSourcePackage: String? = null
    /** Foreground app snapshotted by the trigger (bubble / a11y button) before recording. */
    private var prelockedSource: String? = null

    init {
        // Presence only — never the values (the number in particular must not reach logcat).
        Log.i(
            TAG,
            "helper config: HELPER_CONTACT_NAME " +
                (if (BuildConfig.HELPER_CONTACT_NAME.isNotBlank()) "set" else "EMPTY") +
                ", HELPER_PHONE_NUMBER " + (if (BuildConfig.HELPER_PHONE_NUMBER.isNotBlank()) "set" else "EMPTY")
        )
        // The Phase 3 share finishes asynchronously inside the AccessibilityService; it reports
        // the outcome here. Owned by this process-wide controller (not the Activity) so the
        // spoken result survives MainActivity being gone.
        AssistantAccessibilityService.shareResultListener = { msg ->
            scope.launch {
                shareSourcePackage = null
                prelockedSource = null
                ui.status(msg)
                // Keep shareRunning true until the outcome line has actually been spoken, so a
                // service host stays foreground for the whole async share, not just the
                // "starting…" line it flushed.
                speech.speak(msg) {
                    shareRunning = false
                    ui.turnSpoken()
                }
            }
        }
    }

    // ---- setup helpers ------------------------------------------------------

    fun syncContactsIntoGlossary() {
        if (!granted(Manifest.permission.READ_CONTACTS)) return
        scope.launch(Dispatchers.IO) {
            val n = glossary.syncFromContacts()
            Log.i(TAG, "glossary: synced $n contact names")
        }
    }

    // ---- turn flow --------------------------------------------------------

    /**
     * Begin one voice turn.
     *
     * @param sourcePackageHint the app that was in front when the trigger fired. Snapshotted by
     *        the caller *before* recording so a "share this reel to …" command spoken from the
     *        bubble locks onto that app even though she never switched to Maa Assistant. Null
     *        from MainActivity's own button (she is already in the app — nothing to share).
     * @param fromMainApp true only when [MainActivity]'s own button started this turn — see
     *        [turnFromMainApp].
     */
    fun startTurn(sourcePackageHint: String? = null, fromMainApp: Boolean = false) {
        if (busy) return
        turnFromMainApp = fromMainApp
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            ui.status(app.getString(R.string.status_no_key))
            return
        }
        if (!Connectivity.hasInternet(app)) {
            val m = app.getString(R.string.err_no_internet)
            ui.status(m)
            if (speech.isReady()) speech.speak(m)
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            ui.requestRuntimePermission(Manifest.permission.RECORD_AUDIO)
            return
        }
        prelockedSource = sourcePackageHint
            ?.takeIf { it.isNotBlank() && it !in WHATSAPP_PKGS && it != app.packageName }
        Log.i(TAG, "turn start — source hint=$sourcePackageHint prelocked=$prelockedSource")

        busy = true
        ui.turnStarted()
        ui.status(app.getString(R.string.status_listening))
        voiceCapture.start(object : VoiceCapture.Listener {
            override fun onFinished(audioFile: File) = onAudioReady(audioFile)
            override fun onError(message: String) {
                speech.speak(message)
                endTurn(app.getString(R.string.status_error))
            }
        })
    }

    private fun onAudioReady(audioFile: File) {
        if (!Connectivity.hasInternet(app)) {
            audioFile.delete()
            endTurn(app.getString(R.string.status_error))
            return
        }
        ui.status(app.getString(R.string.status_thinking))
        lastHeard = null
        scope.launch {
            try {
                val client = GeminiClient(BuildConfig.GEMINI_API_KEY)
                val followUpBlock = pendingClarification.orEmpty()
                val result = withContext(Dispatchers.IO) {
                    val pBlock = Personalization.promptBlock(glossary, corrections)
                    client.classify(
                        audioFile,
                        personalizationBlock = pBlock,
                        pendingClarificationBlock = followUpBlock
                    )
                }
                lastHeard = result.heardText
                var intent = AssistantIntent.from(result)
                if (requiresMainAppTrigger(intent) && !turnFromMainApp) {
                    Log.i(TAG, "blocked ${result.functionName} — not triggered from the main app screen")
                    intent = AssistantIntent.Clarify(app.getString(R.string.act_needs_main_app))
                }
                Log.i(
                    TAG,
                    "round-trip ok: fn=${result.functionName} heard=\"${result.heardText}\" intent=$intent"
                )
                lockShareSourceIfNeeded(intent)

                ui.status(app.getString(R.string.status_speaking))
                dispatch(intent)
                ui.heard(lastHeard)
                endTurn(app.getString(R.string.status_idle))
            } catch (e: QuotaExceededException) {
                Log.e(TAG, "round-trip failed: quota/rate limit — ${e.message}", e)
                val msg = "अभी बहुत सारे सवाल हो गए हैं। थोड़ी देर बाद फिर कोशिश कीजिए।"
                debugLog.add(lastHeard.orEmpty(), "quota_exceeded", msg)
                speech.speak(msg) { ui.turnSpoken() }
                endTurn(app.getString(R.string.status_error))
            } catch (e: Exception) {
                Log.e(TAG, "round-trip failed: ${e.message}", e)
                val msg = "माफ़ कीजिए, अभी जवाब नहीं दे पाई। थोड़ी देर बाद कोशिश कीजिए।"
                debugLog.add(lastHeard.orEmpty(), "error", msg)
                speech.speak(msg) { ui.turnSpoken() }
                endTurn(app.getString(R.string.status_error))
            } finally {
                audioFile.delete()
            }
        }
    }

    /**
     * Run an intent through the router and act on its [IntentRouter.Outcome]: a permission /
     * settings follow-up parks the intent for a re-run; a pending question is remembered for
     * exactly the next turn. A share that was launched asynchronously is tracked in
     * [shareRunning] so the host FGS stays up until the AccessibilityService reports back.
     */
    private fun dispatch(intent: AssistantIntent) {
        val outcome = router.handle(intent, shareSourcePackage) { ui.turnSpoken() }
        val intentLabel = intent::class.simpleName ?: "unknown"
        // troubleshoot_app records its rung + whether it fixed things here (debugNote) — see Troubleshooter.
        debugLog.add(
            lastHeard.orEmpty(),
            if (outcome.debugNote.isBlank()) intentLabel else "$intentLabel [${outcome.debugNote}]",
            outcome.spokenOutcome
        )
        when (val followUp = outcome.followUp) {
            is SystemActions.FollowUp.RequestPermission -> {
                pendingIntent = intent
                ui.requestRuntimePermission(followUp.permission)
            }
            SystemActions.FollowUp.OpenDndAccessSettings -> {
                pendingIntent = intent
                ui.openSpecialSettings(followUp)
            }
            SystemActions.FollowUp.OpenAccessibilitySettings -> {
                pendingIntent = intent
                ui.openSpecialSettings(followUp)
            }
            null -> Unit
        }
        pendingClarification = outcome.pending?.let {
            PendingClarification.block(it.originalIntent, it.context)
        }

        if (intent is AssistantIntent.ShareOnScreen && outcome.asyncShareStarted) {
            // The share was actually handed off to the AccessibilityService this turn — keep the
            // host alive until its async outcome comes back through shareResultListener. The
            // router deliberately did not speak outcome.spokenOutcome (the "please wait" line) —
            // see IntentRouter.handle — the real, final outcome (DONE or a specific FAILED
            // message) is now the only thing actually spoken for this command.
            shareRunning = true
        } else if (intent is AssistantIntent.ShareOnScreen) {
            // Rejected outright / turned into a question / failed before a flow ever started
            // (no foreground app seen, WhatsApp itself as source, no launch intent) — no async
            // work is coming, so there is nothing to wait for. Previously this branch only
            // triggered when there was also a followUp or pending, so those terminal failures
            // fell into the branch above and left shareRunning stuck true forever (see
            // asyncShareStarted's doc comment).
            shareSourcePackage = null
            prelockedSource = null
        }
    }

    /** Feature A/B are main-app-trigger only — see [turnFromMainApp]. */
    private fun requiresMainAppTrigger(intent: AssistantIntent): Boolean =
        intent is AssistantIntent.SearchAndOpen || intent is AssistantIntent.SwitchProfile

    /**
     * Freeze the source app for a share the first time we see one this turn-chain — a direct
     * [AssistantIntent.ShareOnScreen], or the [AssistantIntent.Clarify] Gemini raises while
     * asking who to send to. Prefers the trigger-time [prelockedSource] snapshot (bubble /
     * a11y button), falling back to the AccessibilityService's live last-foreground app (the
     * Activity-button path, where there is no snapshot). Never locks WhatsApp itself.
     */
    private fun lockShareSourceIfNeeded(intent: AssistantIntent) {
        val isShareChain = intent is AssistantIntent.ShareOnScreen ||
            (intent is AssistantIntent.Clarify && intent.pendingIntent == "share_onscreen")
        if (!isShareChain) {
            shareSourcePackage = null
            prelockedSource = null
            return
        }
        if (shareSourcePackage == null) {
            val cand = prelockedSource ?: AssistantAccessibilityService.instance?.lastForegroundApp
            if (cand != null && cand !in WHATSAPP_PKGS && cand != app.packageName) {
                shareSourcePackage = cand
                Log.i(TAG, "share: locked source app = $cand (from ${if (prelockedSource != null) "trigger snapshot" else "live foreground"})")
            } else {
                Log.i(TAG, "share: not locking source (candidate=$cand)")
            }
        }
    }

    /** Re-run a parked intent after its permission was granted / its setting was enabled. */
    fun retryIntent(intent: AssistantIntent) {
        ui.status(app.getString(R.string.status_speaking))
        dispatch(intent)
        ui.status(app.getString(R.string.status_idle))
    }

    fun peekPendingIntent(): AssistantIntent? = pendingIntent
    fun clearPendingIntent() { pendingIntent = null }
    fun takePendingIntent(): AssistantIntent? = pendingIntent.also { pendingIntent = null }

    /**
     * Called from both host types when the AccessibilityService connects — re-runs a share that
     * was parked waiting for the service to be enabled. On the Activity path onResume also
     * covers this; on the bubble/button path this is the only resume hook.
     */
    fun onAccessibilityConnected() {
        val parked = pendingIntent
        val isA11yIntent = parked is AssistantIntent.ShareOnScreen ||
            parked is AssistantIntent.SearchAndOpen || parked is AssistantIntent.SwitchProfile
        if (isA11yIntent && AssistantAccessibilityService.instance != null) {
            pendingIntent = null
            scope.launch { retryIntent(parked!!) }
        }
    }

    fun retryTts() = speech.retryInit()

    fun addCorrection(heard: String, meant: String) {
        scope.launch(Dispatchers.IO) { corrections.add(heard, meant) }
    }

    private fun endTurn(finalStatus: String) {
        busy = false
        ui.turnEnded(finalStatus)
        ui.status(finalStatus)
    }

    private fun granted(p: String): Boolean =
        app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "MotherAssistant"
        private val WHATSAPP_PKGS = listOf("com.whatsapp", "com.whatsapp.w4b")

        @Volatile
        private var INSTANCE: AssistantController? = null

        fun get(context: Context): AssistantController =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AssistantController(context.applicationContext).also { INSTANCE = it }
            }
    }
}
