package com.misha.holdtranslate

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

internal class ButtonMover(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var originX = 0
    private var originY = 0
    private var touchX = 0f
    private var touchY = 0f
    var dragging = false
        private set

    fun down(params: WindowManager.LayoutParams, event: MotionEvent) {
        dragging = false
        originX = params.x
        originY = params.y
        touchX = event.rawX
        touchY = event.rawY
    }

    fun onMove(event: MotionEvent, view: View, params: WindowManager.LayoutParams): Boolean {
        val dx = event.rawX - touchX
        val dy = event.rawY - touchY
        val started = !dragging && (abs(dx) > slop || abs(dy) > slop)
        if (started) dragging = true
        if (!dragging) return false
        moveTo(view, params, originX + dx.toInt(), originY - dy.toInt())
        return started
    }

    fun save(params: WindowManager.LayoutParams) {
        Prefs.setButtonPosition(context, params.x, params.y)
        dragging = false
    }

    fun clamp(view: View, params: WindowManager.LayoutParams) {
        moveTo(view, params, params.x, params.y)
    }

    private fun moveTo(view: View, params: WindowManager.LayoutParams, x: Int, y: Int) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val maxX = (bounds.width() - width).coerceAtLeast(0)
        val maxY = (bounds.height() - height).coerceAtLeast(0)
        params.x = x.coerceIn(0, maxX)
        params.y = y.coerceIn(0, maxY)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun place(context: Context, params: WindowManager.LayoutParams, defaultX: Int, defaultY: Int) {
            val saved = Prefs.buttonPosition(context)
            if (saved == null) {
                params.x = defaultX
                params.y = defaultY
            } else {
                params.x = saved.first
                params.y = saved.second
            }
        }
    }
}
