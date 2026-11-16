package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Élément de grille : [iconUri] = icône d'appli (voir [AppIcons]), sinon [emoji], sinon l'initiale de [label].
 * [active] = élément en cours (bordure d'accent).
 */
data class GridItem(
    val label: String,
    val emoji: String = "",
    val sub: String = "",
    val iconUri: String = "",
    val active: Boolean = false
)

/** Fenêtre graphique commune : grille de tuiles arrondies (icône au-dessus du nom). */
object TileGrid {

    fun show(
        act: Activity,
        title: String,
        items: List<GridItem>,
        columns: Int = 3,
        neutral: Pair<String, () -> Unit>? = null,
        onPick: (Int) -> Unit
    ) {
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                tile(act, items[position])
        }
        val grid = GridView(act)
        grid.numColumns = columns
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.verticalSpacing = Ui.dp(act, 10)
        grid.horizontalSpacing = Ui.dp(act, 10)
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        val pad = Ui.dp(act, 14)
        grid.setPadding(pad, pad, pad, pad)
        grid.clipToPadding = false
        grid.adapter = adapter

        val builder = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(grid)
            .setNegativeButton("Fermer", null)
        if (neutral != null) builder.setNeutralButton(neutral.first) { _, _ -> neutral.second() }
        val dlg = builder.create()
        grid.setOnItemClickListener { _, v, pos, _ ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            dlg.dismiss()
            onPick(pos)
        }
        dlg.show()

        val uris = items.map { it.iconUri }.filter { it.isNotEmpty() }
        if (uris.isNotEmpty()) AppIcons.prepare(act, uris) { if (!act.isFinishing) adapter.notifyDataSetChanged() }
    }

    /** Icône : image de l'appli, sinon emoji ou initiale sur pastille colorée. */
    fun icon(act: Activity, item: GridItem, sizeDp: Int): View {
        val size = Ui.dp(act, sizeDp)
        val bmp = if (item.iconUri.isEmpty()) null else AppIcons.bitmap(act, item.iconUri)
        if (bmp != null) {
            val image = ImageView(act)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setImageBitmap(bmp)
            image.layoutParams = LinearLayout.LayoutParams(size, size)
            return image
        }
        val t = TextView(act)
        val clean = item.label.trim()
        val bg = GradientDrawable()
        bg.cornerRadius = size * 0.26f * Ui.ROUND
        if (item.emoji.isNotEmpty()) {
            t.text = item.emoji
            t.textSize = sizeDp * 0.44f
            bg.setColor(Ui.SURFACE)
        } else {
            t.text = clean.take(1).uppercase().ifEmpty { "?" }
            t.textSize = sizeDp * 0.42f
            t.setTextColor(Color.WHITE)
            t.typeface = Typeface.DEFAULT_BOLD
            val hue = ((clean.lowercase().hashCode() and 0x7fffffff) % 360).toFloat()
            bg.setColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.62f)))
        }
        t.gravity = Gravity.CENTER
        t.background = bg
        t.layoutParams = LinearLayout.LayoutParams(size, size)
        return t
    }

    private fun tile(act: Activity, item: GridItem): View {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val pad = Ui.dp(act, 8)
        box.setPadding(pad, Ui.dp(act, 12), pad, Ui.dp(act, 10))
        box.background = Ui.rounded(act, Ui.SURFACE_HI, 16)
        if (item.active) {
            box.foreground = GradientDrawable().also {
                it.setStroke(Ui.dp(act, 2), Ui.ACCENT)
                it.cornerRadius = Ui.dp(act, 16) * Ui.ROUND
            }
        }
        box.addView(icon(act, item, 52))

        val label = TextView(act)
        label.text = item.label
        label.setTextColor(Ui.TEXT)
        label.textSize = 13f
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.ellipsize = TextUtils.TruncateAt.END
        label.setPadding(0, Ui.dp(act, 6), 0, 0)
        box.addView(label)

        if (item.sub.isNotEmpty()) {
            val sub = TextView(act)
            sub.text = item.sub
            sub.setTextColor(Ui.DIM)
            sub.textSize = 11f
            sub.gravity = Gravity.CENTER
            sub.maxLines = 1
            sub.ellipsize = TextUtils.TruncateAt.END
            box.addView(sub)
        }
        return box
    }
}
