package com.example.braviaeco

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Pavé tactile : glisser le doigt déplace la sélection (une touche par cran), toucher = OK.
 * [onKey] reçoit le nom de la touche et false (jamais de répétition ignorée).
 */
class TouchpadView(
    context: Context,
    private val onKey: (key: String, repeat: Boolean) -> Unit
) : View(context) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val step = Ui.dp(context, 44).toFloat()
    private val slop = Ui.dp(context, 10).toFloat()
    private val radius = Ui.dp(context, 28).toFloat()
    private val inset = Ui.dp(context, 2).toFloat()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    init {
        bg.color = Ui.SURFACE
        hint.color = Ui.DIM
        hint.textAlign = Paint.Align.CENTER
        hint.textSize = Ui.dp(context, 13).toFloat()
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(rect, radius, radius, bg)
        canvas.drawText("Glisse pour naviguer · touche pour OK", width / 2f, height / 2f, hint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) >= step || abs(dy) >= step) {
                    if (abs(dx) > abs(dy)) {
                        onKey(if (dx > 0) "Right" else "Left", false)
                        lastX += if (dx > 0) step else -step
                        lastY = event.y
                    } else {
                        onKey(if (dy > 0) "Down" else "Up", false)
                        lastY += if (dy > 0) step else -step
                        lastX = event.x
                    }
                    moved = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && abs(event.x - downX) < slop && abs(event.y - downY) < slop) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onKey("Confirm", false)
                }
            }
        }
        return true
    }
}
