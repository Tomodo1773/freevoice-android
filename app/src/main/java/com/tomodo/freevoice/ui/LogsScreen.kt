package com.tomodo.freevoice.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import com.tomodo.freevoice.R
import com.tomodo.freevoice.databinding.ItemDiagnosticBinding
import com.tomodo.freevoice.databinding.ScreenLogsBinding
import com.tomodo.freevoice.diag.DiagEntry
import com.tomodo.freevoice.diag.DiagLevel
import com.tomodo.freevoice.diag.DiagLogger

internal class LogsScreen(
    private val context: Context,
    val binding: ScreenLogsBinding,
    private val logger: DiagLogger,
) {
    private val adapter = DiagnosticAdapter(context, ::copy)

    init {
        binding.logsList.emptyView = binding.logsEmpty
        binding.logsList.adapter = adapter
        binding.logsClearButton.setOnClickListener {
            AlertDialog.Builder(context)
                .setMessage(R.string.diagnostics_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ ->
                    logger.clear()
                    refresh()
                }
                .show()
        }
    }

    fun refresh() {
        adapter.replace(logger.readLatestEntries(DISPLAY_LIMIT).asReversed())
        binding.logsClearButton.isEnabled = adapter.count > 0
    }

    private fun copy(entry: DiagEntry) {
        val text = "${entry.timestamp} ${entry.level.name} [${entry.source}] ${entry.message}"
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diagnostics_title), text))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DISPLAY_LIMIT = 100
    }
}

private class DiagnosticAdapter(
    private val context: Context,
    private val onCopy: (DiagEntry) -> Unit,
) : BaseAdapter() {
    private var entries = emptyList<DiagEntry>()

    fun replace(next: List<DiagEntry>) {
        entries = next
        notifyDataSetChanged()
    }

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): DiagEntry = entries[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView?.let(ItemDiagnosticBinding::bind)
            ?: ItemDiagnosticBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val entry = getItem(position)
        row.level.text = entry.level.name
        row.level.setTextColor(
            context.getColor(
                when (entry.level) {
                    DiagLevel.INFO -> R.color.fv_primary
                    DiagLevel.WARN -> R.color.fv_warning
                    DiagLevel.ERROR -> R.color.fv_error
                },
            ),
        )
        row.source.text = entry.source
        row.message.text = entry.message
        row.time.text = entry.timestamp
        row.copyButton.setOnClickListener { onCopy(entry) }
        return row.root
    }
}
