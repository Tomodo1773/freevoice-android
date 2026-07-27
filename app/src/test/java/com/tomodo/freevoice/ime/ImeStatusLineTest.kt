package com.tomodo.freevoice.ime

import com.tomodo.freevoice.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeStatusLineTest {
    @Test
    fun `idle shows guidance without a timer`() {
        val line = statusLineFor(state(ImeStatusKind.IDLE), interimText = "", nowMillis = 0L)

        assertNull(line.timer)
        assertEquals(ImeStatusMessage.Guidance(R.string.ime_idle), line.message)
        assertEquals(R.color.ime_status_idle, line.indicatorColorRes)
    }

    @Test
    fun `processing states share the processing indicator and hide the timer`() {
        listOf(
            ImeStatusKind.STARTING to R.string.ime_starting,
            ImeStatusKind.TRANSCRIBING to R.string.ime_transcribing,
            ImeStatusKind.FORMATTING to R.string.ime_formatting,
        ).forEach { (kind, textRes) ->
            val line = statusLineFor(state(kind), interimText = "", nowMillis = 0L)

            assertNull(line.timer)
            assertEquals(ImeStatusMessage.Guidance(textRes), line.message)
            assertEquals(R.color.ime_status_processing, line.indicatorColorRes)
        }
    }

    @Test
    fun `error shows its own message`() {
        val line = statusLineFor(
            state(ImeStatusKind.ERROR, errorMessage = "マイク権限を許可して"),
            interimText = "",
            nowMillis = 0L,
        )

        assertNull(line.timer)
        assertEquals(ImeStatusMessage.Failure("マイク権限を許可して"), line.message)
        assertEquals(R.color.ime_error, line.indicatorColorRes)
    }

    @Test
    fun `recording shows the elapsed time next to the recognized text`() {
        val line = statusLineFor(recording(startedAtMillis = 1_000L), "今日の会議の件", nowMillis = 91_000L)

        assertEquals("01:30", line.timer)
        assertEquals(ImeStatusMessage.Interim("今日の会議の件"), line.message)
        assertEquals(R.color.ime_status_recording, line.indicatorColorRes)
    }

    @Test
    fun `recording keeps the message line filled until the first result arrives`() {
        val line = statusLineFor(recording(), interimText = "", nowMillis = 0L)

        assertEquals("00:00", line.timer)
        assertEquals(ImeStatusMessage.Guidance(R.string.ime_listening), line.message)
    }

    @Test
    fun `blank recognition never leaves the message line empty`() {
        val line = statusLineFor(recording(), interimText = "   \n ", nowMillis = 0L)

        assertEquals(ImeStatusMessage.Guidance(R.string.ime_listening), line.message)
    }

    @Test
    fun `only the newest part of a long transcript is handed to the view`() {
        val transcript = (1..40).joinToString("") { "あいうえお" }

        val line = statusLineFor(recording(), transcript, nowMillis = 0L)

        val interim = line.message as ImeStatusMessage.Interim
        assertEquals(50, interim.body.length)
        assertEquals(transcript.takeLast(50), interim.body)
    }

    @Test
    fun `a clock that runs backwards does not produce a negative timer`() {
        val line = statusLineFor(recording(startedAtMillis = 5_000L), interimText = "", nowMillis = 1_000L)

        assertEquals("00:00", line.timer)
    }

    private fun state(
        kind: ImeStatusKind,
        errorMessage: String? = null,
        recordingStartedAtMillis: Long? = null,
    ) = ImeKeyboardUiState(
        statusKind = kind,
        errorMessage = errorMessage,
        primaryAction = ImePrimaryAction.START,
        primaryEnabled = true,
        cancelVisible = false,
        recordingStartedAtMillis = recordingStartedAtMillis,
    )

    private fun recording(startedAtMillis: Long = 0L) =
        state(ImeStatusKind.RECORDING, recordingStartedAtMillis = startedAtMillis)
}
