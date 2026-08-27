package com.tomodo.freevoice.data

import com.tomodo.freevoice.network.toLangsmithConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsTest {
    private fun valid() = AppSettings(
        transcriptionProfiles = TranscriptionProfiles(
            azureOpenAi = ApiProfile("https://transcribe", "key", DEFAULT_AZURE_OPENAI_TRANSCRIBE_MODEL),
            azureSpeech = ApiProfile("https://speech", "speech-key", ""),
            geminiLive = ApiProfile(apiKey = "gemini-key", model = DEFAULT_GEMINI_TRANSCRIBE_MODEL),
        ),
        formatProfiles = FormatProfiles(
            azure = ApiProfile("https://format", "azure-key", DEFAULT_OPENAI_FORMAT_MODEL),
            openAi = ApiProfile(apiKey = "openai-key", model = DEFAULT_OPENAI_FORMAT_MODEL),
            gemini = ApiProfile(apiKey = "gemini-key", model = DEFAULT_GEMINI_FORMAT_MODEL),
        ),
    )

    private fun AppSettings.withTranscription(
        provider: TranscriptionProvider,
        update: (ApiProfile) -> ApiProfile,
    ) = copy(
        transcriptionProvider = provider,
        transcriptionProfiles = transcriptionProfiles.replacing(provider, update(transcriptionProfiles[provider])),
    )

    private fun AppSettings.withFormat(provider: FormatProvider, update: (ApiProfile) -> ApiProfile) =
        copy(formatProfiles = formatProfiles.replacing(provider, update(formatProfiles[provider])))

    @Test fun `format defaults use terra with low reasoning`() {
        val settings = AppSettings()
        assertEquals(DEFAULT_OPENAI_FORMAT_MODEL, settings.formatProfiles.azure.model)
        assertEquals("low", settings.reasoningEffort)
    }
    @Test fun `azure openai requires endpoint and model`() {
        val azure = TranscriptionProvider.AZURE_OPENAI
        assertEquals(
            "Azure OpenAI のエンドポイントを入力して",
            valid().withTranscription(azure) { it.copy(endpoint = "") }.validateForVoiceInput(),
        )
        assertEquals(
            "文字起こしモデルを入力して",
            valid().withTranscription(azure) { it.copy(model = "") }.validateForVoiceInput(),
        )
    }
    @Test fun `speech requires its endpoint and language`() {
        val speech = valid().copy(transcriptionProvider = TranscriptionProvider.AZURE_SPEECH)
        assertNull(speech.validateForVoiceInput())
        assertEquals(
            "Azure Speech のエンドポイントを入力して",
            speech.withTranscription(TranscriptionProvider.AZURE_SPEECH) { it.copy(endpoint = "") }.validateForVoiceInput(),
        )
        assertEquals("音声言語を入力して", speech.copy(speechLanguage = "").validateForVoiceInput())
    }

    /** プロバイダーごとにキーを分けた意味は、選んでいない側の空キーで止められないこと。 */
    @Test fun `each provider is validated with its own key`() {
        val onlyGemini = AppSettings(
            transcriptionProvider = TranscriptionProvider.GEMINI_LIVE,
            transcriptionProfiles = TranscriptionProfiles(
                geminiLive = ApiProfile(apiKey = "gemini-key", model = DEFAULT_GEMINI_TRANSCRIBE_MODEL),
            ),
            formatEnabled = false,
        )
        assertNull(onlyGemini.validateForVoiceInput())
        assertEquals(
            "文字起こし API キーを入力して",
            onlyGemini.copy(transcriptionProvider = TranscriptionProvider.AZURE_OPENAI).validateForVoiceInput(),
        )
    }
    @Test fun `gemini live requires model and language but has no endpoint`() {
        val live = valid().copy(transcriptionProvider = TranscriptionProvider.GEMINI_LIVE)
        assertNull(live.validateForVoiceInput())
        assertEquals(
            "文字起こしモデルを入力して",
            live.withTranscription(TranscriptionProvider.GEMINI_LIVE) { it.copy(model = "") }.validateForVoiceInput(),
        )
        assertEquals("音声言語を入力して", live.copy(speechLanguage = "").validateForVoiceInput())
    }
    @Test fun `langsmith is opt-in and never blocks voice input`() {
        assertEquals(false, AppSettings().langsmithEnabled)
        assertEquals(false, AppSettings().toLangsmithConfig().active)
        assertNull(valid().copy(langsmithEnabled = true, langsmithApiKey = "").validateForVoiceInput())
    }
    @Test fun `langsmith config mirrors the saved settings`() {
        val config = valid().copy(
            langsmithEnabled = true, langsmithApiKey = "ls-key", langsmithProject = "freevoice-android",
            langsmithRegion = LangsmithRegion.EU, langsmithIncludeContent = false,
        ).toLangsmithConfig()
        assertEquals(true, config.active)
        assertEquals("ls-key", config.apiKey)
        assertEquals("freevoice-android", config.project)
        assertEquals(LangsmithRegion.EU, config.region)
        assertEquals(false, config.includeContent)
    }
    @Test fun `format validation respects enabled and provider`() {
        assertNull(valid().copy(formatEnabled = false).validateForVoiceInput())
        assertEquals(
            "Azure 整形 API のエンドポイントを入力して",
            valid().withFormat(FormatProvider.AZURE) { it.copy(endpoint = "") }.validateForVoiceInput(),
        )
        assertNull(valid().copy(formatProvider = FormatProvider.OPENAI).validateForVoiceInput())
    }
    @Test fun `gemini formatting requires key and model but not endpoint`() {
        val gemini = valid().copy(formatProvider = FormatProvider.GEMINI)
        assertNull(gemini.validateForVoiceInput())
        assertEquals(
            "整形 API キーを入力して",
            gemini.withFormat(FormatProvider.GEMINI) { it.copy(apiKey = "") }.validateForVoiceInput(),
        )
        assertEquals(
            "整形モデルを入力して",
            gemini.withFormat(FormatProvider.GEMINI) { it.copy(model = "") }.validateForVoiceInput(),
        )
    }
}
