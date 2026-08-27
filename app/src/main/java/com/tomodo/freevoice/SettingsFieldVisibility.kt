package com.tomodo.freevoice

import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.TranscriptionProvider

internal data class SettingsFieldVisibility(
    val transcriptionEndpoint: Boolean,
    val transcriptionModel: Boolean,
    val speechLanguage: Boolean,
    val formatEndpoint: Boolean,
)

internal fun settingsFieldVisibility(
    transcriptionProvider: TranscriptionProvider,
    formatProvider: FormatProvider,
) = SettingsFieldVisibility(
    // 「見せない側」ではなく「使う側」を挙げる。プロバイダーが増えたとき、既定で
    // 露出してしまうより、出し忘れて気づくほうが安全なため。
    transcriptionEndpoint = transcriptionProvider in ENDPOINT_PROVIDERS,
    transcriptionModel = transcriptionProvider in MODEL_PROVIDERS,
    speechLanguage = transcriptionProvider in LANGUAGE_PROVIDERS,
    formatEndpoint = formatProvider == FormatProvider.AZURE,
)

/** Live API の接続先は定数なので、入力させるのは Azure の2つだけ。 */
private val ENDPOINT_PROVIDERS = setOf(TranscriptionProvider.AZURE_OPENAI, TranscriptionProvider.AZURE_SPEECH)

/** Speech は接続先と言語だけで決まり、モデルを取らない。 */
private val MODEL_PROVIDERS = setOf(TranscriptionProvider.AZURE_OPENAI, TranscriptionProvider.GEMINI_LIVE)

/** 言語はストリーミングの2プロバイダーが使う。 */
private val LANGUAGE_PROVIDERS = setOf(TranscriptionProvider.AZURE_SPEECH, TranscriptionProvider.GEMINI_LIVE)
