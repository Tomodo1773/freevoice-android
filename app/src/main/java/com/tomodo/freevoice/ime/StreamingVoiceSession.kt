package com.tomodo.freevoice.ime

import com.microsoft.cognitiveservices.speech.CancellationReason
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechRecognitionCanceledEventArgs
import com.microsoft.cognitiveservices.speech.SpeechRecognizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.tomodo.freevoice.audio.WavRecorder
import com.tomodo.freevoice.diag.DiagLogger
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Recognizes over a WebSocket while the user is still speaking, so stopping only
 * has to collect text that already arrived.
 *
 * Reconnects stay inside this class.  The only thing it tells the outside world is
 * the single stop signal, exactly as the Windows version settled on (ADR 0001).
 */
internal class StreamingVoiceSession(
    private val endpoint: String,
    private val apiKey: String,
    private val language: String,
    private val diagnostics: DiagLogger,
    private val onInterim: (String) -> Unit,
    private val onStopSignal: () -> Unit,
    private val maxDurationMillis: Long = WavRecorder.MAX_DURATION_MS,
) : VoiceInputController.VoiceSession {
    private class Native(
        val recognizer: SpeechRecognizer?,
        val config: SpeechConfig?,
        val audio: AudioConfig?,
    )

    private val lock = Any()
    private val transcript = SpeechTranscript()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "FreeVoiceSpeech")
    }

    private var recognizer: SpeechRecognizer? = null
    private var config: SpeechConfig? = null
    private var audio: AudioConfig? = null
    private var closed = false
    private var stopping = false
    private var reconnecting = false
    private var abortMessage: String? = null

    override fun start() {
        synchronized(lock) { check(!closed) { "Session already closed" } }
        try {
            openRecognizer()
        } catch (error: LinkageError) {
            throw UserVisibleException("音声認識モジュールを読み込めなかった", error)
        }
        worker.schedule(onStopSignal, maxDurationMillis, TimeUnit.MILLISECONDS)
    }

    override fun finish(): String {
        synchronized(lock) { stopping = true }

        // 再接続の途中なら決着を待つ。待ち切れなくても停止は進める。
        worker.shutdown()
        val settled = try {
            worker.awaitTermination(RECONNECT_SETTLE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!settled) {
            diagnostics.warn("speech", "reconnect did not settle before stop")
            worker.shutdownNow()
        }

        try {
            synchronized(lock) { recognizer }
                ?.stopContinuousRecognitionAsync()
                ?.get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            diagnostics.warn("speech", "stopContinuousRecognitionAsync failed", error)
        }
        closeRecognizer()

        val confirmed = transcript.confirmedText()
        val interim = transcript.interimText()
        val aborted = synchronized(lock) { abortMessage }
        return when {
            confirmed.isNotEmpty() -> {
                if (aborted != null) {
                    diagnostics.warn("speech", "salvaged ${confirmed.length} chars after abort")
                }
                confirmed
            }
            // stop と recognized 配信のあいだにはレースがあり、発話中に停止すると
            // 確定が届かない端末がある。黙って空を返さず暫定を採用する。
            interim.isNotEmpty() -> {
                diagnostics.warn("speech", "interim fallback used (${interim.length} chars)")
                interim
            }
            aborted != null -> throw UserVisibleException(aborted)
            else -> ""
        }
    }

    override fun cancel() {
        val native = synchronized(lock) {
            if (closed) return
            closed = true
            detachLocked()
        }
        worker.shutdownNow()
        // close はブロックしうる。cancel は非ブロッキングの約束なので呼び出し側を待たせない。
        Thread({ closeQuietly(native) }, "FreeVoiceSpeechClose").start()
    }

    private fun openRecognizer() {
        var opened: Native? = null
        try {
            val newConfig = SpeechConfig.fromEndpoint(URI(endpoint), apiKey).apply {
                speechRecognitionLanguage = language
            }
            val newAudio = AudioConfig.fromDefaultMicrophoneInput()
            val newRecognizer = SpeechRecognizer(newConfig, newAudio)
            opened = Native(newRecognizer, newConfig, newAudio)

            newRecognizer.recognizing.addEventListener { _, event ->
                if (event.result.reason == ResultReason.RecognizingSpeech) {
                    emit(transcript.observeInterim(event.result.text.orEmpty()))
                }
            }
            newRecognizer.recognized.addEventListener { _, event ->
                if (event.result.reason == ResultReason.RecognizedSpeech) {
                    emit(transcript.confirm(event.result.text.orEmpty()))
                }
            }
            newRecognizer.canceled.addEventListener { _, event ->
                onCanceled(event.toCancellation())
            }
            newRecognizer.sessionStopped.addEventListener { _, _ ->
                synchronized(lock) { if (closed || stopping) return@addEventListener }
                diagnostics.warn("speech", "session stopped unexpectedly mid-recording")
            }

            synchronized(lock) {
                check(!closed) { "Session already closed" }
                recognizer = newRecognizer
                config = newConfig
                audio = newAudio
            }
            opened = null

            // Future は待たない。接続の成否は canceled イベントで届き、録音中の中断と
            // 同じ1本の経路で扱われる。開始が「数秒かかるプロセス」になるのを避ける。
            newRecognizer.startContinuousRecognitionAsync()
        } catch (error: Throwable) {
            closeQuietly(opened)
            throw error
        }
    }

    private fun emit(text: String?) {
        if (text == null) return
        synchronized(lock) { if (closed) return }
        onInterim(text)
    }

    /**
     * 原因究明のため、どの分岐に入るかに関わらず必ず記録する。
     * 「認識中に黙って止まったのにログに何も残らない」状態を作らない。
     */
    private fun onCanceled(cancellation: SpeechCancellation) {
        val summary = cancellation.summary()
        val shouldReconnect = synchronized(lock) {
            when {
                closed -> {
                    diagnostics.info("speech", "canceled after cancel: $summary")
                    return
                }
                // abort 済みなら停止シグナルが main を回っている最中。もう繋ぎ直さない。
                stopping || abortMessage != null -> {
                    diagnostics.info("speech", "canceled during stop (normal): $summary")
                    return
                }
                reconnecting -> {
                    diagnostics.warn("speech", "canceled while reconnecting: $summary")
                    return
                }
                cancellation.isRetryable() -> {
                    reconnecting = true
                    true
                }
                else -> false
            }
        }

        if (!shouldReconnect) {
            diagnostics.error("speech", "recognition canceled, giving up: $summary")
            abort(cancellation.userMessage())
            return
        }

        diagnostics.warn("speech", "recognition interrupted, will reconnect: $summary")
        transcript.promoteInterim()
        try {
            worker.execute { reconnect(cancellation) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(lock) { reconnecting = false }
        }
    }

    private fun reconnect(cancellation: SpeechCancellation) {
        for (attempt in 1..MAX_RECONNECTS) {
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                synchronized(lock) { reconnecting = false }
                return
            }
            synchronized(lock) {
                if (closed || stopping) {
                    reconnecting = false
                    return
                }
            }
            closeRecognizer()
            try {
                openRecognizer()
                synchronized(lock) { reconnecting = false }
                diagnostics.info("speech", "reconnected after $attempt attempt(s)")
                return
            } catch (error: Throwable) {
                diagnostics.warn("speech", "reconnect $attempt/$MAX_RECONNECTS failed", error)
            }
        }
        synchronized(lock) { reconnecting = false }
        diagnostics.error("speech", "giving up after $MAX_RECONNECTS reconnects")
        abort(cancellation.userMessage())
    }

    /** The single stop signal; the controller cannot tell it from a released key. */
    private fun abort(message: String) {
        synchronized(lock) {
            if (closed || stopping || abortMessage != null) return
            abortMessage = message
        }
        onStopSignal()
    }

    private fun closeRecognizer() = closeQuietly(synchronized(lock) { detachLocked() })

    private fun detachLocked(): Native = Native(recognizer, config, audio).also {
        recognizer = null
        config = null
        audio = null
    }

    /** Order matters: the recognizer must go before what it was built from. */
    private fun closeQuietly(native: Native?) {
        if (native == null) return
        runCatching { native.recognizer?.close() }
        runCatching { native.audio?.close() }
        runCatching { native.config?.close() }
    }

    private companion object {
        const val MAX_RECONNECTS = 3
        const val RECONNECT_DELAY_MS = 1_000L
        const val RECONNECT_SETTLE_MS = 5_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun SpeechRecognitionCanceledEventArgs.toCancellation() = SpeechCancellation(
    reason = when (reason) {
        CancellationReason.EndOfStream -> SpeechCancelReason.END_OF_STREAM
        CancellationReason.CancelledByUser -> SpeechCancelReason.BY_USER
        else -> SpeechCancelReason.ERROR
    },
    errorCode = errorCode?.name.orEmpty(),
    details = errorDetails.orEmpty(),
)
