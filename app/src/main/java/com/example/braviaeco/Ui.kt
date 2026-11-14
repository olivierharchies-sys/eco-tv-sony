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
    // Thème vert (Sony) par défaut ; la télécommande Edenwood passe en bleu via [useBlueTheme].
    // Les vues lisent ces couleurs à leur construction : appeler [useBlueTheme] avant de les créer.
    var BG: Int = Color.parseColor("#0E1014"); private set
    var SURFACE: Int = Color.parseColor("#1B1F27"); private set
    var SURFACE_HI: Int = Color.parseColor("#2A2F3A"); private set
    var ACCENT: Int = Color.parseColor("#34C77B"); private set

    /** Variante foncée de [ACCENT] (appui sur le bouton OK de la croix). */
    var ACCENT_DARK: Int = Color.parseColor("#2AA866"); private set
    /** Surface appuyée (croix directionnelle). */
    var PRESSED: Int = Color.parseColor("#3A4150"); private set

    /** Facteur d'arrondi des boutons (1 = classique). */
    var ROUND: Float = 1f; private set
    val TEXT: Int = Color.parseColor("#ECEFF4")
    val DIM: Int = Color.parseColor("#98A2B3")

    /** Bascule la palette : bleue pour la télécommande Edenwood, verte pour tout le reste. */
    fun useBlueTheme(blue: Boolean) {
        if (blue) {
            BG = Color.parseColor("#0B1224")
            SURFACE = Color.parseColor("#16213B")
            SURFACE_HI = Color.parseColor("#22305A")
            ACCENT = Color.parseColor("#3D8BFF")
            ACCENT_DARK = Color.parseColor("#2E6FD6")
        } else {
            BG = Color.parseColor("#0E1014")
            SURFACE = Color.parseColor("#1B1F27")
            SURFACE_HI = Color.parseColor("#2A2F3A")
            ACCENT = Color.parseColor("#34C77B")
            ACCENT_DARK = Color.parseColor("#2AA866")
        }
        PRESSED = Color.parseColor("#3A4150")
        ROUND = 1f
    }

    /** Applique le design choisi ; la couleur d'accent dépend de la TV (blue = Edenwood). Renvoie le design. */
    fun useRemoteDesign(ctx: Context, blue: Boolean): Int {
        val d = RemoteDesign.current(ctx)
        useBlueTheme(blue)
        val p = RemoteDesign.palette(d, blue) ?: return d
        BG = Color.parseColor(p[0])
        SURFACE = Color.parseColor(p[1])
        SURFACE_HI = Color.parseColor(p[2])
        PRESSED = Color.parseColor(p[3])
        ACCENT = Color.parseColor(p[4])
        ACCENT_DARK = Color.parseColor(p[5])
        ROUND = RemoteDesign.round(d)
        return d
    }

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** Fond arrondi avec effet de vague au toucher. */
    fun rounded(ctx: Context, color: Int, radiusDp: Int): Drawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(ctx, radiusDp) * ROUND
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = dp(ctx, radiusDp) * ROUND
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

/** Designs de télécommande au choix (couleurs et arrondis ; mêmes boutons). */
object RemoteDesign {
    val NAMES = arrayOf("Classique", "Minuit (noir OLED)", "Ambre", "Néon violet", "Graphite carré")

    // fond, surface, surface haute, appui, accent Sony, accent foncé Sony, accent Edenwood, accent foncé Edenwood
    private val PALETTES = arrayOf(
        null,
        arrayOf("#000000", "#121212", "#1F1F1F", "#2C2C2C", "#00D1FF", "#00A6CC", "#FF4FA3", "#D93A86"),
        arrayOf("#15100B", "#241B13", "#33271C", "#45352A", "#FF9F1C", "#D9820F", "#2EC4B6", "#239E93"),
        arrayOf("#0F0B1A", "#1C1530", "#2A2047", "#3A2C60", "#B26BFF", "#8F4DE0", "#7CFF6B", "#5FD94F"),
        arrayOf("#121314", "#232527", "#313437", "#43474B", "#E5484D", "#C23A3F", "#3D8BFF", "#2E6FD6")
    )
    private val ROUNDS = floatArrayOf(1f, 1f, 1f, 1.35f, 0.3f)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    fun current(ctx: Context): Int = sp(ctx).getInt("remote_design", 0).coerceIn(0, NAMES.size - 1)

    fun save(ctx: Context, design: Int) {
        sp(ctx).edit().putInt("remote_design", design).apply()
    }

    /** Palette du design pour la TV donnée (edenwood = true), ou null pour Classique. */
    fun palette(design: Int, edenwood: Boolean): Array<String>? {
        val p = PALETTES.getOrNull(design) ?: return null
        return if (edenwood) arrayOf(p[0], p[1], p[2], p[3], p[6], p[7]) else p.copyOfRange(0, 6)
    }

    /** Couleur d'accent de la télécommande d'une TV (bouton de bascule vers elle). */
    fun accent(ctx: Context, edenwood: Boolean): Int {
        val p = palette(current(ctx), edenwood)
        return android.graphics.Color.parseColor(p?.get(4) ?: if (edenwood) "#3D8BFF" else "#34C77B")
    }

    fun round(design: Int): Float = ROUNDS.getOrElse(design) { 1f }
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
