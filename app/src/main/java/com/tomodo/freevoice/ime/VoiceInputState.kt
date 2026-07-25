package com.tomodo.freevoice.ime

/** Voice-input job state. A single controller owns every transition. */
sealed class VoiceInputState {
    data object Idle : VoiceInputState()
    data object Starting : VoiceInputState()
    data class Recording(val startedAtElapsedMillis: Long) : VoiceInputState()
    data object Transcribing : VoiceInputState()
    data object Formatting : VoiceInputState()
    data class Error(val message: String) : VoiceInputState()
}

internal enum class MicTapAction { Start, Stop, Ignore }

internal fun VoiceInputState.micTapAction(): MicTapAction = when (this) {
    VoiceInputState.Idle, is VoiceInputState.Error -> MicTapAction.Start
    is VoiceInputState.Recording -> MicTapAction.Stop
    VoiceInputState.Starting, VoiceInputState.Transcribing, VoiceInputState.Formatting -> MicTapAction.Ignore
}
