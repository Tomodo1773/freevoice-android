package com.tomodo.freevoice.context

/** Package-scoped short-term context. All methods are synchronized for IME worker safety. */
class TopicContextStore(private val now: () -> Long = System::currentTimeMillis) {
    data class Entry(val text: String, val updatedAt: Long)
    companion object { const val TTL_MS = 30 * 60 * 1000L; const val MAX_ENTRIES = 20
        const val DISTILL_SYSTEM_PROMPT = """あなたは音声入力の誤変換補正に使う「話題メモ」を管理する。
旧メモと新しい発話から、メモを毎回ゼロから書き直す（追記ではない）。

目的: 後続の音声文字起こしで同音異義語・専門用語の誤変換を防ぐヒントを提供する。

ルール:
- 現在の話題を1〜2文でまとめる。3文以上は禁止
- 話題が変わったら旧メモの内容は捨てる。履歴を残さない
- 誤変換補正に役立つ固有名詞・専門用語は正確な表記で保持する
- 経緯・詳細・結論は不要。「何の領域の話か」だけ書く
- メモ本文のみ出力する"""
    }
    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }
    private val inFlight = mutableSetOf<String>()
    @Synchronized fun get(packageName: String): String? = entries[packageName]?.takeIf { now() - it.updatedAt <= TTL_MS }?.text
    @Synchronized fun put(packageName: String, text: String) { if (text.isNotBlank()) entries[packageName] = Entry(text, now()) }
    @Synchronized fun beginDistill(packageName: String): Boolean = inFlight.add(packageName)
    @Synchronized fun finishDistill(packageName: String) { inFlight.remove(packageName) }
    @Synchronized fun clear() { entries.clear(); inFlight.clear() }
}
