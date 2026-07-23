package com.tomodo.freevoice

import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.TranscriptionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFieldVisibilityTest {
    @Test fun `azure openai transcription hides speech fields`() {
        assertEquals(
            SettingsFieldVisibility(
                transcriptionEndpoint = true,
                transcriptionModel = true,
                speechEndpoint = false,
                speechLanguage = false,
                formatEndpoint = true,
            ),
            settingsFieldVisibility(TranscriptionProvider.AZURE_OPENAI, FormatProvider.AZURE),
        )
    }

    @Test fun `azure speech hides openai transcription fields`() {
        assertEquals(
            SettingsFieldVisibility(
                transcriptionEndpoint = false,
                transcriptionModel = false,
                speechEndpoint = true,
                speechLanguage = true,
                formatEndpoint = true,
            ),
            settingsFieldVisibility(TranscriptionProvider.AZURE_SPEECH, FormatProvider.AZURE),
        )
    }

    @Test fun `openai formatting hides azure endpoint`() {
        assertEquals(
            false,
            settingsFieldVisibility(TranscriptionProvider.AZURE_OPENAI, FormatProvider.OPENAI).formatEndpoint,
        )
    }
}
