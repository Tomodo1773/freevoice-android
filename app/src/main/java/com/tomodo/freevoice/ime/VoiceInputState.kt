package com.tomodo.freevoice.ime

/** UI-visible state.  A single controller owns every transition. */
sealed class VoiceInputState {
    data object Idle : VoiceInputState()
    data object Starting : VoiceInputState()
    data object Recording : VoiceInputState()
    data object Transcribing : VoiceInputState()
    data object Formatting : VoiceInputState()
    data class Error(val message: String) : VoiceInputState()
}
