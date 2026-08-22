package com.tomodo.freevoice.network

import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.FormatProfile
import com.tomodo.freevoice.data.FormatProfiles
import com.tomodo.freevoice.data.FormatProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRoutingTest {
    @Test fun `azure route uses normalized endpoint and api key header`() {
        assertEquals(
            ChatRequestRoute(
                "https://resource.openai.azure.com/openai/v1/chat/completions",
                mapOf("api-key" to "azure-key"),
            ),
            chatRequestRoute(FormatApiConfig(FormatProvider.AZURE, "https://resource.openai.azure.com/", "azure-key")),
        )
    }

    @Test fun `openai route uses bearer authentication`() {
        assertEquals(
            ChatRequestRoute(
                "https://api.openai.com/v1/chat/completions",
                mapOf("Authorization" to "Bearer openai-key"),
            ),
            chatRequestRoute(FormatApiConfig(FormatProvider.OPENAI, apiKey = "openai-key")),
        )
    }

    @Test fun `gemini route uses official openai compatible endpoint and bearer authentication`() {
        assertEquals(
            ChatRequestRoute(
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                mapOf("Authorization" to "Bearer gemini-key"),
            ),
            chatRequestRoute(FormatApiConfig(FormatProvider.GEMINI, apiKey = "gemini-key")),
        )
    }

    @Test fun `active provider selects only its own credentials`() {
        val settings = AppSettings(
            formatProvider = FormatProvider.GEMINI,
            formatProfiles = FormatProfiles(
                openAi = FormatProfile(apiKey = "openai-key", model = "openai-model"),
                gemini = FormatProfile(apiKey = "gemini-key", model = "gemini-model"),
            ),
        )

        val format = settings.toVoiceApiConfig().format
        assertEquals(FormatProvider.GEMINI, format.provider)
        assertEquals("gemini-key", format.apiKey)
        assertEquals("gemini-model", format.model)
    }
}
