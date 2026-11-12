package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread

/** Réglage rapide de la luminosité de l'image (l'échelle est lue sur la TV, 0–50 sur ce modèle). */
class BrightnessDialog(private val act: Activity) {

    fun show() {
        Ui.toast(act, "Luminosité…")
        thread {
            val item = Picture.items(act).getOrNull()?.firstOrNull { it.target == "brightness" }
            act.runOnUiThread {
                if (item == null || !item.numeric) {
                    Ui.toast(act, "La TV ne propose pas de réglage de luminosité")
                } else {
                    build(item)
                }
            }
        }
    }

    private fun build(item: Picture.Item) {
        val min = item.min!!
        val step = item.step
        val steps = (item.max!! - min) / step
        val pad = Ui.dp(act, 20)

        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad, pad, pad / 2)

        val value = TextView(act)
        value.textSize = 28f
        value.gravity = Gravity.CENTER
        box.addView(value)

        val bar = SeekBar(act)
        bar.max = steps
        val start = ((item.current.toIntOrNull() ?: min) - min) / step
        bar.progress = start.coerceIn(0, steps)
        value.text = (min + bar.progress * step).toString()
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun send(progress: Int) {
            val v = (min + progress * step).toString()
            thread {
                val failure = Picture.setOne(act, "brightness", v).exceptionOrNull()
                if (failure != null) {
                    act.runOnUiThread {
                        Ui.toast(act, "Luminosité refusée par la TV : ${failure.message}")
                    }
                }
            }
        }

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = (min + progress * step).toString()
            }

            override fun onStartTrackingTouch(s: SeekBar) {}

            override fun onStopTrackingTouch(s: SeekBar) = send(s.progress)
        })

        fun nudge(delta: Int) {
            val p = (bar.progress + delta).coerceIn(0, steps)
            bar.progress = p
            send(p)
        }

        AlertDialog.Builder(act)
            .setTitle("Luminosité")
            .setView(box)
            .setNeutralButton("−") { _, _ -> }
            .setNegativeButton("Fermer", null)
            .create()
            .also { d ->
                d.show()
                // « − » et « + » ne ferment pas la fenêtre.
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { nudge(-1) }
            }
    }
}
