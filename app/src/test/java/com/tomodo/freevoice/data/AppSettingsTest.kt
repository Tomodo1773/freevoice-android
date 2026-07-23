package com.tomodo.freevoice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsTest {
    private fun valid() = AppSettings(transcriptionApiKey = "key", transcriptionEndpoint = "https://transcribe", formatEndpoint = "https://format", formatApiKey = "format-key")
    @Test fun `format defaults use terra with low reasoning`() {
        val settings = AppSettings()
        assertEquals("gpt-5.6-terra", settings.formatModel)
        assertEquals("low", settings.reasoningEffort)
    }
    @Test fun `azure openai requires endpoint and model`() {
        assertEquals("Azure OpenAI のエンドポイントを入力して", valid().copy(transcriptionEndpoint = "").validateForVoiceInput())
        assertEquals("文字起こしモデルを入力して", valid().copy(transcriptionModel = "").validateForVoiceInput())
    }
    @Test fun `speech requires its endpoint and language`() {
        val speech = valid().copy(transcriptionProvider = TranscriptionProvider.AZURE_SPEECH)
        assertEquals("Azure Speech のエンドポイントを入力して", speech.copy(speechEndpoint = "").validateForVoiceInput())
        assertEquals("音声言語を入力して", speech.copy(speechEndpoint = "https://speech", speechLanguage = "").validateForVoiceInput())
    }
    @Test fun `format validation respects enabled and provider`() {
        assertNull(valid().copy(formatEnabled = false).validateForVoiceInput())
        assertEquals("Azure 整形 API のエンドポイントを入力して", valid().copy(formatEndpoint = "").validateForVoiceInput())
        assertNull(valid().copy(formatProvider = FormatProvider.OPENAI, formatEndpoint = "").validateForVoiceInput())
    }
}
