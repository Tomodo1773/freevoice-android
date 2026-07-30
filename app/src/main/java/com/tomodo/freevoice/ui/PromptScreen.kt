package com.tomodo.freevoice.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.TextView
import android.widget.Toast
import com.tomodo.freevoice.R
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.databinding.ScreenPromptBinding

internal class PromptScreen(
    val binding: ScreenPromptBinding,
    onSave: () -> Unit,
) {
    private val context = binding.root.context

    init {
        binding.promptSaveButton.setOnClickListener { onSave() }
        binding.promptDefaultButton.setOnClickListener { showDefaultPrompt() }
    }

    fun bind(settings: AppSettings) {
        binding.promptEditor.setText(settings.postprocessPrompt)
    }

    fun collect(base: AppSettings): AppSettings =
        base.copy(postprocessPrompt = binding.promptEditor.text.toString())

    fun showStatus(message: String) {
        binding.promptSaveStatus.text = message
    }

    private fun showDefaultPrompt() {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.default_prompt_title)
            .setMessage(AppSettings.DEFAULT_POSTPROCESS_PROMPT)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.copy) { _, _ -> copyDefaultPrompt() }
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun copyDefaultPrompt() {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(
                context.getString(R.string.default_prompt_title),
                AppSettings.DEFAULT_POSTPROCESS_PROMPT,
            ),
        )
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
