package com.tomodo.freevoice

import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.TranscriptionProvider

internal data class SettingsFieldVisibility(
    val transcriptionEndpoint: Boolean,
    val transcriptionModel: Boolean,
    val speechEndpoint: Boolean,
    val speechLanguage: Boolean,
    val formatEndpoint: Boolean,
)

internal fun settingsFieldVisibility(
    transcriptionProvider: TranscriptionProvider,
    formatProvider: FormatProvider,
) = SettingsFieldVisibility(
    transcriptionEndpoint = transcriptionProvider == TranscriptionProvider.AZURE_OPENAI,
    transcriptionModel = transcriptionProvider == TranscriptionProvider.AZURE_OPENAI,
    speechEndpoint = transcriptionProvider == TranscriptionProvider.AZURE_SPEECH,
    speechLanguage = transcriptionProvider == TranscriptionProvider.AZURE_SPEECH,
    formatEndpoint = formatProvider == FormatProvider.AZURE,
)
