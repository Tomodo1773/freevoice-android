package com.tomodo.freevoice.ui

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.tomodo.freevoice.R
import com.tomodo.freevoice.data.ApiProfile
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.FormatProfiles
import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.LangsmithRegion
import com.tomodo.freevoice.data.TranscriptionProfiles
import com.tomodo.freevoice.data.TranscriptionProvider
import com.tomodo.freevoice.databinding.ScreenModelSettingsBinding
import com.tomodo.freevoice.settingsFieldVisibility

internal class ModelSettingsScreen(
    private val context: Context,
    val binding: ScreenModelSettingsBinding,
    onSave: () -> Unit,
) {
    private var shownTranscriptionProvider = TranscriptionProvider.AZURE_OPENAI
    private var transcriptionProfiles = TranscriptionProfiles()
    private var shownFormatProvider = FormatProvider.AZURE
    private var formatProfiles = FormatProfiles()

    init {
        binding.modelTranscriptionProvider.adapter = adapter(TRANSCRIPTION_PROVIDERS.map { it.second }.toTypedArray())
        binding.modelFormatProvider.adapter = adapter(FORMAT_PROVIDERS.map { it.second }.toTypedArray())
        binding.modelReasoningEffort.adapter = adapter(REASONING_EFFORTS)
        binding.modelLangsmithRegion.adapter = adapter(LangsmithRegion.entries.map { it.name }.toTypedArray())
        binding.modelLangsmithEnabled.setOnCheckedChangeListener { _, _ -> renderVisibility() }
        binding.modelTranscriptionProvider.onSelectionChanged(::onTranscriptionProviderSelected)
        binding.modelFormatProvider.onSelectionChanged(::onFormatProviderSelected)
        binding.modelFormatEnabled.setOnCheckedChangeListener { _, _ -> renderVisibility() }
        binding.modelSaveButton.setOnClickListener { onSave() }
    }

    fun bind(settings: AppSettings) {
        transcriptionProfiles = settings.transcriptionProfiles
        shownTranscriptionProvider = settings.transcriptionProvider
        showTranscriptionProfile(transcriptionProfiles[shownTranscriptionProvider])
        binding.modelTranscriptionProvider.setSelection(
            TRANSCRIPTION_PROVIDERS.indexOfFirst { it.first == settings.transcriptionProvider },
        )
        binding.modelSpeechLanguage.setText(settings.speechLanguage)
        formatProfiles = settings.formatProfiles
        shownFormatProvider = settings.formatProvider
        showFormatProfile(formatProfiles[shownFormatProvider])
        binding.modelFormatEnabled.isChecked = settings.formatEnabled
        binding.modelFormatProvider.setSelection(FORMAT_PROVIDERS.indexOfFirst { it.first == settings.formatProvider })
        binding.modelReasoningEffort.setSelection(
            REASONING_EFFORTS.indexOf(settings.reasoningEffort)
                .takeIf { it >= 0 }
                ?: REASONING_EFFORTS.indexOf(AppSettings.DEFAULT_REASONING_EFFORT),
        )
        binding.modelContextAware.isChecked = settings.contextAwareFormatting
        binding.modelLangsmithEnabled.isChecked = settings.langsmithEnabled
        binding.modelLangsmithApiKey.setText(settings.langsmithApiKey)
        binding.modelLangsmithProject.setText(settings.langsmithProject)
        binding.modelLangsmithRegion.setSelection(settings.langsmithRegion.ordinal)
        binding.modelLangsmithIncludeContent.isChecked = settings.langsmithIncludeContent
        renderVisibility()
    }

    fun collect(base: AppSettings): AppSettings {
        transcriptionProfiles = transcriptionProfiles.replacing(shownTranscriptionProvider, readTranscriptionProfile())
        formatProfiles = formatProfiles.replacing(shownFormatProvider, readFormatProfile())
        return base.copy(
            transcriptionProvider = shownTranscriptionProvider,
            transcriptionProfiles = transcriptionProfiles,
            speechLanguage = binding.modelSpeechLanguage.text.toString(),
            formatEnabled = binding.modelFormatEnabled.isChecked,
            formatProvider = shownFormatProvider,
            formatProfiles = formatProfiles,
            reasoningEffort = binding.modelReasoningEffort.selectedItem?.toString()
                ?: AppSettings.DEFAULT_REASONING_EFFORT,
            contextAwareFormatting = binding.modelContextAware.isChecked,
            langsmithEnabled = binding.modelLangsmithEnabled.isChecked,
            langsmithApiKey = binding.modelLangsmithApiKey.text.toString(),
            langsmithProject = binding.modelLangsmithProject.text.toString(),
            langsmithRegion = LangsmithRegion.entries.getOrElse(
                binding.modelLangsmithRegion.selectedItemPosition,
            ) { LangsmithRegion.US },
            langsmithIncludeContent = binding.modelLangsmithIncludeContent.isChecked,
        )
    }

    fun showStatus(message: String) {
        binding.modelSaveStatus.text = message
    }

    /** 表示中のプロバイダーの入力を退避してから差し替える。キーが混ざらない唯一の順序。 */
    private fun onTranscriptionProviderSelected() {
        val nextProvider = TRANSCRIPTION_PROVIDERS.getOrElse(
            binding.modelTranscriptionProvider.selectedItemPosition,
        ) { TRANSCRIPTION_PROVIDERS.first() }.first
        if (shownTranscriptionProvider != nextProvider) {
            transcriptionProfiles = transcriptionProfiles.replacing(shownTranscriptionProvider, readTranscriptionProfile())
            shownTranscriptionProvider = nextProvider
            showTranscriptionProfile(transcriptionProfiles[shownTranscriptionProvider])
        }
        renderVisibility()
    }

    private fun onFormatProviderSelected() {
        val nextProvider = FORMAT_PROVIDERS.getOrElse(
            binding.modelFormatProvider.selectedItemPosition,
        ) { FORMAT_PROVIDERS.first() }.first
        if (shownFormatProvider != nextProvider) {
            formatProfiles = formatProfiles.replacing(shownFormatProvider, readFormatProfile())
            shownFormatProvider = nextProvider
            showFormatProfile(formatProfiles[shownFormatProvider])
        }
        renderVisibility()
    }

    private fun readTranscriptionProfile() = ApiProfile(
        endpoint = binding.modelTranscriptionEndpoint.text.toString(),
        apiKey = binding.modelTranscriptionApiKey.text.toString(),
        model = binding.modelTranscriptionModel.text.toString(),
    )

    private fun showTranscriptionProfile(profile: ApiProfile) {
        binding.modelTranscriptionEndpoint.setText(profile.endpoint)
        binding.modelTranscriptionApiKey.setText(profile.apiKey)
        binding.modelTranscriptionModel.setText(profile.model)
    }

    private fun readFormatProfile() = ApiProfile(
        endpoint = binding.modelFormatEndpoint.text.toString(),
        apiKey = binding.modelFormatApiKey.text.toString(),
        model = binding.modelFormatModel.text.toString(),
    )

    private fun showFormatProfile(profile: ApiProfile) {
        binding.modelFormatEndpoint.setText(profile.endpoint)
        binding.modelFormatApiKey.setText(profile.apiKey)
        binding.modelFormatModel.setText(profile.model)
    }

    private fun renderVisibility() {
        val fields = settingsFieldVisibility(shownTranscriptionProvider, shownFormatProvider)
        binding.modelTranscriptionEndpointRow.isVisible = fields.transcriptionEndpoint
        binding.modelTranscriptionModelRow.isVisible = fields.transcriptionModel
        binding.modelSpeechLanguageRow.isVisible = fields.speechLanguage
        if (fields.transcriptionEndpoint) showEndpointLabels(shownTranscriptionProvider)
        binding.modelFormatFields.isVisible = binding.modelFormatEnabled.isChecked
        binding.modelFormatEndpointRow.isVisible = fields.formatEndpoint
        binding.modelLangsmithFields.isVisible = binding.modelLangsmithEnabled.isChecked
    }

    /** 入力欄は1つだが、宛先はプロバイダーで違う。見出しと例をそれに合わせる。 */
    private fun showEndpointLabels(provider: TranscriptionProvider) {
        val speech = provider == TranscriptionProvider.AZURE_SPEECH
        binding.modelTranscriptionEndpointLabel.setText(
            if (speech) R.string.speech_endpoint else R.string.transcription_endpoint,
        )
        binding.modelTranscriptionEndpointExample.setText(
            if (speech) R.string.speech_endpoint_example else R.string.azure_openai_endpoint_example,
        )
    }

    private fun adapter(items: Array<String>) =
        ArrayAdapter(context, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun Spinner.onSelectionChanged(action: () -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) = action()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private var View.isVisible: Boolean
        get() = visibility == View.VISIBLE
        set(value) {
            visibility = if (value) View.VISIBLE else View.GONE
        }

    companion object {
        private val TRANSCRIPTION_PROVIDERS = listOf(
            TranscriptionProvider.AZURE_OPENAI to "Azure OpenAI",
            TranscriptionProvider.AZURE_SPEECH to "Azure Speech",
            TranscriptionProvider.GEMINI_LIVE to "Gemini Live",
        )
        private val FORMAT_PROVIDERS = listOf(
            FormatProvider.AZURE to "Azure OpenAI",
            FormatProvider.OPENAI to "OpenAI",
            FormatProvider.GEMINI to "Gemini",
        )
        private val REASONING_EFFORTS = arrayOf("none", "low", "medium", "high")
    }
}
