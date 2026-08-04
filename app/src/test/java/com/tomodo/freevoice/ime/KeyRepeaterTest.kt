package com.tomodo.freevoice.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRepeaterTest {
    /** Handler の代わり。予約は1件だけ持ち、advance() で発火させる。 */
    private class FakeScheduler {
        var pending: Runnable? = null
        val delays = mutableListOf<Long>()

        val schedule: (Long, Runnable) -> Unit = { delay, action ->
            delays += delay
            pending = action
        }
        val unschedule: (Runnable) -> Unit = { action -> if (pending === action) pending = null }

        fun advance(times: Int) {
            repeat(times) { pending?.also { pending = null }?.run() }
        }
    }

    private fun repeater(scheduler: FakeScheduler, onRepeat: () -> Unit) = KeyRepeater(
        onRepeat = onRepeat,
        schedule = scheduler.schedule,
        unschedule = scheduler.unschedule,
    )

    @Test
    fun `press deletes once immediately`() {
        val scheduler = FakeScheduler()
        var repeats = 0
        val keys = repeater(scheduler) { repeats += 1 }

        keys.press()

        assertEquals(1, repeats)
        assertEquals(listOf(keyRepeatDelayMillis(0)), scheduler.delays)
    }

    @Test
    fun `holding repeats until released`() {
        val scheduler = FakeScheduler()
        var repeats = 0
        val keys = repeater(scheduler) { repeats += 1 }

        keys.press()
        scheduler.advance(5)

        assertEquals(6, repeats)

        keys.release()
        scheduler.advance(5)

        assertEquals(6, repeats)
        assertEquals(null, scheduler.pending)
    }

    @Test
    fun `tap without hold deletes exactly one character`() {
        val scheduler = FakeScheduler()
        var repeats = 0
        val keys = repeater(scheduler) { repeats += 1 }

        keys.press()
        keys.release()
        scheduler.advance(3)

        assertEquals(1, repeats)
    }

    @Test
    fun `second press while held does not start a second run`() {
        val scheduler = FakeScheduler()
        var repeats = 0
        val keys = repeater(scheduler) { repeats += 1 }

        keys.press()
        keys.press()

        assertEquals(1, repeats)
        assertEquals(1, scheduler.delays.size)
    }

    @Test
    fun `release without press is ignored and press works again afterwards`() {
        val scheduler = FakeScheduler()
        var repeats = 0
        val keys = repeater(scheduler) { repeats += 1 }

        keys.release()
        keys.press()
        scheduler.advance(2)
        keys.release()
        keys.press()

        assertEquals(4, repeats)
    }

    @Test
    fun `a repeat that fires after release does nothing`() {
        val scheduler = FakeScheduler()
        var repeats = 0
        val keys = repeater(scheduler) { repeats += 1 }

        keys.press()
        val stale = scheduler.pending
        keys.release()
        stale?.run()

        assertEquals(1, repeats)
    }

    @Test
    fun `repeat waits longest before the first repeat and speeds up while held`() {
        val hold = keyRepeatDelayMillis(0)
        val early = keyRepeatDelayMillis(1)
        val late = keyRepeatDelayMillis(20)

        assertTrue(hold > early)
        assertTrue(early > late)
        assertEquals(early, keyRepeatDelayMillis(9))
        assertEquals(late, keyRepeatDelayMillis(10))
    }
}
