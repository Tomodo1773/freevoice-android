package com.tomodo.freevoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures a single, bounded 16 kHz mono PCM stream and hands every block to
 * [onChunk] as it is read.  Owns the microphone, the recording limit and the
 * stop/cancel races; what the bytes become is the caller's business.
 */
class PcmRecorder(
    private val onMaxDurationReached: () -> Unit = {},
    private val onChunk: (ByteArray, Int) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000

        /** 録音の上限。ストリーミング認識も同じ上限で自動停止させる。 */
        internal const val MAX_DURATION_MS = 300_000L
    }

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /**
     * true のとき、直前の録音は停止できないまま見捨てられている。その録音のために
     * 作った資源が呼び出し側に残っているので、次を始める前に片付ける。
     */
    @get:Synchronized val hasAbandonedRecording: Boolean get() = worker != null

    /**
     * The IME checks RECORD_AUDIO immediately before calling this method.
     * SecurityException is deliberately allowed to reach VoiceInputController, which
     * presents the permission/start failure to the user.
     */
    @SuppressLint("MissingPermission")
    @Synchronized fun start() {
        check(!recording.get() && worker?.isAlive != true) { "Already recording" }
        worker = null
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Microphone is unavailable" }
        var localRecorder: AudioRecord? = null
        try {
            localRecorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
            check(localRecorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }
            val activeRecorder = requireNotNull(localRecorder)
            cancelled.set(false); recording.set(true); recorder = activeRecorder
            activeRecorder.startRecording()
            worker = Thread { capture(activeRecorder, minBuffer) }.apply { name = "FreeVoiceRecorder"; start() }
        } catch (e: Exception) {
            recording.set(false); recorder = null; worker = null
            localRecorder?.runCatching { stop() }; localRecorder?.release()
            throw e
        }
    }

    /** Ends capture.  Throws when the recording thread will not stop. */
    @Synchronized fun stop() = finishCapture()

    @Synchronized fun cancel() {
        cancelled.set(true)
        finishCapture()
    }

    private fun capture(activeRecorder: AudioRecord, bufferBytes: Int) {
        var maxDurationReached = false
        try {
            val buffer = ByteArray(bufferBytes)
            val deadline = System.currentTimeMillis() + MAX_DURATION_MS
            while (recording.get()) {
                if (System.currentTimeMillis() >= deadline) {
                    maxDurationReached = true
                    break
                }
                val count = activeRecorder.read(buffer, 0, buffer.size)
                if (count > 0) onChunk(buffer, count)
            }
        } finally {
            recording.set(false)
            if (maxDurationReached && !cancelled.get()) onMaxDurationReached()
        }
    }

    @Synchronized private fun finishCapture() {
        recording.set(false)
        val activeRecorder = recorder
        val activeWorker = worker
        var released = false
        activeRecorder?.runCatching { stop() }
        activeWorker?.join(2_000)
        if (activeWorker?.isAlive == true) {
            // Releasing unblocks a device that did not promptly return from read() after stop().
            activeRecorder?.release()
            released = true
            recorder = null
            activeWorker.join(500)
        }
        if (activeWorker?.isAlive == true) {
            // Never let the caller consume data while this thread can still append to it.
            throw IllegalStateException("Recorder worker did not stop")
        }
        worker = null
        if (!released) activeRecorder?.runCatching { release() }
        recorder = null
    }
}
