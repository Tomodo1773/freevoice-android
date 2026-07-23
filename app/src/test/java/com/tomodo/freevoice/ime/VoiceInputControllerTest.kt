package com.tomodo.freevoice.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceInputControllerTest {
    private class Recorder(private val files: ArrayDeque<File>) : VoiceInputController.Recorder {
        var cancelled = 0
        override fun start() = Unit
        override fun stop() = files.removeFirst()
        override fun cancel() { cancelled++ }
    }
    private class Callbacks : VoiceInputController.Callbacks {
        val committed = mutableListOf<Pair<Long, String>>(); val done = CountDownLatch(1); val failures = mutableListOf<Pair<Long, String>>()
        override fun stateChanged(state: VoiceInputState) = Unit
        override fun committed(jobId: Long, text: String, packageName: String, formatFallback: Boolean) { committed += jobId to "$packageName:$text"; done.countDown() }
        override fun failed(jobId: Long, message: String, error: Throwable?) { failures += jobId to message; done.countDown() }
    }
    private fun wav(): File = File.createTempFile("voice", ".wav").apply { writeBytes(ByteArray(45)) }

    @Test fun `normal job transcribes formats and commits`() {
        val callbacks = Callbacks()
        val controller = VoiceInputController(Recorder(ArrayDeque(listOf(wav()))), object : VoiceInputController.Gateway {
            override fun transcribe(wav: File) = " raw "; override fun format(text: String, packageName: String) = VoiceInputController.FormattedText(" formatted ", false); override fun cancel() = Unit
        }, callbacks)
        val jobId = controller.start("pkg", null)
        assertNotNull(jobId); controller.stop()
        assertTrue(callbacks.done.await(2, TimeUnit.SECONDS)); assertEquals(listOf(jobId!! to "pkg:formatted"), callbacks.committed); controller.close()
    }
    @Test fun `validation error does not start recorder`() {
        val callbacks = Callbacks(); val controller = VoiceInputController(Recorder(ArrayDeque()), object : VoiceInputController.Gateway {
            override fun transcribe(wav: File) = ""; override fun format(text: String, packageName: String) = VoiceInputController.FormattedText("", false); override fun cancel() = Unit
        }, callbacks)
        assertNull(controller.start("pkg", "invalid")); assertTrue(controller.currentState() is VoiceInputState.Error); controller.close()
    }
    @Test fun `second start is rejected while a recording owns the job`() {
        val callbacks = Callbacks(); val recorder = Recorder(ArrayDeque(listOf(wav())))
        val controller = VoiceInputController(recorder, object : VoiceInputController.Gateway {
            override fun transcribe(wav: File) = ""; override fun format(text: String, packageName: String) = VoiceInputController.FormattedText("", false); override fun cancel() = Unit
        }, callbacks)
        assertNotNull(controller.start("pkg", null)); assertNull(controller.start("other", null)); controller.cancel(); controller.close()
    }
    @Test fun `cancel then immediate restart suppresses old job commit`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val callbacks = Callbacks()
        val controller = VoiceInputController(Recorder(ArrayDeque(listOf(wav(), wav()))), object : VoiceInputController.Gateway {
            var calls = 0
            override fun transcribe(wav: File): String { calls++; if (calls == 1) { entered.countDown(); release.await(2, TimeUnit.SECONDS); return "old" }; return "new" }
            override fun format(text: String, packageName: String) = VoiceInputController.FormattedText(text, false)
            override fun cancel() = Unit
        }, callbacks)
        val oldJobId = controller.start("old", null); assertNotNull(oldJobId); controller.stop(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        controller.cancel(); val newJobId = controller.start("new", null); assertNotNull(newJobId); controller.stop(); release.countDown()
        assertTrue(callbacks.done.await(2, TimeUnit.SECONDS)); assertEquals(listOf(newJobId!! to "new:new"), callbacks.committed); assertFalse(callbacks.committed.any { it.first == oldJobId }); controller.close()
    }

    @Test fun `mic tap starts stops or ignores according to state`() {
        assertEquals(MicTapAction.Start, VoiceInputState.Idle.micTapAction())
        assertEquals(MicTapAction.Start, VoiceInputState.Error("retry").micTapAction())
        assertEquals(MicTapAction.Stop, VoiceInputState.Recording.micTapAction())
        assertEquals(MicTapAction.Ignore, VoiceInputState.Starting.micTapAction())
        assertEquals(MicTapAction.Ignore, VoiceInputState.Transcribing.micTapAction())
        assertEquals(MicTapAction.Ignore, VoiceInputState.Formatting.micTapAction())
    }
}
