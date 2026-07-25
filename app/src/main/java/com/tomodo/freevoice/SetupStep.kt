package com.tomodo.freevoice

internal enum class SetupStep {
    MICROPHONE,
    ENABLE_IME,
    SELECT_IME,
    COMPLETE,
}

internal fun nextSetupStep(
    microphoneAllowed: Boolean,
    imeEnabled: Boolean,
    imeSelected: Boolean,
): SetupStep = when {
    !microphoneAllowed -> SetupStep.MICROPHONE
    !imeEnabled -> SetupStep.ENABLE_IME
    !imeSelected -> SetupStep.SELECT_IME
    else -> SetupStep.COMPLETE
}
