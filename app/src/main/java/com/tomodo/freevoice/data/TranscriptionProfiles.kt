package com.tomodo.freevoice.data

enum class TranscriptionProvider { AZURE_OPENAI, AZURE_SPEECH, GEMINI_LIVE }

/**
 * 文字起こしプロバイダーごとの接続設定。整形側と同じく、プロバイダーを切り替えても
 * 前のキーが残らないよう、キーは1本を共有せずここで分ける。
 */
data class TranscriptionProfiles(
    val azureOpenAi: ApiProfile = ApiProfile(model = DEFAULT_AZURE_OPENAI_TRANSCRIBE_MODEL),
    // Speech はモデルを取らない。接続先と言語だけで決まる。
    val azureSpeech: ApiProfile = ApiProfile(model = ""),
    // Live API の接続先は定数なので endpoint を使わない。
    val geminiLive: ApiProfile = ApiProfile(model = DEFAULT_GEMINI_TRANSCRIBE_MODEL),
) {
    operator fun get(provider: TranscriptionProvider): ApiProfile = when (provider) {
        TranscriptionProvider.AZURE_OPENAI -> azureOpenAi
        TranscriptionProvider.AZURE_SPEECH -> azureSpeech
        TranscriptionProvider.GEMINI_LIVE -> geminiLive
    }

    fun replacing(provider: TranscriptionProvider, profile: ApiProfile): TranscriptionProfiles = when (provider) {
        TranscriptionProvider.AZURE_OPENAI -> copy(azureOpenAi = profile)
        TranscriptionProvider.AZURE_SPEECH -> copy(azureSpeech = profile)
        TranscriptionProvider.GEMINI_LIVE -> copy(geminiLive = profile)
    }
}

/**
 * 旧設定は API キーを1本しか持たず、エンドポイントとモデルだけがプロバイダーごとだった。
 * そのキーは選択中のプロバイダーのものなので、そこにだけ移す。他へ配ると、使うつもりの
 * なかった宛先へキーを送ってしまう。
 */
internal fun migrateLegacyTranscriptionProfiles(
    provider: TranscriptionProvider,
    apiKey: String,
    azureOpenAiEndpoint: String,
    azureOpenAiModel: String,
    azureSpeechEndpoint: String,
): TranscriptionProfiles = TranscriptionProfiles(
    azureOpenAi = ApiProfile(endpoint = azureOpenAiEndpoint, model = azureOpenAiModel),
    azureSpeech = ApiProfile(endpoint = azureSpeechEndpoint, model = ""),
).let { profiles ->
    profiles.replacing(provider, profiles[provider].copy(apiKey = apiKey))
}

const val DEFAULT_AZURE_OPENAI_TRANSCRIBE_MODEL = "gpt-4o-transcribe"
const val DEFAULT_GEMINI_TRANSCRIBE_MODEL = "gemini-3.5-transcribe-live"
