package com.example.braviaeco

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Surface de gestes de la télécommande « Zen », pensée pour être utilisée sans regarder le téléphone :
 * - au centre : glisser = naviguer, toucher = OK, deux doigts = Retour, appui long = Options ;
 * - bande de droite : glisser = volume, toucher = muet ;
 * - bande de gauche : glisser = chaînes, toucher = pavé numérique.
 * [onAction] reçoit le nom de la touche (ou [NUMPAD]).
 */
class GesturePadView(
    context: Context,
    private val onAction: (String) -> Unit
) : View(context) {

    private val loop = Handler(Looper.getMainLooper())

    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val stripWidth = Ui.dp(context, 58).toFloat()
    private val gap = Ui.dp(context, 8).toFloat()
    private val radius = Ui.dp(context, 26).toFloat()
    private val navStep = Ui.dp(context, 44).toFloat()
    private val volumeStep = Ui.dp(context, 24).toFloat()
    private val channelStep = Ui.dp(context, 64).toFloat()
    private val slop = Ui.dp(context, 10).toFloat()

    private var zone = CENTER
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var multi = false
    private var longFired = false

    private var feedback = ""
    private var feedbackUntil = 0L

    private val longPress = Runnable {
        if (!moved && !multi) {
            longFired = true
            emit("Options")
        }
    }

    init {
        padPaint.color = Ui.SURFACE
        stripPaint.color = Ui.SURFACE_HI
        hintPaint.color = Ui.DIM
        hintPaint.textAlign = Paint.Align.CENTER
        hintPaint.textSize = Ui.dp(context, 12).toFloat()
        labelPaint.color = Ui.DIM
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = Ui.dp(context, 12).toFloat()
        labelPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        signPaint.color = Ui.TEXT
        signPaint.textAlign = Paint.Align.CENTER
        signPaint.textSize = Ui.dp(context, 24).toFloat()
        feedbackPaint.color = Ui.ACCENT
        feedbackPaint.textAlign = Paint.Align.CENTER
        feedbackPaint.textSize = Ui.dp(context, 44).toFloat()
        feedbackPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        isClickable = true
    }

    private fun emit(action: String) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val label = LABELS[action]
        if (label != null) {
            feedback = label
            feedbackUntil = SystemClock.uptimeMillis() + 450
            invalidate()
            postInvalidateDelayed(470)
        }
        onAction(action)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        drawStrip(canvas, 0f, stripWidth, h, "CH")
        drawStrip(canvas, w - stripWidth, w, h, "VOL")

        rect.set(stripWidth + gap, 0f, w - stripWidth - gap, h)
        canvas.drawRoundRect(rect, radius, radius, padPaint)

        val cx = w / 2f
        if (SystemClock.uptimeMillis() < feedbackUntil) {
            canvas.drawText(feedback, cx, h / 2f + feedbackPaint.textSize * 0.35f, feedbackPaint)
        }
        val line = hintPaint.textSize * 1.5f
        canvas.drawText("Glisse : naviguer   ·   Touche : OK", cx, h - line * 2.2f, hintPaint)
        canvas.drawText("2 doigts : Retour   ·   Appui long : Options", cx, h - line * 1.2f, hintPaint)
    }

    private fun drawStrip(canvas: Canvas, left: Float, right: Float, h: Float, label: String) {
        rect.set(left, 0f, right, h)
        canvas.drawRoundRect(rect, radius, radius, stripPaint)
        val cx = (left + right) / 2f
        canvas.drawText("+", cx, signPaint.textSize * 1.6f, signPaint)
        canvas.drawText(label, cx, h / 2f + labelPaint.textSize * 0.35f, labelPaint)
        canvas.drawText("−", cx, h - signPaint.textSize * 0.9f, signPaint)
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
                multi = false
                longFired = false
                zone = when {
                    event.x < stripWidth -> LEFT
                    event.x > width - stripWidth -> RIGHT
                    else -> CENTER
                }
                if (zone == CENTER) loop.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                loop.removeCallbacks(longPress)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    loop.removeCallbacks(longPress)
                }
                if (!longFired && !multi) track(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                loop.removeCallbacks(longPress)
                val still = !moved && abs(event.x - downX) < slop && abs(event.y - downY) < slop
                if (!longFired && still) {
                    if (multi) {
                        emit("Return")
                    } else if (zone == CENTER) {
                        emit("Confirm")
                    } else if (zone == RIGHT) {
                        emit("Mute")
                    } else {
                        emit(NUMPAD)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> loop.removeCallbacks(longPress)
        }
        return true
    }

    /** Transforme le déplacement du doigt en crans : flèches, volume ou chaînes selon la zone. */
    private fun track(x: Float, y: Float) {
        val dx = x - lastX
        val dy = y - lastY
        if (zone == CENTER) {
            if (abs(dx) >= navStep || abs(dy) >= navStep) {
                if (abs(dx) > abs(dy)) {
                    emit(if (dx > 0) "Right" else "Left")
                    lastX += if (dx > 0) navStep else -navStep
                    lastY = y
                } else {
                    emit(if (dy > 0) "Down" else "Up")
                    lastY += if (dy > 0) navStep else -navStep
                    lastX = x
                }
                moved = true
            }
        } else {
            val step = if (zone == RIGHT) volumeStep else channelStep
            if (abs(dy) >= step) {
                val up = dy < 0
                if (zone == RIGHT) {
                    emit(if (up) "VolumeUp" else "VolumeDown")
                } else {
                    emit(if (up) "ChannelUp" else "ChannelDown")
                }
                lastY += if (up) -step else step
                moved = true
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loop.removeCallbacks(longPress)
    }

    companion object {
        const val NUMPAD = "@numpad"

        private const val CENTER = 0
        private const val LEFT = 1
        private const val RIGHT = 2

        private val LABELS: Map<String, String> = mapOf(
            "Up" to "▲", "Down" to "▼", "Left" to "◀", "Right" to "▶",
            "Confirm" to "OK", "Return" to "Retour", "Options" to "Options",
            "VolumeUp" to "Vol +", "VolumeDown" to "Vol −", "Mute" to "Muet",
            "ChannelUp" to "CH +", "ChannelDown" to "CH −"
        )
    }
}
