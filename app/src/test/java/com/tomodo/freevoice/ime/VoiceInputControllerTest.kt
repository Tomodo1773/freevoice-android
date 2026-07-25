package com.tomodo.freevoice.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceInputControllerTest {
    private class Session(private val finish: () -> String) : VoiceInputController.VoiceSession {
        var cancelled = 0
        override fun start() = Unit
        override fun finish() = finish.invoke()
        override fun cancel() { cancelled++ }
    }
    private class Formatter(
        private val format: (String) -> VoiceInputController.FormattedText = {
            VoiceInputController.FormattedText(it, false)
        },
    ) : VoiceInputController.Formatter {
        override fun format(text: String, packageName: String) = format.invoke(text)
        override fun cancel() = Unit
    }
    private class Callbacks : VoiceInputController.Callbacks {
        val committed = mutableListOf<Pair<Long, String>>(); val done = CountDownLatch(1); val failures = mutableListOf<Pair<Long, String>>()
        override fun stateChanged(state: VoiceInputState) = Unit
        override fun committed(jobId: Long, text: String, packageName: String, formatFallback: Boolean) { committed += jobId to "$packageName:$text"; done.countDown() }
        override fun failed(jobId: Long, message: String, error: Throwable?) { failures += jobId to message; done.countDown() }
    }

    @Test fun `normal job transcribes formats and commits`() {
        val callbacks = Callbacks()
        val controller = VoiceInputController(
            { Session { " raw " } },
            Formatter { VoiceInputController.FormattedText(" formatted ", false) },
            callbacks,
        )
        val jobId = controller.start("pkg", null)
        assertNotNull(jobId); controller.stop()
        assertTrue(callbacks.done.await(2, TimeUnit.SECONDS)); assertEquals(listOf(jobId!! to "pkg:formatted"), callbacks.committed); controller.close()
    }
    @Test fun `validation error does not start a session`() {
        val callbacks = Callbacks(); var created = 0
        val controller = VoiceInputController({ created++; Session { "" } }, Formatter(), callbacks)
        assertNull(controller.start("pkg", "invalid")); assertEquals(0, created)
        assertTrue(controller.currentState() is VoiceInputState.Error); controller.close()
    }
    @Test fun `second start is rejected while a recording owns the job`() {
        val callbacks = Callbacks()
        val controller = VoiceInputController({ Session { "" } }, Formatter(), callbacks)
        assertNotNull(controller.start("pkg", null)); assertNull(controller.start("other", null)); controller.cancel(); controller.close()
    }
    @Test fun `cancel then immediate restart suppresses old job commit`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val callbacks = Callbacks()
        var calls = 0
        val controller = VoiceInputController(
            { Session { calls++; if (calls == 1) { entered.countDown(); release.await(2, TimeUnit.SECONDS); "old" } else "new" } },
            Formatter(),
            callbacks,
        )
        val oldJobId = controller.start("old", null); assertNotNull(oldJobId); controller.stop(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        controller.cancel(); val newJobId = controller.start("new", null); assertNotNull(newJobId); controller.stop(); release.countDown()
        assertTrue(callbacks.done.await(2, TimeUnit.SECONDS)); assertEquals(listOf(newJobId!! to "new:new"), callbacks.committed); assertFalse(callbacks.committed.any { it.first == oldJobId }); controller.close()
    }

    @Test fun `session start failure reports an error without a job`() {
        val callbacks = Callbacks()
        val controller = VoiceInputController(
            { object : VoiceInputController.VoiceSession {
                override fun start() = throw IllegalStateException("Microphone is unavailable")
                override fun finish() = ""
                override fun cancel() = Unit
            } },
            Formatter(),
            callbacks,
        )
        assertNull(controller.start("pkg", null))
        assertTrue(controller.currentState() is VoiceInputState.Error)
        assertTrue(callbacks.committed.isEmpty()); controller.close()
    }

    @Test fun `finish failure surfaces its user-visible message`() {
        val callbacks = Callbacks()
        val controller = VoiceInputController(
            { Session { throw UserVisibleException("音声認識サービスに接続できなかった") } },
            Formatter(),
            callbacks,
        )
        assertNotNull(controller.start("pkg", null)); controller.stop()
        assertTrue(callbacks.done.await(2, TimeUnit.SECONDS))
        assertEquals("音声認識サービスに接続できなかった", (controller.currentState() as VoiceInputState.Error).message)
        assertTrue(callbacks.committed.isEmpty()); controller.close()
    }

    @Test fun `empty final text fails instead of committing`() {
        val callbacks = Callbacks()
        val controller = VoiceInputController({ Session { "   " } }, Formatter(), callbacks)
        assertNotNull(controller.start("pkg", null)); controller.stop()
        assertTrue(callbacks.done.await(2, TimeUnit.SECONDS))
        assertEquals("音声を認識できなかった", (controller.currentState() as VoiceInputState.Error).message)
        assertTrue(callbacks.committed.isEmpty()); controller.close()
    }

    @Test fun `mic tap starts stops or ignores according to state`() {
        assertEquals(MicTapAction.Start, VoiceInputState.Idle.micTapAction())
        assertEquals(MicTapAction.Start, VoiceInputState.Error("retry").micTapAction())
        assertEquals(MicTapAction.Stop, VoiceInputState.Recording(0L).micTapAction())
        assertEquals(MicTapAction.Ignore, VoiceInputState.Starting.micTapAction())
        assertEquals(MicTapAction.Ignore, VoiceInputState.Transcribing.micTapAction())
        assertEquals(MicTapAction.Ignore, VoiceInputState.Formatting.micTapAction())
    }
}
