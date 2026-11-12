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
        val first = tryStep(dir)
        if (first == null) return
        // En économie d'énergie « élevé », la TV verrouille la luminosité : passage en « bas », puis nouvel essai.
        if (BraviaClient.getMode(act).getOrNull() == EcoMode.HIGH && BraviaClient.setMode(act, EcoMode.LOW).isSuccess) {
            act.runOnUiThread { Ui.toast(act, "Économie d'énergie passée sur bas") }
            Thread.sleep(400)
            loaded = false
            val second = tryStep(dir) ?: return
            report(second.first, second.second)
            return
        }
        report(first.first, first.second)
    }

    /** Applique un cran. Renvoie null si c'est fait (ou sans objet), sinon la valeur tentée et l'erreur de la TV. */
    private fun tryStep(dir: Int): Pair<Int, String>? {
        if (!loaded) {
            val item = Picture.items(act).getOrNull()?.firstOrNull { it.target == "brightness" }
            if (item == null || !item.numeric) {
                act.runOnUiThread { Ui.toast(act, "La TV ne propose pas de réglage de luminosité") }
                return null
            }
            min = item.min!!
            max = item.max!!
            step = item.step
            cur = item.current.toIntOrNull() ?: min
            loaded = true
        }
        val next = (cur + dir * step).coerceIn(min, max)
        if (next == cur) return null
        val failure = Picture.setOne(act, "brightness", next.toString()).exceptionOrNull() ?: run {
            cur = next
            act.runOnUiThread { onValue(next) }
            return null
        }
        return next to failure.message.orEmpty()
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
