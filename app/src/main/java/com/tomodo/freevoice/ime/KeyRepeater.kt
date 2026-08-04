package com.tomodo.freevoice.ime

import android.annotation.SuppressLint
import android.os.Handler
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration

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
 *
 * ViewConfiguration.getLongPressTimeout() は長押しメニューを出すための閾値で、
 * ユーザー補助の設定次第で1秒以上になる。ソフトキーのオートリピートには長すぎるので、
 * 一般的な IME と同じくキーボード側の固定値を使う。
 */
internal fun keyRepeatDelayMillis(repeats: Int): Long = when {
    repeats <= 0 -> 400L
    repeats < 10 -> 60L
    else -> 25L
}

/** タッチがキーの上にあるか。指の微動で止まらないよう slop ぶん外側まで許す。 */
internal fun isInsideKey(x: Float, y: Float, width: Int, height: Int, slop: Int): Boolean =
    x >= -slop && y >= -slop && x <= width + slop && y <= height + slop

/**
 * 長押しでリピートするキーとして配線する。押下判定と時間の進行をここに閉じ込め、
 * 呼び出し側は「押している間なにを繰り返すか」だけを渡す。
 *
 * タッチを消費するので View 既定のクリック経路（押下表示・キー音・performClick）は
 * 通らない。押下表示とキー音はここで出し、TalkBack などがタッチを介さずに叩く
 * performClick 用に OnClickListener も残す。キー音は押した1回だけ鳴らす
 * （リピートごとに鳴らすと連打音になる）。
 *
 * 戻り値の KeyRepeater は、指が離れないまま入力ビューが消える場合に
 * 呼び出し側から止めるために返す。
 */
@SuppressLint("ClickableViewAccessibility")
internal fun View.bindAsRepeatingKey(handler: Handler, onRepeat: () -> Unit): KeyRepeater {
    val repeater = KeyRepeater(
        onRepeat = onRepeat,
        schedule = { delayMillis, action -> handler.postDelayed(action, delayMillis) },
        unschedule = { action -> handler.removeCallbacks(action) },
    )
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    setOnClickListener { onRepeat() }
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                view.playSoundEffect(SoundEffectConstants.CLICK)
                repeater.press()
            }
            MotionEvent.ACTION_MOVE ->
                if (!isInsideKey(event.x, event.y, view.width, view.height, slop)) {
                    view.isPressed = false
                    repeater.release()
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                repeater.release()
            }
        }
        true
    }
    return repeater
}
