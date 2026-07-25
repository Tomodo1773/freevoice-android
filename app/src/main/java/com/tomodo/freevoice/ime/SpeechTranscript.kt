package com.tomodo.freevoice.ime

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
