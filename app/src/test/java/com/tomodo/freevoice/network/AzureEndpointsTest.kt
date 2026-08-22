package com.tomodo.freevoice.network

import org.junit.Assert.assertEquals
import org.junit.Test

class AzureEndpointsTest {
    @Test fun `services AI project endpoint becomes openai resource origin`() {
        assertEquals("https://resource.openai.azure.com", AzureEndpoints.normalizeOpenAiEndpoint("https://resource.services.ai.azure.com/api/projects/foo"))
    }
    @Test fun `transcription uses normalized endpoint and version`() {
        assertEquals("https://resource.openai.azure.com/openai/deployments/whisper/audio/transcriptions?api-version=2024-10-21", AzureEndpoints.transcription("https://resource.services.ai.azure.com/api/projects/foo", "whisper"))
    }
    @Test fun `azure chat url is deterministic`() {
        assertEquals("https://r.openai.azure.com/openai/v1/chat/completions", AzureEndpoints.azureChat("https://r.openai.azure.com/anything"))
    }
    @Test(expected = IllegalArgumentException::class) fun `non https endpoint fails`() { AzureEndpoints.normalizeOpenAiEndpoint("http://r.openai.azure.com") }
    @Test(expected = IllegalArgumentException::class) fun `hostless endpoint fails`() { AzureEndpoints.normalizeOpenAiEndpoint("https:///path") }
}
