package com.tomodo.freevoice.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatProfilesTest {
    @Test fun `defaults are provider specific`() {
        val profiles = FormatProfiles()

        assertEquals(DEFAULT_OPENAI_FORMAT_MODEL, profiles.azure.model)
        assertEquals(DEFAULT_OPENAI_FORMAT_MODEL, profiles.openAi.model)
        assertEquals(DEFAULT_GEMINI_FORMAT_MODEL, profiles.gemini.model)
    }

    @Test fun `replacing one provider preserves the others`() {
        val profiles = FormatProfiles().replacing(
            FormatProvider.OPENAI,
            FormatProfile(apiKey = "openai-key", model = "openai-model"),
        )

        assertEquals("openai-key", profiles.openAi.apiKey)
        assertEquals("", profiles.azure.apiKey)
        assertEquals("", profiles.gemini.apiKey)
    }

    @Test fun `legacy profile migrates only to selected provider`() {
        val profiles = migrateLegacyFormatProfiles(
            FormatProvider.OPENAI,
            FormatProfile(apiKey = "openai-key", model = "openai-custom"),
        )

        assertEquals(FormatProfile(apiKey = "openai-key", model = "openai-custom"), profiles.openAi)
        assertEquals(FormatProfile(model = DEFAULT_GEMINI_FORMAT_MODEL), profiles.gemini)
        assertEquals(FormatProfile(model = DEFAULT_OPENAI_FORMAT_MODEL), profiles.azure)
    }
}
