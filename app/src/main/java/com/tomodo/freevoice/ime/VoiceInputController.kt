package com.tomodo.freevoice.ime

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the one voice-input job.  The small interfaces deliberately keep its
 * cancellation and state rules unit-testable without Android framework types.
 */
class VoiceInputController(
    private val recorder: Recorder,
    private val gateway: Gateway,
    private val callbacks: Callbacks,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    data class FormattedText(val text: String, val fallback: Boolean)

    interface Recorder {
        fun start()
        fun stop(): File?
        fun cancel()
    }

    interface Gateway {
        fun transcribe(wav: File): String
        fun format(text: String, packageName: String): FormattedText
        fun cancel()
    }

    interface Callbacks {
        fun stateChanged(state: VoiceInputState)
        fun committed(jobId: Long, text: String, packageName: String, formatFallback: Boolean)
        fun failed(jobId: Long, message: String, error: Throwable? = null)
    }

    private val lock = Any()
    private var state: VoiceInputState = VoiceInputState.Idle
    private var packageName = ""
    private var cancelled = false
    private var closed = false
    private var generation = 0L

    fun start(targetPackage: String, validationError: String?): Long? = synchronized(lock) {
        if (closed) return null
        if (state !is VoiceInputState.Idle && state !is VoiceInputState.Error) return null

        generation++
        if (validationError != null) {
            failUserLocked(validationError)
            return null
        }

        cancelled = false
        packageName = targetPackage
        transitionLocked(VoiceInputState.Starting)

        try {
            recorder.start()
            transitionLocked(VoiceInputState.Recording(elapsedRealtimeMillis()))
            generation
        } catch (error: Exception) {
            failLocked("録音を開始できなかった", error)
            null
        }
    }

    fun stop() = synchronized(lock) {
        if (state !is VoiceInputState.Recording || closed) return

        val wav = try {
            recorder.stop()
        } catch (error: Exception) {
            failLocked("録音を終了できなかった", error)
            return
        }
        if (wav == null || wav.length() <= 44L) {
            failUserLocked("音声が録音されていない")
            return
        }

        transitionLocked(VoiceInputState.Transcribing)
        val jobPackage = packageName
        val jobGeneration = generation
        executor.execute {
            try {
                val raw = gateway.transcribe(wav).trim()
                if (raw.isBlank()) throw UserVisibleException("音声を認識できなかった")
                if (!isActive(jobGeneration)) return@execute
                transition(jobGeneration, VoiceInputState.Formatting)
                val formatted = gateway.format(raw, jobPackage)
                val text = formatted.text.trim().ifBlank { raw }
                if (isActive(jobGeneration)) {
                    callbacks.committed(jobGeneration, text, jobPackage, formatted.fallback)
                    transition(jobGeneration, VoiceInputState.Idle)
                }
            } catch (error: Exception) {
                if (isActive(jobGeneration)) {
                    fail(jobGeneration, classify(error), error)
                }
            } finally {
                wav.delete()
            }
        }
    }

    /** Calling this while active is idempotent and suppresses stale callbacks. */
    fun cancel() = synchronized(lock) {
        cancelLocked()
    }

    fun close() = synchronized(lock) {
        closed = true
        cancelLocked()
        executor.shutdownNow()
    }

    fun currentState(): VoiceInputState = synchronized(lock) { state }

    private fun isActive(jobGeneration: Long): Boolean = synchronized(lock) {
        !cancelled && !closed && generation == jobGeneration
    }

    private fun transition(jobGeneration: Long, next: VoiceInputState) = synchronized(lock) {
        if (!cancelled && !closed && generation == jobGeneration) {
            transitionLocked(next)
        }
    }

    private fun transitionLocked(next: VoiceInputState) {
        state = next
        callbacks.stateChanged(next)
    }

    private fun fail(jobGeneration: Long, message: String, error: Throwable) = synchronized(lock) {
        if (generation == jobGeneration) {
            failLocked(message, error)
        }
    }

    private fun failLocked(message: String, error: Throwable) {
        val userMessage = (error as? UserVisibleException)?.message ?: message
        transitionLocked(VoiceInputState.Error(userMessage))
        callbacks.failed(generation, message, error)
    }

    private fun failUserLocked(message: String) {
        transitionLocked(VoiceInputState.Error(message))
        callbacks.failed(generation, message, null)
    }

    private fun cancelLocked() {
        cancelled = true
        generation++
        recorder.cancel()
        gateway.cancel()
        transitionLocked(VoiceInputState.Idle)
    }

    private fun classify(error: Throwable): String = when {
        error.message?.contains("HTTP 401") == true ||
            error.message?.contains("HTTP 403") == true -> "API キーまたは権限を確認して"
        error.message?.contains("HTTP 429") == true -> "API が混み合っている。少し待って再試行して"
        error.message?.contains("Cancelled", true) == true -> "キャンセルした"
        else -> (error as? UserVisibleException)?.message ?: "通信または文字起こしに失敗した"
    }

    private class UserVisibleException(message: String) : Exception(message)
}
