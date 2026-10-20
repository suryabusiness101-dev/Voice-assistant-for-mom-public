package com.family.motherassistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import com.family.motherassistant.MainActivity
import com.family.motherassistant.R
import com.family.motherassistant.actions.SystemActions
import com.family.motherassistant.core.AssistantController
import kotlin.math.hypot

/**
 * Foreground service that lets her start a voice command from anywhere, without switching to
 * Maa Assistant:
 *  - a draggable floating bubble (needs the one-time "draw over other apps" permission), and
 *  - the system Accessibility Button, routed here by [AssistantAccessibilityService].
 *
 * It hosts the shared [AssistantController] pipeline so a turn runs with no Activity in the
 * foreground. When a turn is triggered while another app (e.g. Instagram) is in front, that
 * app is snapshotted immediately as the share source — the same source-locking principle as
 * the existing share flow, just invoked from here.
 *
 * Permission / OEM friction:
 *  - SYSTEM_ALERT_WINDOW is a one-time special-access grant; some OEMs bury or re-lock it.
 *  - Starting the source app from this (background) context relies on the background-activity-
 *    launch exemption that SYSTEM_ALERT_WINDOW itself grants — so share-from-bubble needs that
 *    permission for a second reason beyond drawing the bubble.
 *  - Android 14 enforces foregroundServiceType: the `microphone` type is only applied while
 *    RECORD_AUDIO is granted; otherwise the service runs as `specialUse`.
 *  - Aggressive OEM task-killers (HyperOS especially) may kill this service; START_STICKY plus
 *    the persisted `bubble_enabled` flag re-raise the bubble on restart, and [BootReceiver]
 *    re-raises it after a full device reboot too.
 */
class AssistantService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: AssistantController
    private lateinit var prefs: SharedPreferences
    private var windowManager: WindowManager? = null

    private var bubble: View? = null
    /** True while the persistent bubble should stay up (independent of any in-flight turn). */
    private var bubbleMode = false
    private var micTypeApplied = false

    private val serviceUi = object : AssistantController.Ui {
        override fun status(text: String) {
            handler.post { updateNotification(text) }
        }

        override fun turnStarted() {
            handler.post { setBubbleActive(true) }
        }

        override fun turnEnded(finalStatus: String) {
            handler.post {
                updateNotification(idleText())
                setBubbleActive(false)
                scheduleStopIfIdle()
            }
        }

        override fun turnSpoken() {
            handler.post { stopIfIdle() }
        }

        override fun requestRuntimePermission(permission: String) {
            controller.speech.speak("एक बार Maa Assistant खोलकर permission दे दीजिए।")
            openMainActivity()
        }

        override fun openSpecialSettings(followUp: SystemActions.FollowUp) {
            controller.speech.speak("एक बार settings में Maa Assistant को permission देनी होगी।")
            val action = when (followUp) {
                SystemActions.FollowUp.OpenDndAccessSettings ->
                    Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                else -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            }
            runCatching {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.e(TAG, "cannot open $action from service", it) }
        }

        override fun ttsEngineUnavailable() {
            handler.post { updateNotification(getString(R.string.tts_unavailable)) }
        }

        override fun ttsVoiceDataMissing() {
            handler.post {
                // She can't hear the reason; the notification is the only channel here.
                updateNotification(getString(R.string.tts_needs_voice))
                openMainActivity()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = AssistantController.get(this)
        controller.bindServiceUi(serviceUi)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> {
                bubbleMode = true
                prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, true).apply()
                goForeground(idleText(), mic = false)
                showBubble()
            }

            ACTION_HIDE_BUBBLE -> {
                bubbleMode = false
                prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, false).apply()
                // Satisfy the startForegroundService() contract for this delivery, then wind down.
                goForeground(idleText(), mic = false)
                hideBubble()
                stopIfIdle()
            }

            ACTION_START_TURN -> {
                val hint = intent?.getStringExtra(EXTRA_SOURCE_PKG)
                    ?: AssistantAccessibilityService.instance?.lastForegroundApp
                bubbleMode = bubbleMode || prefs.getBoolean(KEY_BUBBLE_ENABLED, false)
                goForeground(getString(R.string.status_listening), mic = hasMic())
                if (bubbleMode) showBubble()
                announceListening()
                if (controller.shareRunning) {
                    Log.i(TAG, "ACTION_START_TURN ignored — a share is already in progress")
                } else {
                    Log.i(TAG, "ACTION_START_TURN — source hint = $hint")
                    controller.startTurn(hint)
                }
                // startTurn bailed / was skipped (no key / offline / permission / share running)
                // — don't linger foreground.
                if (!controller.busy) scheduleStopIfIdle()
            }

            else -> {
                // Bare restart by the system (START_STICKY) or an unknown action. Restore the
                // bubble if it was on; otherwise stand up foreground briefly and stop cleanly.
                goForeground(idleText(), mic = false)
                if (prefs.getBoolean(KEY_BUBBLE_ENABLED, false)) {
                    bubbleMode = true
                    showBubble()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.unbindServiceUi(serviceUi)
        hideBubble()
        super.onDestroy()
    }

    // ---- foreground / notification ----------------------------------------

    private fun goForeground(text: String, mic: Boolean) {
        val notif = buildNotification(text)
        val sdk = Build.VERSION.SDK_INT
        try {
            when {
                sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    // Android 14+ enforces the type. specialUse backs the persistent bubble;
                    // microphone is added only while RECORD_AUDIO is actually granted.
                    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    startForeground(NOTIF_ID, notif, type)
                    micTypeApplied = mic
                }
                sdk >= Build.VERSION_CODES.R && mic -> {
                    // API 30–33: the microphone FGS type exists and is required for background
                    // mic access. (Pre-30 the type constant is invalid; mic works without it.)
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    micTypeApplied = true
                }
                else -> {
                    startForeground(NOTIF_ID, notif)
                    micTypeApplied = false
                }
            }
        } catch (e: Exception) {
            // e.g. Android 14 rejecting the microphone type when RECORD_AUDIO isn't granted.
            Log.e(TAG, "startForeground(mic=$mic) failed — retrying without a type", e)
            runCatching { startForeground(NOTIF_ID, notif) }
            micTypeApplied = false
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun idleText() = getString(R.string.notif_idle)

    /** Stop the service unless the bubble should stay up or a turn / share is still running. */
    private fun stopIfIdle() {
        if (bubbleMode) {
            // Keep the bubble; just shed the microphone foreground type once the turn is done.
            if (micTypeApplied && !controller.busy) goForeground(idleText(), mic = false)
            return
        }
        if (controller.busy || controller.shareRunning) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleStopIfIdle() {
        handler.postDelayed({ stopIfIdle() }, IDLE_STOP_DELAY_MS)
        // Hard backstop: if a turn / share somehow never reports completion, don't stay
        // foreground forever (unless the bubble is meant to be up).
        handler.postDelayed({
            if (!bubbleMode) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, IDLE_STOP_HARD_MS)
    }

    private fun openMainActivity() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---- floating bubble -------------------------------------------------

    private fun showBubble() {
        if (bubble != null) return
        val wm = windowManager ?: return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "showBubble: SYSTEM_ALERT_WINDOW not granted — cannot add the overlay")
            updateNotification(getString(R.string.bubble_needs_permission))
            return
        }

        val sizePx = (BUBBLE_DP * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundResource(R.drawable.bubble_background)
            setColorFilter(android.graphics.Color.WHITE)
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = getString(R.string.bubble_content_desc)
            // Unobtrusive at rest (like the system Accessibility Button overlay); opaque only
            // while a turn is actually in progress — see setBubbleActive().
            alpha = if (controller.busy) BUBBLE_ALPHA_ACTIVE else BUBBLE_ALPHA_IDLE
        }

        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_BUBBLE_X, 24)
            y = prefs.getInt(KEY_BUBBLE_Y, 320)
        }

        view.setOnTouchListener(BubbleTouch(lp))
        runCatching { wm.addView(view, lp) }
            .onSuccess { bubble = view; Log.i(TAG, "bubble shown") }
            .onFailure { Log.e(TAG, "addView(bubble) failed", it) }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        bubble = null
        runCatching { windowManager?.removeView(v) }
    }

    /** Opaque while a turn is in flight (tap through listening/processing), transparent at rest. */
    private fun setBubbleActive(active: Boolean) {
        bubble?.alpha = if (active) BUBBLE_ALPHA_ACTIVE else BUBBLE_ALPHA_IDLE
    }

    /** Drag to reposition; a tap (little movement, short press) starts a voice turn. */
    private inner class BubbleTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private val slop = ViewConfiguration.get(this@AssistantService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    downAt = SystemClock.uptimeMillis()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    runCatching { windowManager?.updateViewLayout(v, lp) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble())
                    val heldMs = SystemClock.uptimeMillis() - downAt
                    when {
                        // A tap = it never moved past touch-slop. Duration is only an upper
                        // bound so an accidental long rest doesn't fire — a slow, deliberate
                        // press by an elderly user must still count (was 250 ms, far too tight).
                        moved < slop && heldMs < TAP_MAX_MS -> onBubbleTapped()
                        moved >= slop -> {
                            // A drag — persist the new resting position.
                            prefs.edit()
                                .putInt(KEY_BUBBLE_X, lp.x)
                                .putInt(KEY_BUBBLE_Y, lp.y)
                                .apply()
                        }
                        else -> Log.i(TAG, "bubble: press ignored (held ${heldMs}ms, no move)")
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun onBubbleTapped() {
        announceListening()
        val src = AssistantAccessibilityService.instance?.lastForegroundApp
        if (controller.busy || controller.shareRunning) {
            Log.i(
                TAG,
                "bubble tap ignored — ${if (controller.busy) "a turn" else "a share"} is in progress"
            )
            return
        }
        Log.i(TAG, "bubble tapped — source = $src")
        goForeground(getString(R.string.status_listening), mic = hasMic())
        controller.startTurn(src)
        if (!controller.busy) scheduleStopIfIdle()
    }

    /**
     * Immediate "the trigger landed" feedback for a turn started from outside the app (bubble
     * or Accessibility Button) — she has no status screen in view, and a Gemini round-trip on a
     * weak connection can take 20-30 s, so without this the tap feels dead.
     */
    private fun announceListening() {
        runCatching {
            Toast.makeText(this, R.string.status_listening, Toast.LENGTH_SHORT).show()
        }
        bubble?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    companion object {
        private const val TAG = "MotherAssistant"

        const val ACTION_SHOW_BUBBLE = "com.family.motherassistant.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.family.motherassistant.HIDE_BUBBLE"
        const val ACTION_START_TURN = "com.family.motherassistant.START_TURN"
        const val EXTRA_SOURCE_PKG = "source_pkg"

        const val PREFS = "maa_assistant"
        const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"

        private const val CHANNEL_ID = "assistant_service"
        private const val NOTIF_ID = 42
        private const val BUBBLE_DP = 56
        /** Idle vs. active opacity — unobtrusive at rest, fully visible only during a turn. */
        private const val BUBBLE_ALPHA_IDLE = 0.35f
        private const val BUBBLE_ALPHA_ACTIVE = 1.0f
        /** Upper bound on a press still counting as a tap (not a lingering accidental rest). */
        private const val TAP_MAX_MS = 1_200L
        private const val IDLE_STOP_DELAY_MS = 20_000L
        private const val IDLE_STOP_HARD_MS = 90_000L

        fun isBubbleEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BUBBLE_ENABLED, false)

        private fun send(context: Context, action: String, sourcePkg: String? = null) {
            val i = Intent(context, AssistantService::class.java).setAction(action)
            if (sourcePkg != null) i.putExtra(EXTRA_SOURCE_PKG, sourcePkg)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (e: Exception) {
                // Android 12+ can block starting a foreground service from the background; the
                // SYSTEM_ALERT_WINDOW grant normally exempts us. Log rather than crash the caller
                // (the a11y service, or MainActivity).
                Log.e(TAG, "could not start AssistantService for $action", e)
            }
        }

        fun showBubble(context: Context) = send(context, ACTION_SHOW_BUBBLE)
        fun hideBubble(context: Context) = send(context, ACTION_HIDE_BUBBLE)

        /** Called by [AssistantAccessibilityService] when the system Accessibility Button is pressed. */
        fun triggerFromButton(context: Context) {
            val src = AssistantAccessibilityService.instance?.lastForegroundApp
            send(context, ACTION_START_TURN, src)
        }
    }
}
