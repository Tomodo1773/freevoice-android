package com.tomodo.freevoice.ime

/**
 * キーを押している間、動作を繰り返す。押下・解放の所有者は常に1つで、
 * press() 済みの状態で再び press() しても二重に走らせない。
 *
 * 時間の進め方は呼び出し側の schedule/unschedule に任せ、ここは順序だけを持つ。
 */
internal class KeyRepeater(
    private val onRepeat: () -> Unit,
    private val schedule: (delayMillis: Long, action: Runnable) -> Unit,
    private val unschedule: (action: Runnable) -> Unit,
) {
    private var pressed = false
    private var repeats = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!pressed) return
            repeats += 1
            onRepeat()
            schedule(keyRepeatDelayMillis(repeats), this)
        }
    }

    /** 1回目は即座に実行し、押しっぱなしなら以降を自動で繰り返す。 */
    fun press() {
        if (pressed) return
        pressed = true
        repeats = 0
        onRepeat()
        schedule(keyRepeatDelayMillis(0), tick)
    }

    fun release() {
        if (!pressed) return
        pressed = false
        unschedule(tick)
    }
}

/**
 * 繰り返し n 回目のあと、次の実行までの待ち時間。
 * 最初は長押しと分かるまで待ち、続くほど速くして長文を消しやすくする。
 */
internal fun keyRepeatDelayMillis(repeats: Int): Long = when {
    repeats <= 0 -> 400L
    repeats < 10 -> 60L
    else -> 25L
}
