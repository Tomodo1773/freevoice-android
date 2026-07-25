package com.tomodo.freevoice.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API キーだけを Android Keystore の AES/GCM 鍵で暗号化して保存する。 */
class SecureSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        transcriptionProvider = enum(P_TRANSCRIPTION_PROVIDER, TranscriptionProvider.AZURE_OPENAI),
        transcriptionEndpoint = plain(P_TRANSCRIPTION_ENDPOINT),
        transcriptionApiKey = secret(P_TRANSCRIPTION_API_KEY),
        transcriptionModel = plain(P_TRANSCRIPTION_MODEL, "gpt-4o-transcribe"),
        speechEndpoint = plain(P_SPEECH_ENDPOINT),
        speechLanguage = plain(P_SPEECH_LANGUAGE, "ja-JP"),
        formatEnabled = preferences.getBoolean(P_FORMAT_ENABLED, true),
        formatProvider = enum(P_FORMAT_PROVIDER, FormatProvider.AZURE),
        formatEndpoint = plain(P_FORMAT_ENDPOINT),
        formatApiKey = secret(P_FORMAT_API_KEY),
        formatModel = plain(P_FORMAT_MODEL, AppSettings.DEFAULT_FORMAT_MODEL),
        postprocessPrompt = plain(P_POSTPROCESS_PROMPT, AppSettings.DEFAULT_POSTPROCESS_PROMPT),
        reasoningEffort = plain(P_REASONING_EFFORT, AppSettings.DEFAULT_REASONING_EFFORT),
        contextAwareFormatting = preferences.getBoolean(P_CONTEXT_AWARE, true),
        langsmithEnabled = preferences.getBoolean(P_LANGSMITH_ENABLED, false),
        langsmithApiKey = secret(P_LANGSMITH_API_KEY),
        langsmithProject = plain(P_LANGSMITH_PROJECT, AppSettings.DEFAULT_LANGSMITH_PROJECT),
        langsmithRegion = enum(P_LANGSMITH_REGION, LangsmithRegion.US),
        langsmithIncludeContent = preferences.getBoolean(P_LANGSMITH_INCLUDE_CONTENT, true),
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString(P_TRANSCRIPTION_PROVIDER, settings.transcriptionProvider.name)
            .putString(P_TRANSCRIPTION_ENDPOINT, settings.transcriptionEndpoint.trim())
            .putString(P_TRANSCRIPTION_MODEL, settings.transcriptionModel.trim())
            .putString(P_SPEECH_ENDPOINT, settings.speechEndpoint.trim())
            .putString(P_SPEECH_LANGUAGE, settings.speechLanguage.trim())
            .putBoolean(P_FORMAT_ENABLED, settings.formatEnabled)
            .putString(P_FORMAT_PROVIDER, settings.formatProvider.name)
            .putString(P_FORMAT_ENDPOINT, settings.formatEndpoint.trim())
            .putString(P_FORMAT_MODEL, settings.formatModel.trim())
            .putString(P_POSTPROCESS_PROMPT, settings.postprocessPrompt)
            .putString(P_REASONING_EFFORT, settings.reasoningEffort.trim())
            .putBoolean(P_CONTEXT_AWARE, settings.contextAwareFormatting)
            .putBoolean(P_LANGSMITH_ENABLED, settings.langsmithEnabled)
            .putString(P_LANGSMITH_PROJECT, settings.langsmithProject.trim())
            .putString(P_LANGSMITH_REGION, settings.langsmithRegion.name)
            .putBoolean(P_LANGSMITH_INCLUDE_CONTENT, settings.langsmithIncludeContent)
            .putString(P_TRANSCRIPTION_API_KEY, encrypt(settings.transcriptionApiKey))
            .putString(P_FORMAT_API_KEY, encrypt(settings.formatApiKey))
            .putString(P_LANGSMITH_API_KEY, encrypt(settings.langsmithApiKey))
            .apply()
    }

    private fun plain(key: String, fallback: String = "") = preferences.getString(key, fallback).orEmpty()
    private inline fun <reified T : Enum<T>> enum(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(plain(key)) }.getOrDefault(fallback)

    private fun secret(key: String): String = preferences.getString(key, null)?.let(::decrypt).orEmpty()

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String = runCatching {
        if (value.isEmpty()) return ""
        val parts = value.split(":", limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }.getOrDefault("")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val PREFERENCES = "freevoice_settings"
        const val KEY_ALIAS = "freevoice_settings_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val P_TRANSCRIPTION_PROVIDER = "transcription_provider"
        const val P_TRANSCRIPTION_ENDPOINT = "transcription_endpoint"
        const val P_TRANSCRIPTION_API_KEY = "transcription_api_key"
        const val P_TRANSCRIPTION_MODEL = "transcription_model"
        const val P_SPEECH_ENDPOINT = "speech_endpoint"
        const val P_SPEECH_LANGUAGE = "speech_language"
        const val P_FORMAT_ENABLED = "format_enabled"
        const val P_FORMAT_PROVIDER = "format_provider"
        const val P_FORMAT_ENDPOINT = "format_endpoint"
        const val P_FORMAT_API_KEY = "format_api_key"
        const val P_FORMAT_MODEL = "format_model"
        const val P_POSTPROCESS_PROMPT = "postprocess_prompt"
        const val P_REASONING_EFFORT = "reasoning_effort"
        const val P_CONTEXT_AWARE = "context_aware"
        const val P_LANGSMITH_ENABLED = "langsmith_enabled"
        const val P_LANGSMITH_API_KEY = "langsmith_api_key"
        const val P_LANGSMITH_PROJECT = "langsmith_project"
        const val P_LANGSMITH_REGION = "langsmith_region"
        const val P_LANGSMITH_INCLUDE_CONTENT = "langsmith_include_content"
    }
}
