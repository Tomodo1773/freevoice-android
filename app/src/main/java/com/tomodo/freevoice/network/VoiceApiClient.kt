package com.tomodo.freevoice.network

import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.FormatProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

enum class ChatProvider { AZURE, OPENAI }
data class FormatResult(val text: String, val fallback: Boolean, val fallbackReason: String? = null)
data class VoiceApiConfig(
    val azureOpenAiBaseUrl: String = "", val azureOpenAiKey: String = "", val transcriptionDeployment: String = "",
    val chatDeployment: String = "", val chatBaseUrl: String = "", val chatApiKey: String = "",
    val speechEndpoint: String = "", val speechKey: String = "", val speechLanguage: String = "ja-JP",
    val chatProvider: ChatProvider = ChatProvider.AZURE, val reasoningEffort: String = ""
)

fun AppSettings.toVoiceApiConfig() = VoiceApiConfig(
    azureOpenAiBaseUrl = transcriptionEndpoint, azureOpenAiKey = transcriptionApiKey, transcriptionDeployment = transcriptionModel,
    chatDeployment = formatModel, chatBaseUrl = formatEndpoint, chatApiKey = formatApiKey,
    speechEndpoint = speechEndpoint, speechKey = transcriptionApiKey, speechLanguage = speechLanguage,
    chatProvider = if (formatProvider == FormatProvider.AZURE) ChatProvider.AZURE else ChatProvider.OPENAI,
    reasoningEffort = reasoningEffort
)

open class VoiceApiException(message: String, cause: Throwable? = null, val status: Int? = null) : Exception(message, cause)
class VoiceApiCancelledException : VoiceApiException("Cancelled")

/**
 * Blocking client; invoke only on a worker thread.
 * onTrace は null なら計測ごと省く。送信先の詳細はここでは持たない。
 */
class VoiceApiClient(private val config: VoiceApiConfig, private val onTrace: ((LlmSpan) -> Unit)? = null) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeConnection: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); activeConnection?.disconnect() }
    fun resetCancellation() { cancelled.set(false) }

    fun transcribeAzureOpenAi(wav: File): String {
        require(config.azureOpenAiKey.isNotBlank() && config.transcriptionDeployment.isNotBlank()) { "Azure OpenAI transcription settings are missing" }
        return withRetry {
            val boundary = "----FreeVoice${System.nanoTime()}"
            request(AzureEndpoints.transcription(config.azureOpenAiBaseUrl, config.transcriptionDeployment), mapOf("api-key" to config.azureOpenAiKey, "Content-Type" to "multipart/form-data; boundary=$boundary")) { out ->
                DataOutputStream(out).use { data ->
                    data.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n${config.transcriptionDeployment}\r\n")
                    data.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                    wav.inputStream().use { it.copyTo(data) }; data.writeBytes("\r\n--$boundary--\r\n")
                }
            }.let { JSONObject(it).optString("text").trim().also { text -> if (text.isBlank()) throw VoiceApiException("Empty transcription response") } }
        }
    }

    fun transcribeAzureSpeech(wav: File): String {
        require(config.speechKey.isNotBlank() && config.speechEndpoint.isNotBlank()) { "Azure Speech settings are missing" }
        return withRetry {
            request(AzureEndpoints.speech(config.speechEndpoint, config.speechLanguage), mapOf("Ocp-Apim-Subscription-Key" to config.speechKey, "Content-Type" to "audio/wav; codecs=audio/pcm; samplerate=16000")) { out -> wav.inputStream().use { it.copyTo(out) } }
                .let { JSONObject(it).optString("DisplayText").trim().also { text -> if (text.isBlank()) throw VoiceApiException("Empty transcription response") } }
        }
    }

    fun format(original: String, prompt: String, context: String? = null): FormatResult = runFormat("format", original, prompt, context)
    fun distill(previous: String, formatted: String): FormatResult = runFormat("distill", "<これまでの話題>\n$previous\n</これまでの話題>\n<新しい発話>\n$formatted\n</新しい発話>", com.tomodo.freevoice.context.TopicContextStore.DISTILL_SYSTEM_PROMPT)

    private fun runFormat(spanName: String, original: String, prompt: String, context: String? = null): FormatResult {
        if (original.isBlank()) return FormatResult(original, true, "empty original")
        val source = buildString { if (!context.isNullOrBlank()) append("<参考トピック>\n").append(context).append("\n</参考トピック>\n"); append("<校正対象>\n").append(original).append("\n</校正対象>") }
        val messages = listOf(ChatMessage("system", prompt), ChatMessage("user", source))
        val startMs = System.currentTimeMillis()
        try {
            val payload = JSONObject().put("model", config.chatDeployment).put("messages", messages.toJson())
            if (config.reasoningEffort.isNotBlank()) payload.put("reasoning_effort", config.reasoningEffort)
            // 完成した 1 応答だけを返す。試行をまたぐ可変状態を作らないので、
            // リトライしても前の試行のモデル名やトークン数が混ざらない。
            val completion = withRetry {
                val (url, headers) = if (config.chatProvider == ChatProvider.AZURE) AzureEndpoints.azureChat(config.chatBaseUrl) to mapOf("api-key" to config.chatApiKey)
                else AzureEndpoints.openAiChat() to mapOf("Authorization" to "Bearer ${config.chatApiKey}")
                JSONObject(request(url, headers + mapOf("Content-Type" to "application/json")) { it.write(payload.toString().toByteArray()) })
                    .toChatCompletion()
                    .also { if (it.text.isBlank()) throw RetryableException("Empty format response") }
            }
            trace(spanName, messages, startMs, completion)
            return FormatResult(completion.text, false)
        } catch (e: VoiceApiCancelledException) { throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            trace(spanName, messages, startMs, error = reason, errorStatus = statusOf(e))
            return FormatResult(original, true, reason)
        }
    }

    private data class ChatCompletion(val text: String, val model: String?, val inputTokens: Int?, val outputTokens: Int?)

    private fun List<ChatMessage>.toJson(): JSONArray =
        fold(JSONArray()) { array, message -> array.put(JSONObject().put("role", message.role).put("content", message.content)) }

    private fun JSONObject.toChatCompletion(): ChatCompletion = ChatCompletion(
        text = optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty(),
        model = optString("model").takeIf { it.isNotBlank() },
        inputTokens = optJSONObject("usage")?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 },
        outputTokens = optJSONObject("usage")?.optInt("completion_tokens", -1)?.takeIf { it >= 0 },
    )

    /** キャンセル時は呼ばない。応答が無い失敗では completion ごと null になる。 */
    private fun trace(
        spanName: String, messages: List<ChatMessage>, startMs: Long, completion: ChatCompletion? = null,
        error: String? = null, errorStatus: Int? = null,
    ) {
        val sink = onTrace ?: return
        sink(
            LlmSpan(
                spanName = spanName,
                provider = config.chatProvider,
                requestModel = config.chatDeployment,
                responseModel = completion?.model,
                messages = messages,
                completion = completion?.text,
                reasoningEffort = config.reasoningEffort,
                inputTokens = completion?.inputTokens,
                outputTokens = completion?.outputTokens,
                startTimeMs = startMs,
                endTimeMs = System.currentTimeMillis(),
                errorMessage = error,
                errorStatus = errorStatus,
            ),
        )
    }

    private fun statusOf(error: Throwable): Int? = when (error) {
        is VoiceApiException -> error.status
        is RetryableException -> error.status
        else -> null
    }

    private fun <T> withRetry(block: () -> T): T {
        var last: RetryableException? = null
        for (delay in longArrayOf(0, 1_000, 3_000)) {
            checkCancelled(); sleepCancellable(delay)
            try { return block() } catch (e: RetryableException) { last = e }
        }
        throw last ?: VoiceApiException("Request failed")
    }

    private fun request(url: String, headers: Map<String, String>, write: (java.io.OutputStream) -> Unit): String {
        checkCancelled()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true; headers.forEach { (k, v) -> setRequestProperty(k, v) } }
        activeConnection = connection
        try {
            connection.outputStream.use(write); checkCancelled()
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                if (code in setOf(429, 500, 502, 503)) throw RetryableException("HTTP $code", code)
                throw VoiceApiException("HTTP $code", status = code)
            }
            return text
        } catch (e: java.io.IOException) { if (cancelled.get()) throw VoiceApiCancelledException(); throw e
        } finally { activeConnection = null; connection.disconnect() }
    }
    private fun checkCancelled() { if (cancelled.get()) throw VoiceApiCancelledException() }
    private fun sleepCancellable(delayMs: Long) {
        var remaining = delayMs
        while (remaining > 0) {
            checkCancelled()
            val chunk = minOf(remaining, 100L)
            try { Thread.sleep(chunk) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw VoiceApiCancelledException()
            }
            remaining -= chunk
        }
    }
    private class RetryableException(message: String, val status: Int? = null) : Exception(message)
}
