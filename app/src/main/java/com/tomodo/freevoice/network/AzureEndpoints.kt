package com.tomodo.freevoice.network

import java.net.URLEncoder
import java.net.URI

/** Pure URL helpers kept public for unit tests. */
object AzureEndpoints {
    const val DEFAULT_API_VERSION = "2024-10-21"

    fun normalizeOpenAiEndpoint(endpoint: String): String {
        val uri = try { URI(endpoint.trim()) } catch (e: Exception) {
            throw IllegalArgumentException("Invalid Azure endpoint", e)
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "Azure endpoint must be an https URL with a host" }
        val host = uri.host.lowercase()
        val normalizedHost = if (host.endsWith(".services.ai.azure.com")) {
            val resource = host.removeSuffix(".services.ai.azure.com").substringBefore('.')
            require(resource.isNotBlank()) { "Azure AI endpoint has no resource name" }
            "$resource.openai.azure.com"
        } else host
        return "https://$normalizedHost"
    }

    fun transcription(baseUrl: String, deployment: String, apiVersion: String = DEFAULT_API_VERSION): String =
        "${normalizeOpenAiEndpoint(baseUrl)}/openai/deployments/${deployment.pathEncode()}/audio/transcriptions?api-version=${apiVersion.queryEncode()}"

    /** Azure's current v1 chat endpoint uses the model in JSON, not in the path. */
    fun azureChat(baseUrl: String): String = "${normalizeOpenAiEndpoint(baseUrl)}/openai/v1/chat/completions"

    fun openAiChat(): String = "https://api.openai.com/v1/chat/completions"

    fun speech(baseUrl: String, language: String): String =
        "${baseUrl.trim().trimEnd('/')}/speech/recognition/conversation/cognitiveservices/v1?language=${language.queryEncode()}&format=detailed"

    private fun String.pathEncode() = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    private fun String.queryEncode() = URLEncoder.encode(this, "UTF-8")
}
