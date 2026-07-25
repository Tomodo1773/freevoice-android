package com.tomodo.freevoice.ime

internal enum class SpeechCancelReason { ERROR, END_OF_STREAM, BY_USER }

/**
 * A recognition breakdown, copied out of the SDK's enums so the rules below stay
 * testable on the JVM without loading the native library.
 */
internal data class SpeechCancellation(
    val reason: SpeechCancelReason,
    val errorCode: String,
    val details: String = "",
)

/**
 * Win 版 `transcription.ts` と同じ集合。EndOfStream を含めるのが要点で、マイク入力は
 * 停止するまで終端しないため、録音中の EndOfStream はサービス側がストリームを閉じた
 * 異常を意味する。Win 版ではこれを握り潰していたことが「途中から認識されない」原因だった。
 */
private val RETRYABLE_ERROR_CODES =
    setOf("ConnectionFailure", "ServiceTimeout", "ServiceError", "RuntimeError")

internal fun SpeechCancellation.isRetryable(): Boolean = when (reason) {
    SpeechCancelReason.END_OF_STREAM -> true
    SpeechCancelReason.ERROR -> errorCode in RETRYABLE_ERROR_CODES
    SpeechCancelReason.BY_USER -> false
}

internal fun SpeechCancellation.userMessage(): String = when (errorCode) {
    "AuthenticationFailure", "Forbidden" -> "API キーまたは権限を確認して"
    "ConnectionFailure" -> "音声認識サービスに接続できなかった。通信を確認して"
    "BadRequest" -> "Speech エンドポイントまたは言語設定を確認して"
    "TooManyRequests" -> "API が混み合っている。少し待って再試行して"
    else -> "音声認識が中断された"
}

internal fun SpeechCancellation.summary(): String =
    "reason=$reason code=$errorCode details=$details"
