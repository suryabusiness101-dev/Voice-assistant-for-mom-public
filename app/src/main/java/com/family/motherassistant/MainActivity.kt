package com.family.motherassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.family.motherassistant.actions.SystemActions
import com.family.motherassistant.core.AssistantController
import com.family.motherassistant.debug.DebugLogActivity
import com.family.motherassistant.router.AssistantIntent
import com.family.motherassistant.service.AssistantAccessibilityService
import com.family.motherassistant.service.AssistantService

/**
 * The in-app tap-to-talk screen. It is now just one of three ways to start the same voice
 * turn — the floating bubble and the system Accessibility Button are the other two, both
 * routed through [AssistantService]. The actual pipeline (record → Gemini → route → speak)
 * lives in the process-wide [AssistantController]; this Activity only renders status, handles
 * the "that's wrong" correction UI, and does the things that need a real Activity: requesting
 * runtime permissions and resuming a parked intent after a settings-screen round-trip.
 */
class MainActivity : Activity() {

    private lateinit var appTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var talkButton: Button
    private lateinit var wrongButton: Button
    private lateinit var bubbleButton: Button

    private lateinit var controller: AssistantController

    /** Hidden entry point to the private debug log — a 5-second long-press on [appTitle],
     *  no visible hint. See DebugLogActivity. */
    private val debugLogHandler = Handler(Looper.getMainLooper())
    private val debugLogLongPress = Runnable { startActivity(Intent(this, DebugLogActivity::class.java)) }

    /** True while she is away in the system TTS voice-data install flow; re-init TTS on return. */
    private var awaitingVoiceInstall = false

    private val activityUi = object : AssistantController.Ui {
        override fun status(text: String) = setStatus(text)

        override fun turnStarted() = runOnUiThread {
            talkButton.isEnabled = false
            talkButton.text = getString(R.string.btn_listening)
            wrongButton.visibility = View.GONE
        }

        override fun turnEnded(finalStatus: String) = runOnUiThread {
            talkButton.isEnabled = true
            talkButton.text = getString(R.string.btn_tap_to_speak)
        }

        override fun heard(text: String?) = runOnUiThread {
            wrongButton.visibility = View.VISIBLE
        }

        override fun requestRuntimePermission(permission: String) = runOnUiThread {
            val code = if (permission == Manifest.permission.RECORD_AUDIO) REQ_MIC else REQ_ACTION_PERM
            requestPermissions(arrayOf(permission), code)
        }

        override fun openSpecialSettings(followUp: SystemActions.FollowUp) = runOnUiThread {
            when (followUp) {
                SystemActions.FollowUp.OpenDndAccessSettings -> openDndAccessSettings()
                SystemActions.FollowUp.OpenAccessibilitySettings -> openAccessibilitySettings()
                else -> Unit
            }
        }

        override fun ttsVoiceDataMissing() = promptInstallVoiceData()
        override fun ttsEngineUnavailable() = setStatus(getString(R.string.tts_unavailable))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appTitle = findViewById(R.id.appTitle)
        statusText = findViewById(R.id.statusText)
        talkButton = findViewById(R.id.talkButton)
        wrongButton = findViewById(R.id.wrongButton)
        bubbleButton = findViewById(R.id.bubbleButton)

        controller = AssistantController.get(this)

        talkButton.setOnClickListener { controller.startTurn(fromMainApp = true) }
        wrongButton.setOnClickListener { showCorrectionDialog() }
        bubbleButton.setOnClickListener { toggleBubble() }
        appTitle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> debugLogHandler.postDelayed(debugLogLongPress, 5000)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    debugLogHandler.removeCallbacks(debugLogLongPress)
            }
            false
        }

        requestSetupPermissions()
    }

    override fun onResume() {
        super.onResume()
        controller.bindActivityUi(activityUi)

        if (awaitingVoiceInstall) {
            awaitingVoiceInstall = false
            controller.retryTts()
        }

        // Back from a settings screen we sent her to — re-run the parked command automatically.
        when (val parked = controller.peekPendingIntent()) {
            is AssistantIntent.SetRingerMode ->
                if (isNotificationPolicyGranted()) {
                    controller.clearPendingIntent()
                    controller.retryIntent(parked)
                }
            is AssistantIntent.ShareOnScreen, is AssistantIntent.SearchAndOpen, is AssistantIntent.SwitchProfile ->
                if (AssistantAccessibilityService.instance != null) {
                    controller.clearPendingIntent()
                    controller.retryIntent(parked)
                }
            else -> Unit
        }

        refreshBubbleState()
    }

    override fun onPause() {
        super.onPause()
        controller.unbindActivityUi(activityUi)
    }

    // ---- setup ----------------------------------------------------------------

    /**
     * Ask once, up front, for everything the assistant needs: the Tier 1 dangerous permissions,
     * RECORD_AUDIO, notification posting (so the bubble's foreground-service notice shows), and
     * — as a special-access screen, not a dialog — "draw over other apps" for the bubble.
     */
    private fun requestSetupPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_SETUP)
        } else {
            controller.syncContactsIntoGlossary()
            maybeRequestOverlayPermission()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MIC -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                controller.startTurn()
            } else {
                setStatus(getString(R.string.status_no_mic))
            }

            REQ_SETUP -> {
                controller.syncContactsIntoGlossary()
                maybeRequestOverlayPermission()
            }

            REQ_ACTION_PERM -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                val parked = controller.takePendingIntent()
                if (granted && parked != null) controller.retryIntent(parked)
            }
        }
    }

    /** One-time nudge into the "draw over other apps" screen so the bubble can work later. */
    private fun maybeRequestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences(AssistantService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_OVERLAY, false)) return
        prefs.edit().putBoolean(KEY_ASKED_OVERLAY, true).apply()
        Toast.makeText(this, R.string.setup_overlay_hint, Toast.LENGTH_LONG).show()
        openOverlaySettings()
    }

    // ---- floating bubble toggle --------------------------------------------

    private fun toggleBubble() {
        if (AssistantService.isBubbleEnabled(this)) {
            setBubbleEnabled(false)
            AssistantService.hideBubble(this)
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.bubble_needs_permission, Toast.LENGTH_LONG).show()
                openOverlaySettings()
                return
            }
            setBubbleEnabled(true)
            AssistantService.showBubble(this)
            Toast.makeText(this, R.string.bubble_enabled_toast, Toast.LENGTH_LONG).show()
        }
        updateBubbleButton()
    }

    /** Keep the button label honest, and re-assert the bubble if the service was killed. */
    private fun refreshBubbleState() {
        if (AssistantService.isBubbleEnabled(this) && !Settings.canDrawOverlays(this)) {
            setBubbleEnabled(false)   // permission was revoked out from under us
        } else if (AssistantService.isBubbleEnabled(this)) {
            AssistantService.showBubble(this)
        }
        updateBubbleButton()
    }

    private fun updateBubbleButton() {
        bubbleButton.text = getString(
            if (AssistantService.isBubbleEnabled(this)) R.string.btn_bubble_off
            else R.string.btn_bubble_on
        )
    }

    private fun setBubbleEnabled(on: Boolean) {
        getSharedPreferences(AssistantService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(AssistantService.KEY_BUBBLE_ENABLED, on).apply()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "cannot open overlay settings", e)
        }
    }

    // ---- settings-screen helpers (shared with the controller Ui) ----------

    private fun isNotificationPolicyGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun openDndAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "cannot open DND access settings", e)
            controller.clearPendingIntent()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "cannot open accessibility settings", e)
            controller.clearPendingIntent()
        }
    }

    /**
     * The hi-IN voice files aren't on the phone. Show why on screen (she can't hear anything
     * yet), then jump into the system's one-time voice-data install flow. [onResume] re-inits
     * TTS on return.
     */
    private fun promptInstallVoiceData() {
        setStatus(getString(R.string.tts_needs_voice))
        if (awaitingVoiceInstall) return
        awaitingVoiceInstall = true
        try {
            startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .setPackage("com.google.android.tts")
            )
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_INSTALL_TTS_DATA not resolvable", e)
            awaitingVoiceInstall = false
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e2: Exception) {
                Log.e(TAG, "TTS settings not resolvable either", e2)
                setStatus(getString(R.string.tts_unavailable))
            }
        }
    }

    /**
     * "That's wrong": type what she actually meant and store the {heard -> meant} pair so
     * future turns get it as a few-shot correction. Typed dialog — for whoever helps her set up.
     */
    private fun showCorrectionDialog() {
        val heard = controller.lastHeard.orEmpty()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.correction_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.correction_title)
            .setMessage(
                if (heard.isBlank()) getString(R.string.correction_no_heard)
                else getString(R.string.correction_heard_prefix, heard)
            )
            .setView(input)
            .setPositiveButton(R.string.correction_save) { _, _ ->
                val meant = input.text?.toString()?.trim().orEmpty()
                if (meant.isNotEmpty()) {
                    controller.addCorrection(heard, meant)
                    Toast.makeText(this, R.string.correction_saved, Toast.LENGTH_SHORT).show()
                }
                wrongButton.visibility = View.GONE
            }
            .setNegativeButton(R.string.correction_cancel, null)
            .show()
    }

    private fun setStatus(text: String) = runOnUiThread { statusText.text = text }

    companion object {
        private const val TAG = "MotherAssistant"
        private const val REQ_MIC = 101
        private const val REQ_SETUP = 102
        private const val REQ_ACTION_PERM = 103

        private const val KEY_ASKED_OVERLAY = "asked_overlay"
    }
}
