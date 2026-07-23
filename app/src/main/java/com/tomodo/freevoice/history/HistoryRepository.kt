package com.tomodo.freevoice.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(val timestamp: Long, val text: String, val success: Boolean, val error: String? = null)
class HistoryRepository(context: Context) {
    private val file = File(context.filesDir, "history.json")
    @Synchronized fun list(): List<HistoryEntry> = read().sortedByDescending { it.timestamp }
    @Synchronized fun add(text: String, success: Boolean, error: String? = null) {
        val all = (read() + HistoryEntry(System.currentTimeMillis(), text, success, error)).takeLast(100)
        val json = JSONArray(); all.forEach { json.put(JSONObject().put("timestamp", it.timestamp).put("text", it.text).put("success", it.success).put("error", it.error)) }
        file.writeText(json.toString())
    }
    @Synchronized fun clear() { file.delete() }
    private fun read(): List<HistoryEntry> = runCatching {
        val array = JSONArray(file.takeIf { it.exists() }?.readText() ?: "[]")
        (0 until array.length()).map { i -> array.getJSONObject(i).let { HistoryEntry(it.optLong("timestamp"), it.optString("text"), it.optBoolean("success"), it.optString("error").takeIf(String::isNotBlank)) } }
    }.getOrDefault(emptyList())
}
