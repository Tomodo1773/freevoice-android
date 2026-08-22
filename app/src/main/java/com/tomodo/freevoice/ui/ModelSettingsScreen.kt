package com.tomodo.freevoice.ui

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.FormatProfile
import com.tomodo.freevoice.data.FormatProfiles
import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.LangsmithRegion
import com.tomodo.freevoice.data.TranscriptionProvider
import com.tomodo.freevoice.databinding.ScreenModelSettingsBinding
import com.tomodo.freevoice.settingsFieldVisibility

internal class ModelSettingsScreen(
    private val context: Context,
    val binding: ScreenModelSettingsBinding,
    onSave: () -> Unit,
) {
    private var shownFormatProvider = FormatProvider.AZURE
    private var formatProfiles = FormatProfiles()

    init {
        binding.modelTranscriptionProvider.adapter = adapter(arrayOf("Azure OpenAI", "Azure Speech"))
        binding.modelFormatProvider.adapter = adapter(FORMAT_PROVIDERS.map { it.second }.toTypedArray())
        binding.modelReasoningEffort.adapter = adapter(REASONING_EFFORTS)
        binding.modelLangsmithRegion.adapter = adapter(LangsmithRegion.entries.map { it.name }.toTypedArray())
        binding.modelLangsmithEnabled.setOnCheckedChangeListener { _, _ -> renderVisibility() }
        binding.modelTranscriptionProvider.onSelectionChanged(::renderVisibility)
        binding.modelFormatProvider.onSelectionChanged(::onFormatProviderSelected)
        binding.modelFormatEnabled.setOnCheckedChangeListener { _, _ -> renderVisibility() }
        binding.modelSaveButton.setOnClickListener { onSave() }
    }

    fun bind(settings: AppSettings) {
        formatProfiles = settings.formatProfiles
        shownFormatProvider = settings.formatProvider
        binding.modelTranscriptionProvider.setSelection(settings.transcriptionProvider.ordinal)
        binding.modelTranscriptionEndpoint.setText(settings.transcriptionEndpoint)
        binding.modelTranscriptionApiKey.setText(settings.transcriptionApiKey)
        binding.modelTranscriptionModel.setText(settings.transcriptionModel)
        binding.modelSpeechEndpoint.setText(settings.speechEndpoint)
        binding.modelSpeechLanguage.setText(settings.speechLanguage)
        binding.modelFormatEnabled.isChecked = settings.formatEnabled
        showFormatProfile(formatProfiles[shownFormatProvider])
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
        formatProfiles = formatProfiles.replacing(shownFormatProvider, readFormatProfile())
        return base.copy(
            transcriptionProvider = TranscriptionProvider.entries.getOrElse(
                binding.modelTranscriptionProvider.selectedItemPosition,
            ) { TranscriptionProvider.AZURE_OPENAI },
            transcriptionEndpoint = binding.modelTranscriptionEndpoint.text.toString(),
            transcriptionApiKey = binding.modelTranscriptionApiKey.text.toString(),
            transcriptionModel = binding.modelTranscriptionModel.text.toString(),
            speechEndpoint = binding.modelSpeechEndpoint.text.toString(),
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

    private fun readFormatProfile() = FormatProfile(
        endpoint = binding.modelFormatEndpoint.text.toString(),
        apiKey = binding.modelFormatApiKey.text.toString(),
        model = binding.modelFormatModel.text.toString(),
    )

    private fun showFormatProfile(profile: FormatProfile) {
        binding.modelFormatEndpoint.setText(profile.endpoint)
        binding.modelFormatApiKey.setText(profile.apiKey)
        binding.modelFormatModel.setText(profile.model)
    }

    private fun renderVisibility() {
        val transcription = TranscriptionProvider.entries.getOrElse(
            binding.modelTranscriptionProvider.selectedItemPosition,
        ) { TranscriptionProvider.AZURE_OPENAI }
        val fields = settingsFieldVisibility(transcription, shownFormatProvider)
        binding.modelTranscriptionEndpointRow.isVisible = fields.transcriptionEndpoint
        binding.modelTranscriptionModelRow.isVisible = fields.transcriptionModel
        binding.modelSpeechEndpointRow.isVisible = fields.speechEndpoint
        binding.modelSpeechLanguageRow.isVisible = fields.speechLanguage
        binding.modelFormatFields.isVisible = binding.modelFormatEnabled.isChecked
        binding.modelFormatEndpointRow.isVisible = fields.formatEndpoint
        binding.modelLangsmithFields.isVisible = binding.modelLangsmithEnabled.isChecked
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
        private val FORMAT_PROVIDERS = listOf(
            FormatProvider.AZURE to "Azure OpenAI",
            FormatProvider.OPENAI to "OpenAI",
            FormatProvider.GEMINI to "Gemini",
        )
        private val REASONING_EFFORTS = arrayOf("none", "low", "medium", "high")
    }
}
