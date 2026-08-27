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

    fun load(): AppSettings {
        val formatProvider = enum(P_FORMAT_PROVIDER, FormatProvider.AZURE)
        val transcriptionProvider = enum(P_TRANSCRIPTION_PROVIDER, TranscriptionProvider.AZURE_OPENAI)
        return AppSettings(
            transcriptionProvider = transcriptionProvider,
            transcriptionProfiles = loadTranscriptionProfiles(transcriptionProvider),
            speechLanguage = plain(P_SPEECH_LANGUAGE, "ja-JP"),
            formatEnabled = preferences.getBoolean(P_FORMAT_ENABLED, true),
            formatProvider = formatProvider,
            formatProfiles = loadFormatProfiles(formatProvider),
            postprocessPrompt = plain(P_POSTPROCESS_PROMPT, AppSettings.DEFAULT_POSTPROCESS_PROMPT),
            reasoningEffort = plain(P_REASONING_EFFORT, AppSettings.DEFAULT_REASONING_EFFORT),
            contextAwareFormatting = preferences.getBoolean(P_CONTEXT_AWARE, true),
            langsmithEnabled = preferences.getBoolean(P_LANGSMITH_ENABLED, false),
            langsmithApiKey = secret(P_LANGSMITH_API_KEY),
            langsmithProject = plain(P_LANGSMITH_PROJECT, AppSettings.DEFAULT_LANGSMITH_PROJECT),
            langsmithRegion = enum(P_LANGSMITH_REGION, LangsmithRegion.US),
            langsmithIncludeContent = preferences.getBoolean(P_LANGSMITH_INCLUDE_CONTENT, true),
        )
    }

    fun save(settings: AppSettings) {
        val profiles = settings.formatProfiles
        val transcription = settings.transcriptionProfiles
        preferences.edit()
            .putString(P_TRANSCRIPTION_PROVIDER, settings.transcriptionProvider.name)
            .putString(P_SPEECH_LANGUAGE, settings.speechLanguage.trim())
            .putString(P_TRANSCRIPTION_AZURE_OPENAI_ENDPOINT, transcription.azureOpenAi.endpoint.trim())
            .putString(P_TRANSCRIPTION_AZURE_OPENAI_MODEL, transcription.azureOpenAi.model.trim())
            .putString(P_TRANSCRIPTION_AZURE_SPEECH_ENDPOINT, transcription.azureSpeech.endpoint.trim())
            .putString(P_TRANSCRIPTION_GEMINI_MODEL, transcription.geminiLive.model.trim())
            .putBoolean(P_FORMAT_ENABLED, settings.formatEnabled)
            .putString(P_FORMAT_PROVIDER, settings.formatProvider.name)
            .putString(P_FORMAT_AZURE_ENDPOINT, profiles.azure.endpoint.trim())
            .putString(P_FORMAT_AZURE_MODEL, profiles.azure.model.trim())
            .putString(P_FORMAT_OPENAI_MODEL, profiles.openAi.model.trim())
            .putString(P_FORMAT_GEMINI_MODEL, profiles.gemini.model.trim())
            .putString(P_POSTPROCESS_PROMPT, settings.postprocessPrompt)
            .putString(P_REASONING_EFFORT, settings.reasoningEffort.trim())
            .putBoolean(P_CONTEXT_AWARE, settings.contextAwareFormatting)
            .putBoolean(P_LANGSMITH_ENABLED, settings.langsmithEnabled)
            .putString(P_LANGSMITH_PROJECT, settings.langsmithProject.trim())
            .putString(P_LANGSMITH_REGION, settings.langsmithRegion.name)
            .putBoolean(P_LANGSMITH_INCLUDE_CONTENT, settings.langsmithIncludeContent)
            .putString(P_TRANSCRIPTION_AZURE_OPENAI_API_KEY, encrypt(transcription.azureOpenAi.apiKey))
            .putString(P_TRANSCRIPTION_AZURE_SPEECH_API_KEY, encrypt(transcription.azureSpeech.apiKey))
            .putString(P_TRANSCRIPTION_GEMINI_API_KEY, encrypt(transcription.geminiLive.apiKey))
            .putString(P_FORMAT_AZURE_API_KEY, encrypt(profiles.azure.apiKey))
            .putString(P_FORMAT_OPENAI_API_KEY, encrypt(profiles.openAi.apiKey))
            .putString(P_FORMAT_GEMINI_API_KEY, encrypt(profiles.gemini.apiKey))
            .putString(P_LANGSMITH_API_KEY, encrypt(settings.langsmithApiKey))
            .remove(P_FORMAT_ENDPOINT)
            .remove(P_FORMAT_API_KEY)
            .remove(P_FORMAT_MODEL)
            .remove(P_TRANSCRIPTION_ENDPOINT)
            .remove(P_TRANSCRIPTION_API_KEY)
            .remove(P_TRANSCRIPTION_MODEL)
            .remove(P_SPEECH_ENDPOINT)
            .apply()
    }

    /** 旧版の共有キーは、選択中だったプロバイダーの持ち物として引き継ぐ。 */
    private fun loadTranscriptionProfiles(legacyProvider: TranscriptionProvider): TranscriptionProfiles {
        if (!preferences.contains(P_TRANSCRIPTION_AZURE_OPENAI_MODEL)) {
            return migrateLegacyTranscriptionProfiles(
                provider = legacyProvider,
                apiKey = secret(P_TRANSCRIPTION_API_KEY),
                azureOpenAiEndpoint = plain(P_TRANSCRIPTION_ENDPOINT),
                azureOpenAiModel = plain(P_TRANSCRIPTION_MODEL, DEFAULT_AZURE_OPENAI_TRANSCRIBE_MODEL),
                azureSpeechEndpoint = plain(P_SPEECH_ENDPOINT),
            )
        }
        return TranscriptionProfiles(
            azureOpenAi = ApiProfile(
                endpoint = plain(P_TRANSCRIPTION_AZURE_OPENAI_ENDPOINT),
                apiKey = secret(P_TRANSCRIPTION_AZURE_OPENAI_API_KEY),
                model = plain(P_TRANSCRIPTION_AZURE_OPENAI_MODEL, DEFAULT_AZURE_OPENAI_TRANSCRIBE_MODEL),
            ),
            azureSpeech = ApiProfile(
                endpoint = plain(P_TRANSCRIPTION_AZURE_SPEECH_ENDPOINT),
                apiKey = secret(P_TRANSCRIPTION_AZURE_SPEECH_API_KEY),
                model = "",
            ),
            geminiLive = ApiProfile(
                apiKey = secret(P_TRANSCRIPTION_GEMINI_API_KEY),
                model = plain(P_TRANSCRIPTION_GEMINI_MODEL, DEFAULT_GEMINI_TRANSCRIBE_MODEL),
            ),
        )
    }

    private fun loadFormatProfiles(legacyProvider: FormatProvider): FormatProfiles {
        val defaults = FormatProfiles()
        if (!preferences.contains(P_FORMAT_AZURE_MODEL)) {
            return migrateLegacyFormatProfiles(
                provider = legacyProvider,
                profile = ApiProfile(
                    endpoint = if (legacyProvider == FormatProvider.AZURE) plain(P_FORMAT_ENDPOINT) else "",
                    apiKey = secret(P_FORMAT_API_KEY),
                    model = plain(P_FORMAT_MODEL, defaults[legacyProvider].model),
                ),
            )
        }
        return FormatProfiles(
            azure = ApiProfile(
                endpoint = plain(P_FORMAT_AZURE_ENDPOINT),
                apiKey = secret(P_FORMAT_AZURE_API_KEY),
                model = plain(P_FORMAT_AZURE_MODEL, defaults.azure.model),
            ),
            openAi = ApiProfile(
                apiKey = secret(P_FORMAT_OPENAI_API_KEY),
                model = plain(P_FORMAT_OPENAI_MODEL, defaults.openAi.model),
            ),
            gemini = ApiProfile(
                apiKey = secret(P_FORMAT_GEMINI_API_KEY),
                model = plain(P_FORMAT_GEMINI_MODEL, defaults.gemini.model),
            ),
        )
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
        // 旧版の単一設定。新形式がなければ移行元として読む。
        const val P_TRANSCRIPTION_ENDPOINT = "transcription_endpoint"
        const val P_TRANSCRIPTION_API_KEY = "transcription_api_key"
        const val P_TRANSCRIPTION_MODEL = "transcription_model"
        const val P_SPEECH_ENDPOINT = "speech_endpoint"
        const val P_SPEECH_LANGUAGE = "speech_language"
        const val P_TRANSCRIPTION_AZURE_OPENAI_ENDPOINT = "transcription_azure_openai_endpoint"
        const val P_TRANSCRIPTION_AZURE_OPENAI_API_KEY = "transcription_azure_openai_api_key"
        const val P_TRANSCRIPTION_AZURE_OPENAI_MODEL = "transcription_azure_openai_model"
        const val P_TRANSCRIPTION_AZURE_SPEECH_ENDPOINT = "transcription_azure_speech_endpoint"
        const val P_TRANSCRIPTION_AZURE_SPEECH_API_KEY = "transcription_azure_speech_api_key"
        const val P_TRANSCRIPTION_GEMINI_API_KEY = "transcription_gemini_api_key"
        const val P_TRANSCRIPTION_GEMINI_MODEL = "transcription_gemini_model"
        const val P_FORMAT_ENABLED = "format_enabled"
        const val P_FORMAT_PROVIDER = "format_provider"
        // 旧版の単一設定。新形式がなければ移行元として読む。
        const val P_FORMAT_ENDPOINT = "format_endpoint"
        const val P_FORMAT_API_KEY = "format_api_key"
        const val P_FORMAT_MODEL = "format_model"
        const val P_FORMAT_AZURE_ENDPOINT = "format_azure_endpoint"
        const val P_FORMAT_AZURE_API_KEY = "format_azure_api_key"
        const val P_FORMAT_AZURE_MODEL = "format_azure_model"
        const val P_FORMAT_OPENAI_API_KEY = "format_openai_api_key"
        const val P_FORMAT_OPENAI_MODEL = "format_openai_model"
        const val P_FORMAT_GEMINI_API_KEY = "format_gemini_api_key"
        const val P_FORMAT_GEMINI_MODEL = "format_gemini_model"
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
