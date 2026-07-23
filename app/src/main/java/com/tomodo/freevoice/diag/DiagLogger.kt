package com.tomodo.freevoice.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagLogger internal constructor(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
    private val now: () -> Date = { Date() },
) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    init {
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "maxBytes must fit in a byte array" }
    }

    fun log(message: String, error: Throwable? = null) = synchronized(FILE_LOCK) {
        val detail = error?.let {
            ": ${it.javaClass.simpleName}${it.message?.let { message -> ": ${sanitize(message)}" }.orEmpty()}"
        }.orEmpty()
        val line = "${SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).format(now())} ${sanitize(message)}$detail\n"
        file.appendText(line, Charsets.UTF_8)
        trimToLimit()
    }

    fun read(): String = synchronized(FILE_LOCK) {
        if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    fun readLatest(maxLines: Int): String = synchronized(FILE_LOCK) {
        require(maxLines > 0) { "maxLines must be positive" }
        if (file.exists()) file.readLines(Charsets.UTF_8).takeLast(maxLines).joinToString("\n") else ""
    }

    fun clear() = synchronized(FILE_LOCK) { file.delete() }

    private fun trimToLimit() {
        if (file.length() <= maxBytes) return
        val bytes = file.readBytes()
        var start = bytes.size - maxBytes.toInt()
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        if (start < bytes.size) start++
        file.writeBytes(bytes.copyOfRange(start, bytes.size))
    }

    private fun sanitize(value: String): String = SECRET_PATTERNS
        .fold(value.replace(CONTROL_CHARACTERS, " ").take(MAX_COMPONENT_CHARS)) { text, pattern ->
            pattern.replace(text) { match -> "${match.groupValues[1]}<redacted>" }
        }

    companion object {
        const val MAX_BYTES = 256 * 1024L
        private const val FILE_NAME = "diagnostics.log"
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
        private const val MAX_COMPONENT_CHARS = 2_000
        private val FILE_LOCK = Any()
        private val CONTROL_CHARACTERS = Regex("[\\r\\n\\t]+")
        private val SECRET_PATTERNS = listOf(
            Regex("(?i)(\\bBearer\\s+)[^\\s,;]+"),
            Regex("(?i)((?:api[-_ ]?key|ocp-apim-subscription-key|authorization)\\s*[:=]\\s*)[^\\s,;]+"),
        )
    }
}
