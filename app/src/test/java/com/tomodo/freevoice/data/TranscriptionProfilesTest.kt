package com.tomodo.freevoice.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionProfilesTest {
    @Test fun `defaults are provider specific`() {
        val profiles = TranscriptionProfiles()

        assertEquals(DEFAULT_AZURE_OPENAI_TRANSCRIBE_MODEL, profiles.azureOpenAi.model)
        assertEquals("", profiles.azureSpeech.model)
        assertEquals(DEFAULT_GEMINI_TRANSCRIBE_MODEL, profiles.geminiLive.model)
    }

    /** プロバイダーを切り替えても前のキーが残らないことが、分けた目的そのもの。 */
    @Test fun `replacing one provider preserves the others`() {
        val profiles = TranscriptionProfiles().replacing(
            TranscriptionProvider.GEMINI_LIVE,
            ApiProfile(apiKey = "gemini-key", model = "gemini-model"),
        )

        assertEquals("gemini-key", profiles.geminiLive.apiKey)
        assertEquals("", profiles.azureOpenAi.apiKey)
        assertEquals("", profiles.azureSpeech.apiKey)
    }

    @Test fun `the legacy shared key moves only to the provider that was selected`() {
        val profiles = migrateLegacyTranscriptionProfiles(
            provider = TranscriptionProvider.AZURE_SPEECH,
            apiKey = "speech-key",
            azureOpenAiEndpoint = "https://transcribe",
            azureOpenAiModel = "gpt-4o-transcribe",
            azureSpeechEndpoint = "https://speech",
        )

        assertEquals("speech-key", profiles.azureSpeech.apiKey)
        assertEquals("", profiles.azureOpenAi.apiKey)
        assertEquals("", profiles.geminiLive.apiKey)
    }

    /** キーだけが共有だった。エンドポイントとモデルは旧版でも別々なので、そのまま残す。 */
    @Test fun `migration keeps the endpoints and models of every provider`() {
        val profiles = migrateLegacyTranscriptionProfiles(
            provider = TranscriptionProvider.AZURE_OPENAI,
            apiKey = "azure-key",
            azureOpenAiEndpoint = "https://transcribe",
            azureOpenAiModel = "gpt-4o-transcribe",
            azureSpeechEndpoint = "https://speech",
        )

        assertEquals(ApiProfile("https://transcribe", "azure-key", "gpt-4o-transcribe"), profiles.azureOpenAi)
        assertEquals(ApiProfile("https://speech", "", ""), profiles.azureSpeech)
        assertEquals(DEFAULT_GEMINI_TRANSCRIBE_MODEL, profiles.geminiLive.model)
    }
}
