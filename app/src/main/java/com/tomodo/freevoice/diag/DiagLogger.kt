package com.tomodo.freevoice.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagLogger(context: Context) {
    private val file = File(context.filesDir, "diagnostics.log")
    @Synchronized fun log(message: String, error: Throwable? = null) {
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} $message${error?.let { ": ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()}\n"
        file.appendText(line)
        if (file.length() > MAX_BYTES) file.writeText(file.readText().takeLast(MAX_BYTES.toInt()))
    }
    @Synchronized fun read(): String = if (file.exists()) file.readText() else ""
    @Synchronized fun clear() { file.delete() }
    companion object { private const val MAX_BYTES = 256 * 1024L }
}
