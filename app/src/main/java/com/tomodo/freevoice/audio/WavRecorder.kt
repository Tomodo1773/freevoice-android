package com.tomodo.freevoice.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Records with [PcmRecorder] and finalizes the captured stream as a WAV file. */
class WavRecorder(
    private val cacheDir: File,
    onMaxDurationReached: () -> Unit = {},
) {
    private val capture = PcmRecorder(onMaxDurationReached, ::write)
    private val cancelled = AtomicBoolean(false)
    private var output: FileOutputStream? = null
    private var rawFile: File? = null
    private var wavFile: File? = null

    @Synchronized fun start() {
        // 停止できなかった録音スレッドはまだ生きうる。ストリームは閉じずに手放して
        // write() を空振りさせてから、その録音だけのファイルを消す。閉じると生きた
        // スレッドの write が例外になり、プロセスごと落ちる。
        if (capture.hasAbandonedRecording) {
            output = null
            discardFiles()
        }
        check(cacheDir.exists() || cacheDir.mkdirs()) { "Could not create recording cache" }
        val raw = File.createTempFile("freevoice-", ".pcm", cacheDir)
        rawFile = raw
        wavFile = File.createTempFile("freevoice-", ".wav", cacheDir).also { it.delete() }
        cancelled.set(false)
        output = FileOutputStream(raw)
        try {
            capture.start()
        } catch (e: Exception) {
            closeOutput()
            discardFiles()
            throw e
        }
    }

    /** Stops capture and returns the finalized file, or null when cancelled/empty. */
    @Synchronized fun stop(): File? {
        capture.stop()
        closeOutput()
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
        capture.cancel()
        closeOutput()
        discardFiles()
    }

    /** 録音スレッドから呼ばれる。stop() は書き込みが終わってから戻る。 */
    private fun write(buffer: ByteArray, count: Int) {
        output?.write(buffer, 0, count)
    }

    private fun closeOutput() {
        output?.runCatching { close() }
        output = null
    }

    private fun discardFiles() {
        rawFile?.delete(); wavFile?.delete()
        rawFile = null; wavFile = null
    }

    private fun writeWavHeader(file: File, pcmBytes: Long) {
        RandomAccessFile(file, "rw").use { out ->
            fun intLE(value: Long) { out.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())) }
            fun shortLE(value: Int) { out.write(byteArrayOf(value.toByte(), (value shr 8).toByte())) }
            val rate = PcmRecorder.SAMPLE_RATE
            out.writeBytes("RIFF"); intLE(36 + pcmBytes); out.writeBytes("WAVEfmt "); intLE(16)
            shortLE(1); shortLE(1); intLE(rate.toLong()); intLE(rate * 2L)
            shortLE(2); shortLE(16); out.writeBytes("data"); intLE(pcmBytes)
        }
    }
}
