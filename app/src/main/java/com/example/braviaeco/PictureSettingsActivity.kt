package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Tous les réglages d'image que la TV expose par l'API : plage numérique = curseur,
 * liste de valeurs = choix. Chaque modification est envoyée aussitôt à la TV.
 * Ce que la TV n'expose pas (selon le modèle, Motionflow ou Reality Creation) n'apparaît pas ici.
 */
class PictureSettingsActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 16)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(pad, pad * 2, pad, 0)

        val title = TextView(this)
        title.text = "Réglages d'image"
        title.setTextColor(Ui.TEXT)
        title.textSize = 22f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        root.addView(title)

        info = TextView(this)
        info.setTextColor(Ui.DIM)
        info.textSize = 13f
        info.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12))
        root.addView(info)

        val reload = Ui.pill(this, "Relire les réglages de la TV")
        root.addView(reload, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)))
        reload.setOnClickListener { load() }

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 32))
        val scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        load()
    }

    private fun load() {
        info.text = "Lecture des réglages de la TV…"
        thread {
            val result = Picture.items(this)
            runOnUiThread { show(result) }
        }
    }

    private fun show(result: Result<List<Picture.Item>>) {
        list.removeAllViews()
        val items = result.getOrNull()
        if (items == null) {
            info.text = "Échec : ${result.exceptionOrNull()?.message}"
            return
        }
        info.text = "${items.size} réglages proposés par la TV. Chaque modification est appliquée tout de suite."
        // Le mode d'image en premier : le changer modifie les autres valeurs.
        items.sortedBy { if (it.target == "pictureMode") 0 else 1 }.forEach { list.addView(card(it)) }
    }

    private fun card(item: Picture.Item): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.rounded(this, Ui.SURFACE, 16)
        val p = Ui.dp(this, 14)
        card.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Ui.dp(this, 8)
        card.layoutParams = lp

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val name = TextView(this)
        name.text = Picture.labelOf(item.target)
        name.setTextColor(Ui.TEXT)
        name.textSize = 15f
        name.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this)
        value.setTextColor(Ui.ACCENT)
        value.textSize = 15f
        value.text = Picture.valueLabel(item.current)
        head.addView(value)
        card.addView(head)

        if (item.numeric) {
            val min = item.min!!
            val step = item.step
            val steps = (item.max!! - min) / step
            val bar = SeekBar(this)
            bar.max = steps
            val now = item.current.toIntOrNull() ?: min
            bar.progress = ((now - min) / step).coerceIn(0, steps)
            bar.setPadding(0, Ui.dp(this, 10), 0, 0)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = (min + progress * step).toString()
                }

                override fun onStartTrackingTouch(s: SeekBar) {}

                override fun onStopTrackingTouch(s: SeekBar) {
                    apply(item.target, (min + s.progress * step).toString(), false)
                }
            })
            card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else if (item.choices.isNotEmpty()) {
            card.isClickable = true
            card.setOnClickListener {
                val shown = item.choices.map { Picture.valueLabel(it) }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(Picture.labelOf(item.target))
                    .setSingleChoiceItems(shown, item.choices.indexOf(item.current)) { d, i ->
                        d.dismiss()
                        value.text = shown[i]
                        // Changer de mode d'image modifie les autres réglages : on relit tout ensuite.
                        apply(item.target, item.choices[i], item.target == "pictureMode")
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
        return card
    }

    private fun apply(target: String, value: String, reloadAfter: Boolean) {
        thread {
            val result = Picture.setOne(this, target, value)
            runOnUiThread {
                val failure = result.exceptionOrNull()
                if (failure != null) {
                    val msg = failure.message.orEmpty()
                    Ui.toast(this, "« ${Picture.labelOf(target)} » refusé par la TV : $msg")
                    thread {
                        val text = Picture.diagnose(this, target, value, msg)
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            val tv = TextView(this)
                            tv.text = text
                            tv.textSize = 13f
                            tv.setTextIsSelectable(true)
                            val p = Ui.dp(this, 20)
                            tv.setPadding(p, p / 2, p, 0)
                            val sv = ScrollView(this)
                            sv.addView(tv)
                            AlertDialog.Builder(this).setTitle("Réglage refusé").setView(sv).setPositiveButton("OK", null).show()
                        }
                    }
                    load()
                } else if (reloadAfter) {
                    load()
                }
            }
        }
    }
}
