package com.tomodo.freevoice.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeKeyboardUiStateTest {
    @Test
    fun `idle offers recording without cancellation`() {
        val ui = VoiceInputState.Idle.toImeKeyboardUiState()

        assertEquals(ImeStatusKind.IDLE, ui.statusKind)
        assertEquals(ImePrimaryAction.START, ui.primaryAction)
        assertTrue(ui.primaryEnabled)
        assertFalse(ui.cancelVisible)
        assertNull(ui.recordingStartedAtMillis)
        assertNull(ui.errorMessage)
    }

    @Test
    fun `starting is busy and cancellable`() {
        val ui = VoiceInputState.Starting.toImeKeyboardUiState()

        assertEquals(ImeStatusKind.STARTING, ui.statusKind)
        assertEquals(ImePrimaryAction.BUSY, ui.primaryAction)
        assertFalse(ui.primaryEnabled)
        assertTrue(ui.cancelVisible)
        assertNull(ui.recordingStartedAtMillis)
    }

    @Test
    fun `recording offers stop and preserves elapsed clock origin`() {
        val startedAtMillis = 12_345L

        val ui = VoiceInputState.Recording(startedAtMillis).toImeKeyboardUiState()

        assertEquals(ImeStatusKind.RECORDING, ui.statusKind)
        assertEquals(ImePrimaryAction.STOP, ui.primaryAction)
        assertTrue(ui.primaryEnabled)
        assertTrue(ui.cancelVisible)
        assertEquals(startedAtMillis, ui.recordingStartedAtMillis)
    }

    @Test
    fun `transcribing is busy and cancellable`() {
        val ui = VoiceInputState.Transcribing.toImeKeyboardUiState()

        assertEquals(ImeStatusKind.TRANSCRIBING, ui.statusKind)
        assertEquals(ImePrimaryAction.BUSY, ui.primaryAction)
        assertFalse(ui.primaryEnabled)
        assertTrue(ui.cancelVisible)
        assertNull(ui.recordingStartedAtMillis)
    }

    @Test
    fun `formatting is busy and cancellable`() {
        val ui = VoiceInputState.Formatting.toImeKeyboardUiState()

        assertEquals(ImeStatusKind.FORMATTING, ui.statusKind)
        assertEquals(ImePrimaryAction.BUSY, ui.primaryAction)
        assertFalse(ui.primaryEnabled)
        assertTrue(ui.cancelVisible)
        assertNull(ui.recordingStartedAtMillis)
    }

    @Test
    fun `error exposes message and retry without cancellation`() {
        val ui = VoiceInputState.Error("マイク権限を許可して").toImeKeyboardUiState()

        assertEquals(ImeStatusKind.ERROR, ui.statusKind)
        assertEquals(ImePrimaryAction.RETRY, ui.primaryAction)
        assertTrue(ui.primaryEnabled)
        assertFalse(ui.cancelVisible)
        assertNull(ui.recordingStartedAtMillis)
        assertEquals("マイク権限を許可して", ui.errorMessage)
    }

    @Test
    fun `recording elapsed time is stable at boundaries`() {
        assertEquals("00:00", formatRecordingElapsed(-1L))
        assertEquals("00:00", formatRecordingElapsed(0L))
        assertEquals("00:59", formatRecordingElapsed(59_999L))
        assertEquals("01:00", formatRecordingElapsed(60_000L))
        assertEquals("120:00", formatRecordingElapsed(7_200_000L))
    }
}
