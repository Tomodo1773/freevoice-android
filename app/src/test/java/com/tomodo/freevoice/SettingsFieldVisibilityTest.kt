package com.tomodo.freevoice

import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.TranscriptionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFieldVisibilityTest {
    @Test fun `azure openai takes an endpoint and a model, not a language`() {
        assertEquals(
            SettingsFieldVisibility(
                transcriptionEndpoint = true,
                transcriptionModel = true,
                speechLanguage = false,
                formatEndpoint = true,
            ),
            settingsFieldVisibility(TranscriptionProvider.AZURE_OPENAI, FormatProvider.AZURE),
        )
    }

    @Test fun `azure speech takes an endpoint and a language, not a model`() {
        assertEquals(
            SettingsFieldVisibility(
                transcriptionEndpoint = true,
                transcriptionModel = false,
                speechLanguage = true,
                formatEndpoint = true,
            ),
            settingsFieldVisibility(TranscriptionProvider.AZURE_SPEECH, FormatProvider.AZURE),
        )
    }

    /** Live API の接続先は定数なので、エンドポイントは入力させない。 */
    @Test fun `gemini live takes a model and a language, not an endpoint`() {
        assertEquals(
            SettingsFieldVisibility(
                transcriptionEndpoint = false,
                transcriptionModel = true,
                speechLanguage = true,
                formatEndpoint = true,
            ),
            settingsFieldVisibility(TranscriptionProvider.GEMINI_LIVE, FormatProvider.AZURE),
        )
    }

    @Test fun `openai formatting hides azure endpoint`() {
        assertEquals(
            false,
            settingsFieldVisibility(TranscriptionProvider.AZURE_OPENAI, FormatProvider.OPENAI).formatEndpoint,
        )
    }

    @Test fun `gemini formatting hides azure endpoint`() {
        assertEquals(
            false,
            settingsFieldVisibility(TranscriptionProvider.AZURE_OPENAI, FormatProvider.GEMINI).formatEndpoint,
        )
    }
}
