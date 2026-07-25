package com.tomodo.freevoice.ime

internal enum class ImeStatusKind {
    IDLE,
    STARTING,
    RECORDING,
    TRANSCRIBING,
    FORMATTING,
    ERROR,
}

internal enum class ImePrimaryAction {
    START,
    STOP,
    BUSY,
    RETRY,
}

internal data class ImeKeyboardUiState(
    val statusKind: ImeStatusKind,
    val errorMessage: String? = null,
    val primaryAction: ImePrimaryAction,
    val primaryEnabled: Boolean,
    val cancelVisible: Boolean,
    val recordingStartedAtMillis: Long? = null,
)

internal fun VoiceInputState.toImeKeyboardUiState(): ImeKeyboardUiState = when (this) {
    VoiceInputState.Idle -> ImeKeyboardUiState(
        statusKind = ImeStatusKind.IDLE,
        primaryAction = ImePrimaryAction.START,
        primaryEnabled = true,
        cancelVisible = false,
    )
    VoiceInputState.Starting -> ImeKeyboardUiState(
        statusKind = ImeStatusKind.STARTING,
        primaryAction = ImePrimaryAction.BUSY,
        primaryEnabled = false,
        cancelVisible = true,
    )
    is VoiceInputState.Recording -> ImeKeyboardUiState(
        statusKind = ImeStatusKind.RECORDING,
        primaryAction = ImePrimaryAction.STOP,
        primaryEnabled = true,
        cancelVisible = true,
        recordingStartedAtMillis = startedAtElapsedMillis,
    )
    VoiceInputState.Transcribing -> ImeKeyboardUiState(
        statusKind = ImeStatusKind.TRANSCRIBING,
        primaryAction = ImePrimaryAction.BUSY,
        primaryEnabled = false,
        cancelVisible = true,
    )
    VoiceInputState.Formatting -> ImeKeyboardUiState(
        statusKind = ImeStatusKind.FORMATTING,
        primaryAction = ImePrimaryAction.BUSY,
        primaryEnabled = false,
        cancelVisible = true,
    )
    is VoiceInputState.Error -> ImeKeyboardUiState(
        statusKind = ImeStatusKind.ERROR,
        errorMessage = message,
        primaryAction = ImePrimaryAction.RETRY,
        primaryEnabled = true,
        cancelVisible = false,
    )
}
