package com.tomodo.freevoice.ime

import com.tomodo.freevoice.R

/**
 * ステータス行に何を出すかだけを表す。文言の解決と View への反映は
 * Context を持つ [ImeKeyboardUi] に任せ、判断はここに閉じてテストできるようにする。
 */
internal data class ImeStatusLine(
    /** 録音中だけ経過時間。null は非表示（空文字にすると margin だけが残る）。 */
    val timer: String?,
    val message: ImeStatusMessage,
    val indicatorColorRes: Int,
)

internal sealed interface ImeStatusMessage {
    /** 案内・状態文言。頭から読ませたいので、溢れたら末尾を省略する。 */
    data class Guidance(val textRes: Int) : ImeStatusMessage

    /** エラー本文。案内と同じく頭から読ませる。 */
    data class Failure(val body: String) : ImeStatusMessage

    /** 認識中のテキスト。いま話した末尾を残したいので、先頭側を省略する。 */
    data class Interim(val body: String) : ImeStatusMessage
}

/**
 * 状態と認識テキストから、ステータス行の中身を決める。
 * 経過時間を本文と別に持つのは、同じ TextView だと先頭省略で時間ごと消えるため。
 */
internal fun statusLineFor(
    state: ImeKeyboardUiState,
    interimText: String,
    nowMillis: Long,
): ImeStatusLine = when (state.statusKind) {
    ImeStatusKind.IDLE -> guidance(R.string.ime_idle, R.color.ime_status_idle)
    ImeStatusKind.STARTING -> guidance(R.string.ime_starting, R.color.ime_status_processing)
    ImeStatusKind.TRANSCRIBING -> guidance(R.string.ime_transcribing, R.color.ime_status_processing)
    ImeStatusKind.FORMATTING -> guidance(R.string.ime_formatting, R.color.ime_status_processing)
    ImeStatusKind.ERROR -> ImeStatusLine(
        timer = null,
        message = ImeStatusMessage.Failure(state.errorMessage.orEmpty()),
        indicatorColorRes = R.color.ime_error,
    )
    ImeStatusKind.RECORDING -> recordingStatusLine(state, interimText, nowMillis)
}

private fun guidance(textRes: Int, indicatorColorRes: Int) = ImeStatusLine(
    timer = null,
    message = ImeStatusMessage.Guidance(textRes),
    indicatorColorRes = indicatorColorRes,
)

private fun recordingStatusLine(
    state: ImeKeyboardUiState,
    interimText: String,
    nowMillis: Long,
): ImeStatusLine {
    val elapsed = (
        nowMillis - requireNotNull(state.recordingStartedAtMillis)
    ).coerceAtLeast(0L)
    val interim = interimText.takeLast(INTERIM_SAFETY_CAP).trim()
    return ImeStatusLine(
        timer = formatRecordingElapsed(elapsed),
        // 最初の認識結果が届くまでも、空白だけが届いたときも、行を空にしない。
        message = if (interim.isEmpty()) {
            ImeStatusMessage.Guidance(R.string.ime_listening)
        } else {
            ImeStatusMessage.Interim(interim)
        },
        indicatorColorRes = R.color.ime_status_recording,
    )
}

/** 末尾の見せ方は TextView の ellipsize(START) に任せる。ここは描画コスト対策の上限。 */
private const val INTERIM_SAFETY_CAP = 50
