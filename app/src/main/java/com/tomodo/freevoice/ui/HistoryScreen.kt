package com.tomodo.freevoice.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import com.tomodo.freevoice.R
import com.tomodo.freevoice.databinding.ItemHistoryBinding
import com.tomodo.freevoice.databinding.ScreenHistoryBinding
import com.tomodo.freevoice.history.HistoryEntry
import com.tomodo.freevoice.history.HistoryRepository

internal class HistoryScreen(
    private val context: Context,
    val binding: ScreenHistoryBinding,
    private val repository: HistoryRepository,
) {
    private val adapter = HistoryAdapter(context, ::copy)

    init {
        binding.historyList.emptyView = binding.historyEmpty
        binding.historyList.adapter = adapter
        binding.historyClearButton.setOnClickListener {
            AlertDialog.Builder(context)
                .setMessage(R.string.history_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ ->
                    repository.clear()
                    refresh()
                }
                .show()
        }
    }

    fun refresh() {
        adapter.replace(repository.list())
        binding.historyClearButton.isEnabled = adapter.count > 0
    }

    private fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.history_title), text))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

private class HistoryAdapter(
    private val context: Context,
    private val onCopy: (String) -> Unit,
) : BaseAdapter() {
    private var entries = emptyList<HistoryEntry>()

    fun replace(next: List<HistoryEntry>) {
        entries = next
        notifyDataSetChanged()
    }

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): HistoryEntry = entries[position]
    override fun getItemId(position: Int): Long = getItem(position).timestamp

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView?.let(ItemHistoryBinding::bind)
            ?: ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val entry = getItem(position)
        row.status.setText(if (entry.success) R.string.history_success else R.string.history_failure)
        row.status.setTextColor(context.getColor(if (entry.success) R.color.fv_primary else R.color.fv_error))
        row.time.text = DateUtils.getRelativeTimeSpanString(
            entry.timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        row.text.text = entry.text
        row.text.isVisible = entry.text.isNotBlank()
        row.error.text = entry.error.orEmpty()
        row.error.isVisible = !entry.error.isNullOrBlank()
        row.copyButton.isVisible = entry.text.isNotBlank()
        row.copyButton.setOnClickListener { onCopy(entry.text) }
        return row.root
    }

    private var View.isVisible: Boolean
        get() = visibility == View.VISIBLE
        set(value) {
            visibility = if (value) View.VISIBLE else View.GONE
        }
}
