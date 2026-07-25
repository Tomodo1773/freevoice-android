package com.tomodo.freevoice.ui

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.TranscriptionProvider
import com.tomodo.freevoice.databinding.ScreenModelSettingsBinding
import com.tomodo.freevoice.settingsFieldVisibility

internal class ModelSettingsScreen(
    private val context: Context,
    val binding: ScreenModelSettingsBinding,
    onSave: () -> Unit,
) {
    init {
        binding.modelTranscriptionProvider.adapter = adapter(arrayOf("Azure OpenAI", "Azure Speech"))
        binding.modelFormatProvider.adapter = adapter(arrayOf("Azure OpenAI", "OpenAI"))
        binding.modelReasoningEffort.adapter = adapter(REASONING_EFFORTS)
        binding.modelTranscriptionProvider.onSelectionChanged(::renderVisibility)
        binding.modelFormatProvider.onSelectionChanged(::renderVisibility)
        binding.modelFormatEnabled.setOnCheckedChangeListener { _, _ -> renderVisibility() }
        binding.modelSaveButton.setOnClickListener { onSave() }
    }

    fun bind(settings: AppSettings) {
        binding.modelTranscriptionProvider.setSelection(settings.transcriptionProvider.ordinal)
        binding.modelTranscriptionEndpoint.setText(settings.transcriptionEndpoint)
        binding.modelTranscriptionApiKey.setText(settings.transcriptionApiKey)
        binding.modelTranscriptionModel.setText(settings.transcriptionModel)
        binding.modelSpeechEndpoint.setText(settings.speechEndpoint)
        binding.modelSpeechLanguage.setText(settings.speechLanguage)
        binding.modelFormatEnabled.isChecked = settings.formatEnabled
        binding.modelFormatProvider.setSelection(settings.formatProvider.ordinal)
        binding.modelFormatEndpoint.setText(settings.formatEndpoint)
        binding.modelFormatApiKey.setText(settings.formatApiKey)
        binding.modelFormatModel.setText(settings.formatModel)
        binding.modelReasoningEffort.setSelection(
            REASONING_EFFORTS.indexOf(settings.reasoningEffort)
                .takeIf { it >= 0 }
                ?: REASONING_EFFORTS.indexOf(AppSettings.DEFAULT_REASONING_EFFORT),
        )
        binding.modelContextAware.isChecked = settings.contextAwareFormatting
        renderVisibility()
    }

    fun collect(base: AppSettings): AppSettings = base.copy(
        transcriptionProvider = TranscriptionProvider.entries.getOrElse(
            binding.modelTranscriptionProvider.selectedItemPosition,
        ) { TranscriptionProvider.AZURE_OPENAI },
        transcriptionEndpoint = binding.modelTranscriptionEndpoint.text.toString(),
        transcriptionApiKey = binding.modelTranscriptionApiKey.text.toString(),
        transcriptionModel = binding.modelTranscriptionModel.text.toString(),
        speechEndpoint = binding.modelSpeechEndpoint.text.toString(),
        speechLanguage = binding.modelSpeechLanguage.text.toString(),
        formatEnabled = binding.modelFormatEnabled.isChecked,
        formatProvider = FormatProvider.entries.getOrElse(
            binding.modelFormatProvider.selectedItemPosition,
        ) { FormatProvider.AZURE },
        formatEndpoint = binding.modelFormatEndpoint.text.toString(),
        formatApiKey = binding.modelFormatApiKey.text.toString(),
        formatModel = binding.modelFormatModel.text.toString(),
        reasoningEffort = binding.modelReasoningEffort.selectedItem?.toString()
            ?: AppSettings.DEFAULT_REASONING_EFFORT,
        contextAwareFormatting = binding.modelContextAware.isChecked,
    )

    fun showStatus(message: String) {
        binding.modelSaveStatus.text = message
    }

    private fun renderVisibility() {
        val transcription = TranscriptionProvider.entries.getOrElse(
            binding.modelTranscriptionProvider.selectedItemPosition,
        ) { TranscriptionProvider.AZURE_OPENAI }
        val format = FormatProvider.entries.getOrElse(
            binding.modelFormatProvider.selectedItemPosition,
        ) { FormatProvider.AZURE }
        val fields = settingsFieldVisibility(transcription, format)
        binding.modelTranscriptionEndpointRow.isVisible = fields.transcriptionEndpoint
        binding.modelTranscriptionModelRow.isVisible = fields.transcriptionModel
        binding.modelSpeechEndpointRow.isVisible = fields.speechEndpoint
        binding.modelSpeechLanguageRow.isVisible = fields.speechLanguage
        binding.modelFormatFields.isVisible = binding.modelFormatEnabled.isChecked
        binding.modelFormatEndpointRow.isVisible = fields.formatEndpoint
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
        private val REASONING_EFFORTS = arrayOf("none", "low", "medium", "high")
    }
}
