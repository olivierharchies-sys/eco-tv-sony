package com.example.braviaeco

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

/** Palette et fabriques de vues partagées (thème sombre). */
object Ui {
    val BG: Int = Color.parseColor("#0E1014")
    val SURFACE: Int = Color.parseColor("#1B1F27")
    val SURFACE_HI: Int = Color.parseColor("#2A2F3A")
    val ACCENT: Int = Color.parseColor("#34C77B")
    val TEXT: Int = Color.parseColor("#ECEFF4")
    val DIM: Int = Color.parseColor("#98A2B3")

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** Fond arrondi avec effet de vague au toucher. */
    fun rounded(ctx: Context, color: Int, radiusDp: Int): Drawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(ctx, radiusDp).toFloat()
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = dp(ctx, radiusDp).toFloat()
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), shape, mask)
    }

    /** Bouton « pilule » : un TextView cliquable au style de l'appli. */
    fun pill(ctx: Context, text: String, textSp: Float = 14f): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(TEXT)
        t.textSize = textSp
        t.gravity = Gravity.CENTER
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.background = rounded(ctx, SURFACE, 18)
        t.isClickable = true
        t.setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
        return t
    }

    /** Appui prolongé = répétition (volume, chaînes). fire(false) au premier appui, fire(true) ensuite. */
    fun holdRepeat(v: View, fire: (Boolean) -> Unit) {
        val loop = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                fire(true)
                loop.postDelayed(this, 140)
            }
        }
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.drawableHotspotChanged(event.x, event.y)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    fire(false)
                    loop.postDelayed(ticker, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    loop.removeCallbacks(ticker)
                }
            }
            true
        }
    }

    fun toast(ctx: Context, message: String) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Bouton rond marche/arrêt : vert si la TV est allumée, gris sinon. */
class PowerButton(context: Context) : View(context) {

    var on: Boolean? = null
        set(value) {
            field = value
            invalidate()
        }

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        disc.color = Ui.SURFACE
        shade.color = Color.parseColor("#33FFFFFF")
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = Ui.dp(context, 3).toFloat()
        isClickable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f

        canvas.drawCircle(cx, cy, r, disc)
        if (isPressed) canvas.drawCircle(cx, cy, r, shade)

        stroke.color = if (on == true) Ui.ACCENT else Ui.DIM
        val ir = r * 0.34f
        val oy = cy + r * 0.05f
        rect.set(cx - ir, oy - ir, cx + ir, oy + ir)
        canvas.drawArc(rect, 300f, 300f, false, stroke)
        canvas.drawLine(cx, oy - ir * 1.2f, cx, oy - ir * 0.1f, stroke)
    }
}
