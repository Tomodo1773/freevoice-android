package com.tomodo.freevoice.data

enum class FormatProvider { AZURE, OPENAI, GEMINI }

data class FormatProfile(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String,
)

/** 固定された各プロバイダーの設定。欠損を隠す Map や可変状態を持たない。 */
data class FormatProfiles(
    val azure: FormatProfile = FormatProfile(model = DEFAULT_OPENAI_FORMAT_MODEL),
    val openAi: FormatProfile = FormatProfile(model = DEFAULT_OPENAI_FORMAT_MODEL),
    val gemini: FormatProfile = FormatProfile(model = DEFAULT_GEMINI_FORMAT_MODEL),
) {
    operator fun get(provider: FormatProvider): FormatProfile = when (provider) {
        FormatProvider.AZURE -> azure
        FormatProvider.OPENAI -> openAi
        FormatProvider.GEMINI -> gemini
    }

    fun replacing(provider: FormatProvider, profile: FormatProfile): FormatProfiles = when (provider) {
        FormatProvider.AZURE -> copy(azure = profile)
        FormatProvider.OPENAI -> copy(openAi = profile)
        FormatProvider.GEMINI -> copy(gemini = profile)
    }
}

internal fun migrateLegacyFormatProfiles(
    provider: FormatProvider,
    profile: FormatProfile,
): FormatProfiles = FormatProfiles().replacing(provider, profile)

const val DEFAULT_OPENAI_FORMAT_MODEL = "gpt-5.6-terra"
const val DEFAULT_GEMINI_FORMAT_MODEL = "gemini-3.7-flash"
