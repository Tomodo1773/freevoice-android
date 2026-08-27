package com.tomodo.freevoice.network

import com.tomodo.freevoice.audio.PcmRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Gemini Live API のメッセージ組み立てと解釈だけを持つ。ソケットも設定も知らないので、
 * JVM テストでプロトコルの形をそのまま検証できる。
 */
internal object GeminiLiveProtocol {
    const val ENDPOINT = "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /**
     * 公式 SDK と同じヘッダ認証。ドキュメントの `?key=` はハンドシェイクにヘッダを
     * 付けられないブラウザ向けの書き方で、ネイティブクライアントには不要。
     * キーが URL に載らないので、接続先を診断ログに書いても秘密は漏れない。
     */
    const val API_KEY_HEADER = "x-goog-api-key"

    /** 録音側と同じ値でなければサーバーが音声を読み違える。 */
    private const val AUDIO_MIME = "audio/pcm;rate=${PcmRecorder.SAMPLE_RATE}"

    /** 認識結果以外のサーバーメッセージは Ignored に潰す。 */
    sealed interface ServerEvent {
        /** setup が受理された合図。これより前に音声を送っても捨てられる。 */
        data object SetupComplete : ServerEvent
        data class Interim(val text: String) : ServerEvent
        data class Final(val text: String) : ServerEvent
        data object Ignored : ServerEvent
    }

    /**
     * 接続直後に1回だけ送る。自動発話検知は切る。押している間だけ喋る UI なので、
     * 途中の沈黙でターンを区切られると1回の入力が分断されるため。
     */
    fun setup(model: String, languageCode: String): String = JSONObject()
        .put(
            "setup",
            JSONObject()
                .put("model", qualify(model))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
                .put(
                    "inputAudioTranscription",
                    JSONObject()
                        .put("languageCodes", JSONArray().put(languageCode))
                        // フィラー除去と整形は整形モデルの担当。ここでは削らない。
                        .put("mode", "VERBATIM"),
                )
                .put(
                    "realtimeInputConfig",
                    JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true)),
                ),
        )
        .toString()

    /** 発話の開始。自動発話検知を切った以上、これを送らないと音声が無視される。 */
    fun activityStart(): String = realtimeInput(JSONObject().put("activityStart", JSONObject()))

    /** 発話の終了。サーバーはこれを見て最後の確定を返す。 */
    fun activityEnd(): String = realtimeInput(JSONObject().put("activityEnd", JSONObject()))

    /** [length] バイトだけを送る。[pcm] は録音側が使い回すので、ここで切り出す。 */
    fun audioChunk(pcm: ByteArray, length: Int): String = realtimeInput(
        JSONObject().put(
            "audio",
            JSONObject()
                .put("mimeType", AUDIO_MIME)
                .put("data", Base64.getEncoder().encodeToString(if (length == pcm.size) pcm else pcm.copyOf(length))),
        ),
    )

    fun parse(message: String): ServerEvent {
        val root = runCatching { JSONObject(message) }.getOrNull() ?: return ServerEvent.Ignored
        if (root.has("setupComplete")) return ServerEvent.SetupComplete
        val content = root.optJSONObject("serverContent") ?: return ServerEvent.Ignored
        // 確定を先に見る。同じメッセージに両方載っていたら確定のほうが新しい。
        text(content, "inputTranscription")?.let { return ServerEvent.Final(it) }
        text(content, "interimInputTranscription")?.let { return ServerEvent.Interim(it) }
        return ServerEvent.Ignored
    }

    private fun realtimeInput(body: JSONObject): String =
        JSONObject().put("realtimeInput", body).toString()

    private fun text(content: JSONObject, field: String): String? =
        content.optJSONObject(field)?.optString("text")?.takeIf { it.isNotEmpty() }

    private fun qualify(model: String): String =
        if (model.startsWith(MODEL_PREFIX)) model else MODEL_PREFIX + model

    private const val MODEL_PREFIX = "models/"
}
