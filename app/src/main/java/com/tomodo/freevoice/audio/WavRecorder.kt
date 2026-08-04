package com.tomodo.freevoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Records a single, bounded 16 kHz mono PCM stream and finalizes it as a WAV file. */
class WavRecorder(
    private val cacheDir: File,
    private val onMaxDurationReached: () -> Unit = {},
) {
    companion object {
        const val SAMPLE_RATE = 16_000

        /** 録音の上限。ストリーミング認識も同じ上限で自動停止させる。 */
        internal const val MAX_DURATION_MS = 300_000L
    }

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var rawFile: File? = null
    private var wavFile: File? = null
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /**
     * The IME checks RECORD_AUDIO immediately before calling this method.
     * SecurityException is deliberately allowed to reach VoiceInputController, which
     * presents the permission/start failure to the user.
    */
    @SuppressLint("MissingPermission")
    @Synchronized fun start() {
        val previousWorker = worker
        check(!recording.get() && previousWorker?.isAlive != true) { "Already recording" }
        // A stalled worker may finish after finishCapture() has reported failure.  It
        // owns only this abandoned recording, so clean its files before a new one.
        if (previousWorker != null) {
            rawFile?.delete()
            wavFile?.delete()
            rawFile = null
            wavFile = null
            worker = null
        }
        check(cacheDir.exists() || cacheDir.mkdirs()) { "Could not create recording cache" }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Microphone is unavailable" }
        var localRecorder: AudioRecord? = null
        try {
            localRecorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
            check(localRecorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone" }
            rawFile = File.createTempFile("freevoice-", ".pcm", cacheDir)
            wavFile = File.createTempFile("freevoice-", ".wav", cacheDir).also { it.delete() }
            val activeRecorder = requireNotNull(localRecorder)
            val activeRawFile = requireNotNull(rawFile)
            cancelled.set(false); recording.set(true); recorder = activeRecorder
            activeRecorder.startRecording()
            worker = Thread {
                var maxDurationReached = false
                try {
                    FileOutputStream(activeRawFile).use { output ->
                        val buffer = ByteArray(minBuffer); val deadline = System.currentTimeMillis() + MAX_DURATION_MS
                        while (recording.get()) {
                            if (System.currentTimeMillis() >= deadline) {
                                maxDurationReached = true
                                break
                            }
                            val count = activeRecorder.read(buffer, 0, buffer.size)
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                } finally {
                    recording.set(false)
                    if (maxDurationReached && !cancelled.get()) onMaxDurationReached()
                }
            }.apply { name = "FreeVoiceRecorder"; start() }
        } catch (e: Exception) {
            recording.set(false); recorder = null; worker = null
            rawFile?.delete(); wavFile?.delete(); rawFile = null; wavFile = null
            localRecorder?.runCatching { stop() }; localRecorder?.release()
            throw e
        }
    }

    /** Stops capture and returns the finalized file, or null when cancelled/empty. */
    @Synchronized fun stop(): File? {
        finishCapture()
        val raw = rawFile ?: return null
        val target = wavFile ?: return null
        return try {
            if (cancelled.get() || raw.length() == 0L) null else {
                writeWavHeader(target, raw.length())
                FileOutputStream(target, true).use { out -> raw.inputStream().use { it.copyTo(out) } }
                target
            }
        } finally {
            raw.delete()
            rawFile = null
            if (cancelled.get()) target.delete()
        }
    }

    @Synchronized fun cancel() {
        cancelled.set(true)
        finishCapture()
        rawFile?.delete(); wavFile?.delete()
        rawFile = null; wavFile = null
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
            activeWorker?.join(500)
        }
        if (activeWorker?.isAlive == true) {
            // Never finalize/read a WAV while its PCM writer can still mutate it.
            throw IllegalStateException("Recorder worker did not stop")
        }
        worker = null
        if (!released) activeRecorder?.runCatching { release() }
        recorder = null
    }

    private fun writeWavHeader(file: File, pcmBytes: Long) {
        RandomAccessFile(file, "rw").use { out ->
            fun intLE(value: Long) { out.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())) }
            fun shortLE(value: Int) { out.write(byteArrayOf(value.toByte(), (value shr 8).toByte())) }
            out.writeBytes("RIFF"); intLE(36 + pcmBytes); out.writeBytes("WAVEfmt "); intLE(16)
            shortLE(1); shortLE(1); intLE(SAMPLE_RATE.toLong()); intLE(SAMPLE_RATE * 2L)
            shortLE(2); shortLE(16); out.writeBytes("data"); intLE(pcmBytes)
        }
    }
}
