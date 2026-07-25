package com.tomodo.freevoice

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStepTest {
    @Test
    fun `setup exposes only the first incomplete step`() {
        assertEquals(SetupStep.MICROPHONE, nextSetupStep(false, false, false))
        assertEquals(SetupStep.ENABLE_IME, nextSetupStep(true, false, false))
        assertEquals(SetupStep.SELECT_IME, nextSetupStep(true, true, false))
        assertEquals(SetupStep.COMPLETE, nextSetupStep(true, true, true))
    }

    @Test
    fun `earlier prerequisites win over later state`() {
        assertEquals(SetupStep.MICROPHONE, nextSetupStep(false, true, true))
        assertEquals(SetupStep.ENABLE_IME, nextSetupStep(true, false, true))
    }
}
