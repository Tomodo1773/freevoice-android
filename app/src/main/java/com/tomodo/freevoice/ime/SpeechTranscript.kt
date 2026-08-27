package com.tomodo.freevoice.ime

import com.tomodo.freevoice.diag.DiagLogger

/**
 * Accumulates streamed recognition text.  Confirmed and in-flight text are kept
 * apart so a reconnect can promote the in-flight part first: whatever the service
 * had not confirmed yet would otherwise be lost with the closed socket.
 *
 * Every method is safe to call from the SDK's own threads.
 */
internal class SpeechTranscript {
    private val lock = Any()
    private val confirmed = mutableListOf<String>()
    private var interim = ""

    /** Returns the new full text, or null when nothing changed. */
    fun confirm(text: String): String? = synchronized(lock) {
        if (text.isEmpty()) return null
        confirmed += text
        interim = ""
        joinedLocked()
    }

    /** Returns the new full text, or null when nothing changed. */
    fun observeInterim(text: String): String? = synchronized(lock) {
        if (text.isEmpty() || text == interim) return null
        interim = text
        joinedLocked()
    }

    /** Moves in-flight text into the confirmed part before dropping the connection. */
    fun promoteInterim() = synchronized(lock) {
        if (interim.isNotEmpty()) {
            confirmed += interim
            interim = ""
        }
    }

    fun confirmedText(): String = synchronized(lock) { confirmed.joinToString("") }

    fun interimText(): String = synchronized(lock) { interim }

    private fun joinedLocked(): String = confirmed.joinToString("") + interim
}

/**
 * 停止時に何を返すかの規則。確定 → 暫定 → 中断メッセージの順に拾う。
 *
 * 停止と確定配信のあいだにはレースがあり、発話中に止めると確定が届かない端末がある。
 * 黙って空を返さず暫定を採用するのはそのため。中断していても確定が残っていれば、
 * 失敗にせずそれを返す。プロバイダーが違っても判断は同じなので、ここだけが持つ。
 */
internal fun SpeechTranscript.resolve(
    abortMessage: String?,
    diagnostics: DiagLogger,
    source: String,
): String {
    val confirmed = confirmedText()
    val interim = interimText()
    return when {
        confirmed.isNotEmpty() -> {
            if (abortMessage != null) diagnostics.warn(source, "salvaged ${confirmed.length} chars after abort")
            confirmed
        }
        interim.isNotEmpty() -> {
            diagnostics.warn(source, "interim fallback used (${interim.length} chars)")
            interim
        }
        abortMessage != null -> throw UserVisibleException(abortMessage)
        else -> ""
    }
}
