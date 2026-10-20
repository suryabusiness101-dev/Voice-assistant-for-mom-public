package com.family.motherassistant.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Wrapper over Android TTS, locked to `hi-IN`.
 *
 * Robustness (some OEM phones, e.g. Xiaomi/HyperOS, ship a default TTS engine with no
 * Hindi at all, which would leave the app silently mute):
 *  - Explicitly asks for Google's engine ([GOOGLE_TTS_PACKAGE]) instead of trusting
 *    whatever the phone has set as its default engine. Falls back to the system default
 *    engine only if Google's cannot be initialised.
 *  - Separates "engine present but the hi-IN voice is not downloaded" (LANG_MISSING_DATA)
 *    from "engine cannot do Hindi at all" (LANG_NOT_SUPPORTED). The first is reported via
 *    [Listener.onVoiceDataMissing] so the caller can launch the one-time voice download;
 *    the second triggers the default-engine fallback, then [Listener.onEngineUnavailable].
 *  - [retryInit] re-runs initialisation from scratch (call it after the user returns from
 *    the ACTION_INSTALL_TTS_DATA flow). Any text queued in the meantime is flushed once
 *    the voice is ready, so she never has to repeat the command.
 *
 * Text passed in before the engine is ready is queued (capped at [MAX_PENDING]) and
 * flushed on init. Each [speak] flushes any previous utterance (QUEUE_FLUSH) so replies
 * never pile up.
 */
class SpeechOutput(
    context: Context,
    private val listener: Listener? = null
) : TextToSpeech.OnInitListener {

    /** Init-time outcomes the host Activity must react to (show a message, start a flow). */
    interface Listener {
        /** Engine is up and hi-IN is ready to speak. */
        fun onReady() {}
        /** Engine is up but the hi-IN voice files are missing — offer the one-time download. */
        fun onVoiceDataMissing() {}
        /** No usable engine could speak Hindi, even after the default-engine fallback. */
        fun onEngineUnavailable() {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ready = false
    /** True once we've given up on Google's engine and fallen back to the system default. */
    private var usingFallbackEngine = false

    private val pending = ArrayDeque<Pair<String, (() -> Unit)?>>()
    private val callbacks = HashMap<String, () -> Unit>()

    init {
        createEngine(GOOGLE_TTS_PACKAGE)
    }

    /** Speak [text] in Hindi. [onComplete], if given, runs on the main thread when playback ends. */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onComplete?.let { main.post(it) }
            return
        }
        if (!ready) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(text to onComplete)
            return
        }
        enqueue(text, onComplete)
    }

    /** Whether hi-IN is loaded and [speak] will actually produce audio right now. */
    fun isReady(): Boolean = ready

    /**
     * Re-attempt initialisation from scratch — call after the user returns from the
     * ACTION_INSTALL_TTS_DATA flow. If the voice is now present, queued text is flushed.
     */
    fun retryInit() {
        usingFallbackEngine = false
        createEngine(GOOGLE_TTS_PACKAGE)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        callbacks.clear()
        pending.clear()
    }

    private fun createEngine(enginePackage: String?) {
        tts?.shutdown()
        ready = false
        tts = if (enginePackage != null) {
            TextToSpeech(appContext, this, enginePackage)
        } else {
            TextToSpeech(appContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed: status=$status, fallback=$usingFallbackEngine")
            fallbackOrGiveUp()
            return
        }
        val engine = tts ?: return

        when (engine.isLanguageAvailable(HINDI)) {
            TextToSpeech.LANG_MISSING_DATA -> {
                Log.w(TAG, "hi-IN voice data not installed (engine=${engine.defaultEngine})")
                notify { onVoiceDataMissing() }
                return
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.e(TAG, "engine cannot do hi-IN (engine=${engine.defaultEngine}), fallback=$usingFallbackEngine")
                fallbackOrGiveUp()
                return
            }
        }

        val langResult = engine.setLanguage(HINDI)
        ready = langResult != TextToSpeech.LANG_MISSING_DATA && langResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            Log.e(TAG, "hi-IN setLanguage failed: $langResult")
            notify { onVoiceDataMissing() }
            return
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = fireDone(utteranceId)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = fireDone(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = fireDone(utteranceId)
        })

        Log.i(TAG, "TTS ready on hi-IN (engine=${engine.defaultEngine}, fallback=$usingFallbackEngine)")
        notify { onReady() }
        while (pending.isNotEmpty()) {
            val (text, cb) = pending.removeFirst()
            enqueue(text, cb)
        }
    }

    /** Try the system default engine once; if that was already the fallback, report unavailable. */
    private fun fallbackOrGiveUp() {
        if (!usingFallbackEngine) {
            usingFallbackEngine = true
            main.post { createEngine(null) }
        } else {
            notify { onEngineUnavailable() }
        }
    }

    private fun notify(block: Listener.() -> Unit) {
        val l = listener ?: return
        main.post { l.block() }
    }

    private fun enqueue(text: String, onComplete: (() -> Unit)?) {
        val id = "utt-" + System.nanoTime()
        if (onComplete != null) callbacks[id] = onComplete
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onComplete?.let { main.post(it) }
        }
    }

    private fun fireDone(utteranceId: String?) {
        val cb = utteranceId?.let { callbacks.remove(it) } ?: return
        main.post(cb)
    }

    companion object {
        private const val TAG = "SpeechOutput"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        private const val MAX_PENDING = 8
        private val HINDI = Locale("hi", "IN")
    }
}
