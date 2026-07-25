package com.tomodo.freevoice

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets

/**
 * システムバーと画面切り欠きの余白を Activity のルートへ一度だけ集約する。
 * XML の基準 padding に毎回加算するため、Insets の再配信でも累積しない。
 */
internal object SystemBarInsets {
    fun apply(window: Window, root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        enableEdgeToEdge(window)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = safeInsets(insets)
            view.setPadding(
                baseLeft + safe.left,
                baseTop + safe.top,
                baseRight + safe.right,
                baseBottom + safe.bottom,
            )
            insets
        }
        root.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun enableEdgeToEdge(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun safeInsets(insets: WindowInsets): SafeInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return SafeInsets(safe.left, safe.top, safe.right, safe.bottom)
        }

        var left = insets.systemWindowInsetLeft
        var top = insets.systemWindowInsetTop
        var right = insets.systemWindowInsetRight
        var bottom = insets.systemWindowInsetBottom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout?.let { cutout ->
                left = maxOf(left, cutout.safeInsetLeft)
                top = maxOf(top, cutout.safeInsetTop)
                right = maxOf(right, cutout.safeInsetRight)
                bottom = maxOf(bottom, cutout.safeInsetBottom)
            }
        }
        return SafeInsets(left, top, right, bottom)
    }

    private data class SafeInsets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )
}
