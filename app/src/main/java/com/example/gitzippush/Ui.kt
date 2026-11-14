package com.example.gitzippush

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Palette sombre et fabriques de vues. */
object Ui {
    val BG: Int = Color.parseColor("#0E1014")
    val SURFACE: Int = Color.parseColor("#1B1F27")
    val SURFACE_HI: Int = Color.parseColor("#2A2F3A")
    val ACCENT: Int = Color.parseColor("#34C77B")
    val TEXT: Int = Color.parseColor("#ECEFF4")
    val DIM: Int = Color.parseColor("#98A2B3")
    val WARN: Int = Color.parseColor("#F5A524")
    val ERROR: Int = Color.parseColor("#FF6B6B")

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int): Drawable {
        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(ctx, radiusDp).toFloat()
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = dp(ctx, radiusDp).toFloat()
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), shape, mask)
    }

    fun params(ctx: Context, topDp: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(ctx, topDp)
        return lp
    }

    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(ctx, SURFACE, 20)
        val p = dp(ctx, 16)
        c.setPadding(p, p, p, p)
        return c
    }

    fun heading(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(TEXT)
        t.textSize = 17f
        t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun note(ctx: Context, text: String = ""): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(DIM)
        t.textSize = 13f
        return t
    }

    fun field(ctx: Context, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setHintTextColor(DIM)
        e.setTextColor(TEXT)
        e.textSize = 15f
        e.setSingleLine(true)
        e.inputType = inputType
        e.background = rounded(ctx, SURFACE_HI, 12)
        val h = dp(ctx, 14)
        val v = dp(ctx, 12)
        e.setPadding(h, v, h, v)
        return e
    }

    /** Bouton : plein vert si [primary], gris sinon. */
    fun button(ctx: Context, text: String, primary: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = text
        t.gravity = Gravity.CENTER
        t.textSize = 15f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(if (primary) BG else TEXT)
        t.background = rounded(ctx, if (primary) ACCENT else SURFACE_HI, 14)
        t.isClickable = true
        val h = dp(ctx, 12)
        t.setPadding(h, 0, h, 0)
        t.minimumHeight = dp(ctx, 48)
        Help.attach(t)
        return t
    }

    /**
     * Champ avec liste déroulante : [items] (local) et [remote] (GitHub, en arrière-plan, facultatif).
     * La liste s'ouvre au toucher ; la saisie libre reste possible.
     */
    fun combo(
        a: android.app.Activity, hint: String, items: () -> List<String>,
        remote: (() -> List<String>)? = null, inputType: Int = InputType.TYPE_CLASS_TEXT
    ): android.widget.AutoCompleteTextView {
        val e = android.widget.AutoCompleteTextView(a)
        e.hint = hint
        e.setHintTextColor(DIM)
        e.setTextColor(TEXT)
        e.textSize = 15f
        e.setSingleLine(true)
        e.inputType = inputType
        e.threshold = 1
        e.background = rounded(a, SURFACE_HI, 12)
        val h = dp(a, 14)
        val v = dp(a, 12)
        e.setPadding(h, v, h, v)
        e.setDropDownBackgroundDrawable(GradientDrawable().also { it.setColor(SURFACE_HI) })
        var extra: List<String> = emptyList()
        fun refill(show: Boolean) {
            val all = LinkedHashSet<String>()
            all.addAll(items().filter { it.isNotBlank() })
            all.addAll(extra)
            val ad = object : android.widget.ArrayAdapter<String>(a, android.R.layout.simple_dropdown_item_1line, ArrayList(all)) {
                override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                    val t = super.getView(position, convertView, parent) as TextView
                    t.setTextColor(TEXT)
                    return t
                }
            }
            e.setAdapter(ad)
            if (show && all.isNotEmpty() && e.windowToken != null) {
                // liste complète, pas seulement ce qui commence par le texte saisi
                ad.filter.filter(null)
                e.showDropDown()
            }
        }
        fun open() {
            refill(true)
            val r = remote ?: return
            Thread {
                val got = try {
                    r()
                } catch (x: Exception) {
                    emptyList()
                }
                if (got.isNotEmpty()) a.runOnUiThread {
                    extra = got
                    if (e.hasFocus()) refill(true)
                }
            }.start()
        }
        e.setOnClickListener { open() }
        e.setOnFocusChangeListener { _, has -> if (has) open() }
        refill(false)
        return e
    }

    /** Ligne de boutons de même largeur. */
    fun row(ctx: Context, topDp: Int, vararg views: android.view.View): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.isBaselineAligned = false
        val gap = dp(ctx, 4)
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(gap, 0, gap, 0)
            r.addView(v, lp)
        }
        r.layoutParams = params(ctx, topDp)
        return r
    }
}
