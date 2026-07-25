package com.tomodo.freevoice.ui

import com.tomodo.freevoice.R
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.databinding.ScreenPromptBinding

internal class PromptScreen(
    val binding: ScreenPromptBinding,
    onSave: () -> Unit,
) {
    init {
        binding.promptSaveButton.setOnClickListener { onSave() }
        binding.promptRestoreButton.setOnClickListener {
            binding.promptEditor.setText(AppSettings.DEFAULT_POSTPROCESS_PROMPT)
            binding.promptSaveStatus.setText(R.string.prompt_restored)
        }
    }

    fun bind(settings: AppSettings) {
        binding.promptEditor.setText(settings.postprocessPrompt)
    }

    fun collect(base: AppSettings): AppSettings =
        base.copy(postprocessPrompt = binding.promptEditor.text.toString())

    fun showStatus(message: String) {
        binding.promptSaveStatus.text = message
    }
}
