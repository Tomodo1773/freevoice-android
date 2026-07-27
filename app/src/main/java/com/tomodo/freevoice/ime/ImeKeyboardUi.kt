package com.tomodo.freevoice.ime

import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
        fun onOpenSettings()
        fun onSpace()
        fun onDelete()
        fun onEnter()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var state: ImeKeyboardUiState? = null

    /**
     * 認識中のテキスト。制御状態ではなく表示だけの持ち物なので VoiceInputState には
     * 載せず、ここで抱える（ADR 0001: 表示の状態はまず表示側で表現する）。
     */
    private var interimText = ""
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
        binding.imeSettings.setOnClickListener { actions.onOpenSettings() }
        binding.imeSpace.setOnClickListener { actions.onSpace() }
        binding.imeDelete.setOnClickListener { actions.onDelete() }
        binding.imeEnter.setOnClickListener { actions.onEnter() }
    }

    fun render(next: ImeKeyboardUiState) {
        state = next
        if (next.statusKind != ImeStatusKind.RECORDING) interimText = ""
        handler.removeCallbacks(recordingTick)
        renderStatus()
        renderPrimaryAction(next)
        binding.imeCancel.visibility = if (next.cancelVisible) View.VISIBLE else View.GONE
        if (next.recordingStartedAtMillis != null) handler.postDelayed(recordingTick, 1_000L)
    }

    /** Streaming recognition only; the batch provider never reports partial text. */
    fun setInterimText(text: String) {
        if (state?.statusKind != ImeStatusKind.RECORDING) return
        interimText = text
        renderStatus()
    }

    fun setEnterCommand(command: ImeEnterCommand) {
        binding.imeEnter.contentDescription = binding.root.context.getString(command.descriptionRes())
    }

    fun close() {
        handler.removeCallbacks(recordingTick)
        state = null
        interimText = ""
    }

    private fun renderStatus() {
        val current = state ?: return
        val line = statusLineFor(current, interimText, elapsedRealtimeMillis())
        applyTimer(line.timer)
        applyMessage(line.message)
        binding.imeStatusIndicator.setTextColor(color(line.indicatorColorRes))
    }

    private fun applyTimer(timer: String?) {
        if (timer == null) {
            binding.imeTimer.visibility = View.GONE
            return
        }
        binding.imeTimer.visibility = View.VISIBLE
        binding.imeTimer.text = timer
        binding.imeTimer.contentDescription = text(R.string.ime_recording_time, timer)
    }

    private fun applyMessage(message: ImeStatusMessage) {
        val (body, ellipsize) = when (message) {
            is ImeStatusMessage.Guidance -> text(message.textRes) to TextUtils.TruncateAt.END
            is ImeStatusMessage.Failure -> message.body to TextUtils.TruncateAt.END
            is ImeStatusMessage.Interim -> message.body to TextUtils.TruncateAt.START
        }
        // setEllipsize は requestLayout を誘発するので、録音中の毎秒更新で無駄に呼ばない。
        if (binding.imeStatus.ellipsize != ellipsize) binding.imeStatus.ellipsize = ellipsize
        binding.imeStatus.text = body
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

    /**
     * システムのナビゲーションバーとキーが重ならないよう、キーボード本体の下に
     * その高さぶんのスペーサーを積む。本体の padding ではなく外側に足すことで、
     * インセットの変化がキーの高さや配置に影響しないようにする。
     */
    private fun applyNavigationBarInset() {
        val root = binding.root
        root.setOnApplyWindowInsetsListener { _, insets ->
            val navigationBarBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            val spacer = binding.imeNavSpacer
            if (spacer.layoutParams.height != navigationBarBottom) {
                spacer.layoutParams.height = navigationBarBottom
                spacer.requestLayout()
            }
            insets
        }
        root.requestApplyInsets()
    }
}

internal fun formatRecordingElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
