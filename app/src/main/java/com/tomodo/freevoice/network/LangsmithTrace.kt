package com.tomodo.freevoice.network

import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.LangsmithRegion
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
    val provider: ChatProvider,
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

/**
 * 1 回の LLM 呼び出しを OTLP/HTTP JSON の resourceSpans 形式へ組み立てる。
 * OpenLLMetry の gen_ai.* semantic convention に準拠。
 */
fun buildLlmSpanPayload(
    span: LlmSpan,
    project: String,
    includeContent: Boolean,
    traceId: String,
    spanId: String,
): JSONObject {
    val attributes = JSONArray()
        .put(strAttr("gen_ai.system", if (span.provider == ChatProvider.OPENAI) "openai" else "azure.openai"))
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

    return JSONObject().put(
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
                            .put(
                                "spans",
                                JSONArray().put(
                                    JSONObject()
                                        .put("traceId", traceId)
                                        .put("spanId", spanId)
                                        .put("name", span.spanName)
                                        .put("kind", 3) // SPAN_KIND_CLIENT
                                        .put("startTimeUnixNano", unixNano(span.startTimeMs))
                                        .put("endTimeUnixNano", unixNano(span.endTimeMs))
                                        .put("attributes", attributes)
                                        .put("status", status)
                                        .put("events", events),
                                ),
                            ),
                    ),
                ),
        ),
    )
}

private fun strAttr(key: String, value: String) =
    JSONObject().put("key", key).put("value", JSONObject().put("stringValue", value))

/** OTLP/HTTP JSON では int64 を文字列でエンコードする。 */
private fun intAttr(key: String, value: Int) =
    JSONObject().put("key", key).put("value", JSONObject().put("intValue", value.toString()))

private fun unixNano(millis: Long): String = (millis * 1_000_000L).toString()

/**
 * ペイロードは呼び出しスレッドで組み立て、POST だけ別スレッドへ流す。
 * 先に文字列にすることでプロンプトへの参照をすぐ手放せる。
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

    /** 呼び出し側が config.active を保証する。判定は sinkFor に寄せてある。 */
    fun send(config: LangsmithConfig, span: LlmSpan) {
        val payload = runCatching {
            buildLlmSpanPayload(span, config.project, config.includeContent, randomHex(random, TRACE_ID_BYTES), randomHex(random, SPAN_ID_BYTES)).toString()
        }.getOrElse {
            onFailure("トレースを組み立てられなかった: ${it.javaClass.simpleName}")
            return
        }
        runCatching { executor.execute { post(config, payload) } }
    }

    internal fun report(message: String) = onFailure(message)

    override fun close() {
        executor.shutdownNow()
    }

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
 * VoiceApiClient 側でスパン組み立てごと省く。
 * 「有効にしたのに送られない」ときは黙って捨てず、足りない設定を報告する。
 */
fun LangsmithTracer?.sinkFor(settings: AppSettings): ((LlmSpan) -> Unit)? {
    val tracer = this ?: return null
    val config = settings.toLangsmithConfig()
    if (!config.enabled) return null
    config.missing?.let {
        tracer.report("トレースを送れない: $it が未入力")
        return null
    }
    return { span -> tracer.send(config, span) }
}
