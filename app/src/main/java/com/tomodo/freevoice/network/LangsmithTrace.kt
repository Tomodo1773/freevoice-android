package com.tomodo.freevoice.network

import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.LangsmithRegion
import com.tomodo.freevoice.data.TranscriptionProvider
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class LangsmithConfig(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val project: String = AppSettings.DEFAULT_LANGSMITH_PROJECT,
    val region: LangsmithRegion = LangsmithRegion.US,
    val includeContent: Boolean = true,
) {
    /** 有効なのに送れないとき、足りない設定の名前。揃っていれば null。 */
    val missing: String? get() = when {
        apiKey.isBlank() -> "API キー"
        project.isBlank() -> "プロジェクト名"
        else -> null
    }

    /** 送信に必要な値が揃っているとき true。 */
    val active: Boolean get() = enabled && missing == null

    val endpoint: String get() = when (region) {
        LangsmithRegion.US -> "https://api.smith.langchain.com/otel/v1/traces"
        LangsmithRegion.EU -> "https://eu.api.smith.langchain.com/otel/v1/traces"
    }
}

fun AppSettings.toLangsmithConfig() = LangsmithConfig(
    enabled = langsmithEnabled, apiKey = langsmithApiKey, project = langsmithProject,
    region = langsmithRegion, includeContent = langsmithIncludeContent,
)

data class ChatMessage(val role: String, val content: String)

/** LLM 呼び出し 1 回分。時刻は System.currentTimeMillis() を想定する。 */
data class LlmSpan(
    val spanName: String,
    /** gen_ai.system の値。文字起こしと整形でプロバイダーの型が違うので文字列で持つ。 */
    val system: String,
    val requestModel: String,
    val responseModel: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val completion: String? = null,
    val reasoningEffort: String = "",
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val errorMessage: String? = null,
    val errorStatus: Int? = null,
)

/** 録音 1 回ぶんの親スパン。子の LLM 呼び出しをまとめる。 */
data class ChainSpan(
    /** 文字起こしの生テキスト。 */
    val input: String,
    /** 実際に入力したテキスト。 */
    val output: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

val FormatProvider.genAiSystem: String get() = when (this) {
    FormatProvider.AZURE -> "azure.openai"
    FormatProvider.OPENAI -> "openai"
    FormatProvider.GEMINI -> "gcp.gemini"
}

val TranscriptionProvider.genAiSystem: String get() = when (this) {
    TranscriptionProvider.AZURE_OPENAI -> "azure.openai"
    TranscriptionProvider.AZURE_SPEECH -> "azure.ai.speech"
    TranscriptionProvider.GEMINI_LIVE -> "gcp.gemini"
}

/**
 * 1 回の LLM 呼び出しだけで完結するトレース。話題蒸留のように録音の外で走るものが使う。
 * OpenLLMetry の gen_ai.* semantic convention に準拠。
 */
fun buildLlmSpanPayload(
    span: LlmSpan,
    project: String,
    includeContent: Boolean,
    traceId: String,
    spanId: String,
): JSONObject = tracePayload(project, JSONArray().put(llmSpanJson(span, includeContent, traceId, spanId, null)))

/**
 * 録音 1 回ぶん。root と子を 1 リクエストにまとめる。
 * LangSmith は親が結局送られてこない子スパンを破棄するので、分割して送らない。
 */
fun buildRecordingPayload(
    root: ChainSpan,
    children: List<LlmSpan>,
    project: String,
    includeContent: Boolean,
    traceId: String,
    rootSpanId: String,
    childSpanIds: List<String>,
): JSONObject {
    val spans = JSONArray().put(chainSpanJson(root, includeContent, traceId, rootSpanId))
    children.zip(childSpanIds).forEach { (child, spanId) ->
        spans.put(llmSpanJson(child, includeContent, traceId, spanId, rootSpanId))
    }
    return tracePayload(project, spans)
}

private fun llmSpanJson(
    span: LlmSpan,
    includeContent: Boolean,
    traceId: String,
    spanId: String,
    parentSpanId: String?,
): JSONObject {
    val attributes = JSONArray()
        .put(strAttr("langsmith.span.kind", "llm"))
        .put(strAttr("gen_ai.system", span.system))
        .put(strAttr("gen_ai.operation.name", "chat"))
        .put(strAttr("gen_ai.request.model", span.requestModel))
        .put(strAttr("gen_ai.request.reasoning_effort", span.reasoningEffort))
        .put(strAttr("freevoice.operation", span.spanName))
    span.responseModel?.let { attributes.put(strAttr("gen_ai.response.model", it)) }
    span.inputTokens?.let { attributes.put(intAttr("gen_ai.usage.input_tokens", it)) }
    span.outputTokens?.let { attributes.put(intAttr("gen_ai.usage.output_tokens", it)) }

    if (includeContent) {
        span.messages.forEachIndexed { index, message ->
            attributes.put(strAttr("gen_ai.prompt.$index.role", message.role))
            attributes.put(strAttr("gen_ai.prompt.$index.content", message.content))
        }
        span.completion?.let {
            attributes.put(strAttr("gen_ai.completion.0.role", "assistant"))
            attributes.put(strAttr("gen_ai.completion.0.content", it))
        }
    }

    val status = span.errorMessage
        ?.let { JSONObject().put("code", 2).put("message", it) }
        ?: JSONObject().put("code", 1)
    val events = JSONArray()
    span.errorMessage?.let { message ->
        events.put(
            JSONObject()
                .put("name", "exception")
                .put("timeUnixNano", unixNano(span.endTimeMs))
                .put(
                    "attributes",
                    JSONArray()
                        .put(strAttr("exception.type", span.errorStatus?.let { "HTTP $it" } ?: "Error"))
                        .put(strAttr("exception.message", message)),
                ),
        )
    }

    return spanJson(traceId, spanId, parentSpanId, span.spanName, SPAN_KIND_CLIENT, span.startTimeMs, span.endTimeMs)
        .put("attributes", attributes)
        .put("status", status)
        .put("events", events)
}

private fun chainSpanJson(
    span: ChainSpan,
    includeContent: Boolean,
    traceId: String,
    spanId: String,
): JSONObject {
    val attributes = JSONArray().put(strAttr("langsmith.span.kind", "chain"))
    if (includeContent) {
        // トレース一覧とヘッダの Input/Output は root のものしか見ない。
        // ここを落とすと、子が埋まっていても一覧が全部空に見える。
        attributes.put(strAttr("input.value", span.input))
        attributes.put(strAttr("output.value", span.output))
    }
    return spanJson(traceId, spanId, null, "recording", SPAN_KIND_INTERNAL, span.startTimeMs, span.endTimeMs)
        .put("attributes", attributes)
        .put("status", JSONObject().put("code", 1))
        .put("events", JSONArray())
}

private fun spanJson(
    traceId: String,
    spanId: String,
    parentSpanId: String?,
    name: String,
    kind: Int,
    startTimeMs: Long,
    endTimeMs: Long,
): JSONObject = JSONObject()
    .put("traceId", traceId)
    .put("spanId", spanId)
    .apply { parentSpanId?.let { put("parentSpanId", it) } }
    .put("name", name)
    .put("kind", kind)
    .put("startTimeUnixNano", unixNano(startTimeMs))
    .put("endTimeUnixNano", unixNano(endTimeMs))

private fun tracePayload(project: String, spans: JSONArray): JSONObject = JSONObject().put(
    "resourceSpans",
    JSONArray().put(
        JSONObject()
            .put(
                "resource",
                JSONObject().put(
                    "attributes",
                    JSONArray()
                        .put(strAttr("service.name", "freevoice"))
                        .put(strAttr("langsmith.project", project)),
                ),
            )
            .put(
                "scopeSpans",
                JSONArray().put(
                    JSONObject()
                        .put("scope", JSONObject().put("name", "freevoice"))
                        .put("spans", spans),
                ),
            ),
    ),
)

private const val SPAN_KIND_INTERNAL = 1
private const val SPAN_KIND_CLIENT = 3

private fun strAttr(key: String, value: String) =
    JSONObject().put("key", key).put("value", JSONObject().put("stringValue", value))

/** OTLP/HTTP JSON では int64 を文字列でエンコードする。 */
private fun intAttr(key: String, value: Int) =
    JSONObject().put("key", key).put("value", JSONObject().put("intValue", value.toString()))

private fun unixNano(millis: Long): String = (millis * 1_000_000L).toString()

/**
 * 組み立ても POST も別スレッドで行う。録音ぶんの送信は入力確定直後、
 * つまりメインスレッドから来るので、JSON 化までメインに載せない。
 * 送信失敗は握り潰し、音声入力本体には一切影響させない。
 * 音声入力ジョブより長生きするので、所有者はアプリ側の 1 インスタンスに限る。
 */
class LangsmithTracer(
    private val onFailure: (String) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "langsmith-trace").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val random = SecureRandom()

    /** 呼び出し側が config.active を保証する。判定は configFor に寄せてある。 */
    fun send(config: LangsmithConfig, span: LlmSpan) = submit(config) {
        buildLlmSpanPayload(span, config.project, config.includeContent, traceId(), spanId())
    }

    /** 録音 1 回ぶんを 1 リクエストで送る。root ごと送るので孤児スパンが出ない。 */
    fun send(config: LangsmithConfig, root: ChainSpan, children: List<LlmSpan>) = submit(config) {
        buildRecordingPayload(
            root, children, config.project, config.includeContent,
            traceId(), spanId(), children.map { spanId() },
        )
    }

    internal fun report(message: String) = onFailure(message)

    override fun close() {
        executor.shutdownNow()
    }

    private fun submit(config: LangsmithConfig, build: () -> JSONObject) {
        runCatching {
            executor.execute {
                val payload = runCatching { build().toString() }.getOrElse {
                    onFailure("トレースを組み立てられなかった: ${it.javaClass.simpleName}")
                    return@execute
                }
                post(config, payload)
            }
        }
    }

    private fun traceId(): String = randomHex(random, TRACE_ID_BYTES)

    private fun spanId(): String = randomHex(random, SPAN_ID_BYTES)

    private fun post(config: LangsmithConfig, payload: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", config.apiKey)
                setRequestProperty("Langsmith-Project", config.project)
            }
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                // 本文が無いと、キー違い・プロジェクト名不正・ペイロード不正を切り分けられない。
                val body = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                onFailure("トレース送信に失敗した: HTTP $code ${body.take(BODY_SNIPPET_CHARS)}".trim())
            }
        } catch (error: Exception) {
            onFailure("トレース送信に失敗した: ${error.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val BODY_SNIPPET_CHARS = 200
        const val TRACE_ID_BYTES = 16
        const val SPAN_ID_BYTES = 8
    }
}

/** OTLP の traceId は 16 バイト、spanId は 8 バイトの hex。壊れると全スパンが弾かれる。 */
internal fun randomHex(random: Random, bytes: Int): String =
    ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

/**
 * 送るかどうかの判定はここだけが持つ。設定が揃っていなければ null を返し、
 * 呼び出し側でスパン組み立てごと省く。
 * 「有効にしたのに送られない」ときは黙って捨てず、足りない設定を報告する。
 */
fun LangsmithTracer?.configFor(settings: AppSettings): LangsmithConfig? {
    val tracer = this ?: return null
    val config = settings.toLangsmithConfig()
    if (!config.enabled) return null
    config.missing?.let {
        tracer.report("トレースを送れない: $it が未入力")
        return null
    }
    return config
}

/** 単発のトレースを送る口。録音ぶんは RecordingTrace が溜めてから送る。 */
fun LangsmithTracer?.sinkFor(settings: AppSettings): ((LlmSpan) -> Unit)? {
    val tracer = this ?: return null
    val config = configFor(settings) ?: return null
    return { span -> tracer.send(config, span) }
}
