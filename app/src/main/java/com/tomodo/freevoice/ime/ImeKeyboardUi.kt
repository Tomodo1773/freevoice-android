package com.tomodo.freevoice.ime

import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import com.tomodo.freevoice.R
import com.tomodo.freevoice.databinding.ImeFreevoiceKeyboardBinding
import java.util.Locale

internal class ImeKeyboardUi(
    private val binding: ImeFreevoiceKeyboardBinding,
    private val actions: Actions,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    interface Actions {
        fun onMic()
        fun onCancel()
        fun onSwitchKeyboard()
        fun onOpenSettings()
        fun onSpace()
        fun onDelete()
        fun onEnter()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var state: ImeKeyboardUiState? = null
    private val recordingTick = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1_000L)
        }
    }

    init {
        applyNavigationBarInset()
        binding.imeMic.setOnClickListener { actions.onMic() }
        binding.imeCancel.setOnClickListener { actions.onCancel() }
        binding.imeSwitch.setOnClickListener { actions.onSwitchKeyboard() }
        binding.imeSettings.setOnClickListener { actions.onOpenSettings() }
        binding.imeSpace.setOnClickListener { actions.onSpace() }
        binding.imeDelete.setOnClickListener { actions.onDelete() }
        binding.imeEnter.setOnClickListener { actions.onEnter() }
    }

    fun render(next: ImeKeyboardUiState) {
        state = next
        handler.removeCallbacks(recordingTick)
        renderStatus()
        renderPrimaryAction(next)
        binding.imeCancel.visibility = if (next.cancelVisible) View.VISIBLE else View.GONE
        if (next.recordingStartedAtMillis != null) handler.postDelayed(recordingTick, 1_000L)
    }

    fun setEnterCommand(command: ImeEnterCommand) {
        binding.imeEnter.contentDescription = binding.root.context.getString(command.descriptionRes())
    }

    fun close() {
        handler.removeCallbacks(recordingTick)
        state = null
    }

    private fun renderStatus() {
        val current = state ?: return
        binding.imeStatus.text = when (current.statusKind) {
            ImeStatusKind.IDLE -> text(R.string.ime_idle)
            ImeStatusKind.STARTING -> text(R.string.ime_starting)
            ImeStatusKind.RECORDING -> {
                val elapsed = (
                    elapsedRealtimeMillis() - requireNotNull(current.recordingStartedAtMillis)
                ).coerceAtLeast(0L)
                text(R.string.ime_recording_time, formatRecordingElapsed(elapsed))
            }
            ImeStatusKind.TRANSCRIBING -> text(R.string.ime_transcribing)
            ImeStatusKind.FORMATTING -> text(R.string.ime_formatting)
            ImeStatusKind.ERROR -> current.errorMessage.orEmpty()
        }
        binding.imeStatusIndicator.setTextColor(
            color(
                when (current.statusKind) {
                    ImeStatusKind.RECORDING -> R.color.ime_status_recording
                    ImeStatusKind.STARTING,
                    ImeStatusKind.TRANSCRIBING,
                    ImeStatusKind.FORMATTING,
                    -> R.color.ime_status_processing
                    ImeStatusKind.ERROR -> R.color.ime_error
                    ImeStatusKind.IDLE -> R.color.ime_status_idle
                },
            ),
        )
    }

    private fun renderPrimaryAction(current: ImeKeyboardUiState) {
        binding.imeMic.isEnabled = current.primaryEnabled
        when (current.primaryAction) {
            ImePrimaryAction.START -> configurePrimary(
                textRes = R.string.ime_record_start,
                iconRes = R.drawable.ic_mic,
                backgroundRes = R.drawable.ime_mic_background,
            )
            ImePrimaryAction.STOP -> configurePrimary(
                textRes = R.string.ime_record_stop,
                iconRes = R.drawable.ic_ime_stop,
                backgroundRes = R.drawable.ime_mic_recording_background,
            )
            ImePrimaryAction.BUSY -> configurePrimary(
                textRes = R.string.ime_processing,
                iconRes = null,
                backgroundRes = R.drawable.ime_mic_background,
            )
            ImePrimaryAction.RETRY -> configurePrimary(
                textRes = R.string.ime_retry,
                iconRes = R.drawable.ic_mic,
                backgroundRes = R.drawable.ime_mic_background,
            )
        }
    }

    private fun configurePrimary(
        textRes: Int,
        iconRes: Int?,
        backgroundRes: Int,
    ) {
        binding.imeMic.setText(textRes)
        binding.imeMic.contentDescription = text(textRes)
        binding.imeMic.setBackgroundResource(backgroundRes)
        binding.imeMic.setCompoundDrawablesRelativeWithIntrinsicBounds(
            iconRes?.let { drawable(it) },
            null,
            null,
            null,
        )
    }

    private fun ImeEnterCommand.descriptionRes(): Int = when (this) {
        ImeEnterCommand.NewLine -> R.string.ime_enter
        is ImeEnterCommand.EditorAction -> when (actionId) {
            android.view.inputmethod.EditorInfo.IME_ACTION_GO -> R.string.ime_enter_go
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> R.string.ime_enter_search
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> R.string.ime_enter_send
            android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> R.string.ime_enter_next
            android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> R.string.ime_enter_done
            android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS -> R.string.ime_enter_previous
            else -> R.string.ime_enter
        }
    }

    private fun text(id: Int, vararg arguments: Any): String =
        binding.root.context.getString(id, *arguments)

    private fun color(id: Int): Int = binding.root.context.getColor(id)
    private fun drawable(id: Int): Drawable? = binding.root.context.getDrawable(id)

    private fun applyNavigationBarInset() {
        val root = binding.root
        val baseBottomPadding = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val navigationBarBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                baseBottomPadding + navigationBarBottom,
            )
            insets
        }
        root.requestApplyInsets()
    }
}

internal fun formatRecordingElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
