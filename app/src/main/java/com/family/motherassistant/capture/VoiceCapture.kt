package com.family.motherassistant.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Records a short spoken command to an AAC/ADTS file (`audio/aac`, the format Gemini accepts
 * directly as inline audio).
 *
 * Auto-stops when the speaker goes quiet for [SILENCE_MS] after having said something, or when
 * [MAX_MS] is reached — whichever comes first. Phase 1 only needs one clean clip to hand to
 * Gemini; there is no streaming or partial transcription.
 *
 * All [Listener] callbacks are delivered on the main thread.
 */
class VoiceCapture(private val context: Context) {

    interface Listener {
        /** A playable AAC file, guaranteed non-empty. */
        fun onFinished(audioFile: File)

        /** Recording could not start, or produced nothing usable. [message] is Hindi, ready for TTS. */
        fun onError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var listener: Listener? = null
    private var recording = false

    private var startedAt = 0L
    private var lastLoudAt = 0L
    private var heardSpeech = false

    fun start(listener: Listener) {
        if (recording) return
        this.listener = listener

        val file = File(context.cacheDir, "command.aac")
        if (file.exists()) file.delete()
        outputFile = file

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(32_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            Log.e(TAG, "recorder start failed", e)
            safeRelease(rec)
            recorder = null
            deliverError("रिकॉर्डिंग शुरू नहीं हो पाई")
            return
        }

        recorder = rec
        recording = true
        startedAt = System.currentTimeMillis()
        lastLoudAt = startedAt
        heardSpeech = false
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    /** Stop early (e.g. the user tapped again). No-op when not recording. */
    fun stop() {
        if (recording) finishRecording()
    }

    /** Abandon any in-progress recording and drop the listener. Safe to call from onDestroy(). */
    fun cancel() {
        handler.removeCallbacks(pollRunnable)
        recorder?.let { safeRelease(it) }
        recorder = null
        recording = false
        listener = null
        outputFile?.delete()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!recording) return
            val now = System.currentTimeMillis()
            val amplitude = try {
                recorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }

            if (amplitude > SPEECH_AMPLITUDE) {
                heardSpeech = true
                lastLoudAt = now
            }

            val elapsed = now - startedAt
            val quietRun = now - lastLoudAt
            val done = elapsed >= MAX_MS ||
                (heardSpeech && elapsed >= MIN_MS && quietRun >= SILENCE_MS)

            if (done) finishRecording() else handler.postDelayed(this, POLL_MS)
        }
    }

    private fun finishRecording() {
        handler.removeCallbacks(pollRunnable)
        recording = false

        val rec = recorder
        recorder = null
        val file = outputFile

        var stopped = false
        try {
            rec?.stop()
            stopped = true
        } catch (e: RuntimeException) {
            // stop() throws when no valid audio was captured (clip too short / immediate silence).
            Log.w(TAG, "recorder stop() failed — clip too short?", e)
        } finally {
            rec?.let { safeRelease(it) }
        }

        val l = listener
        listener = null
        if (stopped && file != null && file.length() > 0L) {
            l?.onFinished(file)
        } else {
            file?.delete()
            l?.onError("आवाज़ साफ़ नहीं मिली, फिर से बोलिए")
        }
    }

    private fun deliverError(message: String) {
        recording = false
        val l = listener
        listener = null
        l?.onError(message)
    }

    private fun safeRelease(rec: MediaRecorder) {
        try {
            rec.reset()
        } catch (_: Exception) {
        }
        try {
            rec.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "VoiceCapture"
        private const val POLL_MS = 150L
        private const val MIN_MS = 700L
        private const val MAX_MS = 8_000L
        private const val SILENCE_MS = 1_500L

        /** MediaRecorder.getMaxAmplitude() is 0..32767; ~1800 clears typical room noise. */
        private const val SPEECH_AMPLITUDE = 1_800
    }
}
