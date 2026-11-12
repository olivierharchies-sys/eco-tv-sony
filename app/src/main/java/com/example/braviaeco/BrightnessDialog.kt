package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Luminosité de l'image par un double bouton + / − (comme le volume). Échelle lue sur la TV
 * (0–50 sur ce modèle). Un refus de la TV ouvre un compte rendu détaillé (une fois toutes les 8 s).
 */
class BrightnessControl(private val act: Activity, private val onValue: (Int) -> Unit) {

    private val worker = Executors.newSingleThreadExecutor()
    private var loaded = false
    private var min = 0
    private var max = 50
    private var step = 1
    private var cur = 0
    private var lastReport = 0L

    fun nudge(dir: Int) {
        try {
            worker.execute { run(dir) }
        } catch (e: RejectedExecutionException) {
            // activité fermée
        }
    }

    fun close() = worker.shutdownNow()

    private fun run(dir: Int) {
        if (!loaded) {
            val item = Picture.items(act).getOrNull()?.firstOrNull { it.target == "brightness" }
            if (item == null || !item.numeric) {
                act.runOnUiThread { Ui.toast(act, "La TV ne propose pas de réglage de luminosité") }
                return
            }
            min = item.min!!
            max = item.max!!
            step = item.step
            cur = item.current.toIntOrNull() ?: min
            loaded = true
        }
        val next = (cur + dir * step).coerceIn(min, max)
        if (next == cur) return
        val failure = Picture.setOne(act, "brightness", next.toString()).exceptionOrNull()
        if (failure == null) {
            cur = next
            act.runOnUiThread { onValue(next) }
        } else {
            report(next, failure.message.orEmpty())
        }
    }

    private fun report(tried: Int, error: String) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 8000) return
        lastReport = now
        val text = Picture.diagnose(act, "brightness", tried.toString(), error)
        loaded = false
        act.runOnUiThread {
            if (act.isFinishing) return@runOnUiThread
            val tv = TextView(act)
            tv.text = text
            tv.textSize = 13f
            tv.setTextIsSelectable(true)
            val p = Ui.dp(act, 20)
            tv.setPadding(p, p / 2, p, 0)
            val sv = ScrollView(act)
            sv.addView(tv)
            AlertDialog.Builder(act).setTitle("Luminosité refusée par la TV").setView(sv).setPositiveButton("OK", null).show()
        }
    }
}
