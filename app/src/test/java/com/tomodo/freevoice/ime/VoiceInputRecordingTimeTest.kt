package com.tomodo.freevoice.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputRecordingTimeTest {
    @Test
    fun `recording state captures injected elapsed clock`() {
        val states = mutableListOf<VoiceInputState>()
        val controller = VoiceInputController(
            sessionFactory = {
                object : VoiceInputController.VoiceSession {
                    override fun start() = Unit
                    override fun finish() = ""
                    override fun cancel() = Unit
                }
            },
            formatter = object : VoiceInputController.Formatter {
                override fun format(text: String, packageName: String) =
                    VoiceInputController.FormattedText(text, false)

                override fun cancel() = Unit
            },
            callbacks = object : VoiceInputController.Callbacks {
                override fun stateChanged(state: VoiceInputState) {
                    states += state
                }

                override fun committed(
                    jobId: Long,
                    text: String,
                    packageName: String,
                    formatFallback: Boolean,
                ) = Unit

                override fun failed(jobId: Long, message: String, error: Throwable?) = Unit
            },
            elapsedRealtimeMillis = { 12_345L },
        )

        controller.start("pkg", null)

        assertEquals(
            listOf(VoiceInputState.Starting, VoiceInputState.Recording(12_345L)),
            states,
        )
        assertEquals(VoiceInputState.Recording(12_345L), controller.currentState())
        controller.close()
    }
}
