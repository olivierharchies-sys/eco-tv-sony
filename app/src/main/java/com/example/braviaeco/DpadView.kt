package com.example.braviaeco

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Croix directionnelle ronde : 4 secteurs (haut, droite, bas, gauche) autour d'un bouton OK central.
 * [onKey] reçoit le nom de la touche et true si c'est une répétition (appui prolongé sur une flèche).
 */
class DpadView(
    context: Context,
    private val onKey: (key: String, repeat: Boolean) -> Unit
) : View(context) {

    private val loop = Handler(Looper.getMainLooper())

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val tri = Path()
    private var zone = NONE

    private val ticker = object : Runnable {
        override fun run() {
            fire(true)
            loop.postDelayed(this, 140)
        }
    }

    init {
        ringPaint.color = Ui.SURFACE
        hiPaint.color = Color.parseColor("#3A4150")
        sepPaint.color = Ui.BG
        sepPaint.style = Paint.Style.STROKE
        sepPaint.strokeWidth = Ui.dp(context, 3).toFloat()
        glyphPaint.color = Ui.TEXT
        textPaint.color = Ui.BG
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (h in 1 until w) h else w
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - Ui.dp(context, 2)
        val inner = outer * INNER_RATIO

        rect.set(cx - outer, cy - outer, cx + outer, cy + outer)
        canvas.drawCircle(cx, cy, outer, ringPaint)

        if (zone in UP..LEFT) {
            val start = when (zone) {
                UP -> 225f
                RIGHT -> 315f
                DOWN -> 45f
                else -> 135f
            }
            canvas.drawArc(rect, start, 90f, true, hiPaint)
        }

        for (a in intArrayOf(45, 135, 225, 315)) {
            val rad = Math.toRadians(a.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            canvas.drawLine(cx + dx * inner, cy + dy * inner, cx + dx * outer, cy + dy * outer, sepPaint)
        }

        okPaint.color = if (zone == CENTER) Color.parseColor("#2AA866") else Ui.ACCENT
        canvas.drawCircle(cx, cy, inner, okPaint)

        val mid = (outer + inner) / 2f
        val s = outer * 0.075f
        arrow(canvas, cx, cy - mid, s, 0f)
        arrow(canvas, cx + mid, cy, s, 90f)
        arrow(canvas, cx, cy + mid, s, 180f)
        arrow(canvas, cx - mid, cy, s, 270f)

        textPaint.textSize = inner * 0.55f
        canvas.drawText("OK", cx, cy + textPaint.textSize * 0.35f, textPaint)
    }

    private fun arrow(canvas: Canvas, x: Float, y: Float, s: Float, rotation: Float) {
        tri.reset()
        tri.moveTo(x, y - s)
        tri.lineTo(x - s, y + s * 0.7f)
        tri.lineTo(x + s, y + s * 0.7f)
        tri.close()
        canvas.save()
        canvas.rotate(rotation, x, y)
        canvas.drawPath(tri, glyphPaint)
        canvas.restore()
    }

    private fun zoneAt(x: Float, y: Float): Int {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f
        val dx = x - cx
        val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > outer) return NONE
        if (dist <= outer * INNER_RATIO) return CENTER

        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        if (angle < 0) angle += 360.0
        return when {
            angle >= 315.0 || angle < 45.0 -> RIGHT
            angle < 135.0 -> DOWN
            angle < 225.0 -> LEFT
            else -> UP
        }
    }

    private fun fire(repeat: Boolean) {
        val key = when (zone) {
            UP -> "Up"
            RIGHT -> "Right"
            DOWN -> "Down"
            LEFT -> "Left"
            CENTER -> "Confirm"
            else -> return
        }
        onKey(key, repeat)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val z = zoneAt(event.x, event.y)
                if (z == NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                zone = z
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                fire(false)
                if (z != CENTER) loop.postDelayed(ticker, 450)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                loop.removeCallbacks(ticker)
                zone = NONE
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loop.removeCallbacks(ticker)
    }

    private companion object {
        const val NONE = 0
        const val UP = 1
        const val RIGHT = 2
        const val DOWN = 3
        const val LEFT = 4
        const val CENTER = 5
        const val INNER_RATIO = 0.38f
    }
}
