package com.tomodo.freevoice.ime

import com.tomodo.freevoice.audio.PcmRecorder
import com.tomodo.freevoice.diag.DiagLogger
import com.tomodo.freevoice.network.GeminiLiveProtocol
import com.tomodo.freevoice.network.GeminiLiveProtocol.ServerEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Transcribes over the Gemini Live API while the user is still speaking, so stopping
 * only has to collect text that already arrived.
 *
 * Unlike the Azure Speech path this session owns the microphone itself, so the
 * recording limit is the recorder's own and needs no second timer.  A broken
 * connection is not retried: the recording cap is well inside the API's session
 * limit, so a drop means the network is gone, and reconnecting would lose the audio
 * spoken meanwhile anyway.  It ends like every other breakdown, through the single
 * stop signal, with the confirmed text salvaged (ADR 0001).
 */
internal class GeminiLiveVoiceSession(
    private val apiKey: String,
    private val model: String,
    private val language: String,
    private val diagnostics: DiagLogger,
    private val onInterim: (String) -> Unit,
    private val onStopSignal: () -> Unit,
    private val httpClient: OkHttpClient = sharedClient,
) : VoiceInputController.VoiceSession {
    private val lock = Any()
    private val transcript = SpeechTranscript()
    private val recorder = PcmRecorder(onMaxDurationReached = onStopSignal, onChunk = ::sendAudio)

    /** 停止後の確定を待つ間だけ使う。確定が届けばここが開く。 */
    private val finalized = CountDownLatch(1)

    private var socket: WebSocket? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private var closed = false
    private var stopping = false
    private var abortMessage: String? = null

    override fun start() {
        synchronized(lock) { check(!closed) { "Session already closed" } }
        val request = Request.Builder()
            .url(GeminiLiveProtocol.ENDPOINT)
            .header(GeminiLiveProtocol.API_KEY_HEADER, apiKey)
            .build()
        val opened = httpClient.newWebSocket(request, Listener())
        synchronized(lock) { socket = opened }
        // 接続完了は待たない。setup は先に積んでおけばハンドシェイク後に流れる。
        opened.send(GeminiLiveProtocol.setup(model, language))
        try {
            recorder.start()
        } catch (error: Throwable) {
            synchronized(lock) { closed = true; socket = null }
            opened.cancel()
            throw error
        }
    }

    override fun finish(): String {
        synchronized(lock) { stopping = true }
        // マイクを先に止める。以降のチャンクを送らないので activityEnd が最後になる。
        try {
            recorder.stop()
        } catch (error: Exception) {
            diagnostics.warn("gemini", "recorder did not stop cleanly", error)
        }
        synchronized(lock) { socket }?.send(GeminiLiveProtocol.activityEnd())

        // 未確定が残っているときだけ待つ。全部確定済みなら待つ理由がない。
        if (transcript.interimText().isNotEmpty() && !finalized.await(FINALIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            diagnostics.warn("gemini", "no final transcription within ${FINALIZE_TIMEOUT_MS}ms")
        }
        closeSocket()

        return transcript.resolve(synchronized(lock) { abortMessage }, diagnostics, "gemini")
    }

    override fun cancel() {
        val open = synchronized(lock) {
            if (closed) return
            closed = true
            socket.also { socket = null; pending.clear() }
        }
        // recorder.cancel() は録音スレッドの停止を待つ。cancel は非ブロッキングの
        // 約束なので呼び出し側を待たせない。
        Thread({
            runCatching { recorder.cancel() }
            open?.cancel()
        }, "FreeVoiceGeminiClose").start()
    }

    /** 録音スレッドから呼ばれる。buffer は使い回されるので、この中で送り切る。 */
    private fun sendAudio(buffer: ByteArray, count: Int) {
        val message = GeminiLiveProtocol.audioChunk(buffer, count)
        synchronized(lock) {
            if (closed) return
            val open = socket ?: return
            // setup 完了前の音声は捨てられる。順序を崩さないよう溜めておく。
            if (ready) open.send(message) else pending += message
        }
    }

    private fun handle(event: ServerEvent) {
        when (event) {
            is ServerEvent.SetupComplete -> flushPending()
            is ServerEvent.Interim -> emit(transcript.observeInterim(event.text))
            is ServerEvent.Final -> {
                emit(transcript.confirm(event.text))
                if (synchronized(lock) { stopping }) finalized.countDown()
            }
            is ServerEvent.Ignored -> Unit
        }
    }

    /** 送信はロックの内側で行う。録音スレッドと入れ違うと音声の順序が崩れるため。 */
    private fun flushPending() = synchronized(lock) {
        if (closed || ready) return
        val open = socket ?: return
        ready = true
        open.send(GeminiLiveProtocol.activityStart())
        pending.forEach(open::send)
        pending.clear()
    }

    private fun emit(text: String?) {
        if (text == null) return
        synchronized(lock) { if (closed) return }
        onInterim(text)
    }

    private fun closeSocket() {
        val open = synchronized(lock) {
            if (closed) return
            closed = true
            socket.also { socket = null }
        }
        open?.close(NORMAL_CLOSURE, null)
    }

    /** The single stop signal; the controller cannot tell it from a released key. */
    private fun abort(message: String) {
        synchronized(lock) {
            if (closed || stopping || abortMessage != null) return
            abortMessage = message
        }
        onStopSignal()
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handle(GeminiLiveProtocol.parse(text))

        /** サーバーはバイナリフレームでも同じ JSON を返す。 */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(GeminiLiveProtocol.parse(bytes.utf8()))

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (settled()) {
                diagnostics.info("gemini", "closed during stop (normal): $code")
                return
            }
            diagnostics.error("gemini", "connection closed mid-recording: $code $reason")
            abort("音声認識の接続が切れた")
        }

        /**
         * 原因究明のため、どの分岐に入るかに関わらず必ず記録する。認証もここに落ちる。
         * ヘッダは決して文字列化しない（API キーが載っている唯一の場所）。
         */
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code
            if (settled()) {
                diagnostics.info("gemini", "failure after stop: ${status ?: t.javaClass.simpleName}")
                return
            }
            diagnostics.error("gemini", "connection failed (status=${status ?: "none"})", t)
            abort(if (status == UNAUTHORIZED || status == FORBIDDEN) "API キーが拒否された" else "音声認識に接続できなかった")
        }

        private fun settled() = synchronized(lock) { closed || stopping || abortMessage != null }
    }

    private companion object {
        const val FINALIZE_TIMEOUT_MS = 3_000L
        const val NORMAL_CLOSURE = 1_000
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403

        /** スレッドプールを持つので、録音ごとに作らず1つを使い回す。 */
        val sharedClient: OkHttpClient by lazy { OkHttpClient() }
    }
}
