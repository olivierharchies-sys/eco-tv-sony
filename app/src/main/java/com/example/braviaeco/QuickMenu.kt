package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import kotlin.concurrent.thread

/**
 * Bouton d'actions rapides de la télécommande : écran éteint (le son continue) et minuteur d'extinction.
 */
class QuickMenu(private val act: Activity) {

    private fun toast(message: String) = Ui.toast(act, message)

    fun show() {
        val remaining = SleepTimer.remainingMinutes(act)
        val labels = arrayOf(
            "Écran éteint / rallumé (le son continue)",
            if (remaining != null) "Minuteur d'extinction (reste $remaining min)…" else "Minuteur d'extinction…"
        )
        AlertDialog.Builder(act)
            .setTitle("Actions rapides")
            .setItems(labels) { _, which ->
                if (which == 0) togglePictureOff() else timerDialog()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    // ---------------------------------------------------------------- écran éteint

    private fun sp() = act.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    /** Mode « Image off » de la TV : l'écran s'éteint, le son et le décodeur continuent. */
    private fun togglePictureOff() {
        toast("Écran…")
        thread {
            val current = BraviaClient.getMode(act).getOrNull()
            val message = if (current == EcoMode.PICTURE_OFF) {
                val back = EcoMode.fromApi(sp().getString("mode_before_picture_off", null))
                    ?.takeIf { it != EcoMode.PICTURE_OFF } ?: EcoMode.LOW
                BraviaClient.setMode(act, back).fold(
                    onSuccess = { "Écran rallumé (économie : ${back.label})" },
                    onFailure = { "Échec : ${it.message}" }
                )
            } else {
                if (current != null) sp().edit().putString("mode_before_picture_off", current.api).apply()
                BraviaClient.setMode(act, EcoMode.PICTURE_OFF).fold(
                    onSuccess = { "Écran éteint, son conservé" },
                    onFailure = { "Échec : ${it.message}" }
                )
            }
            act.runOnUiThread {
                toast(message)
                EcoWidgetProvider.refreshAll(act)
            }
        }
    }

    // ---------------------------------------------------------------- minuteur

    private fun timerDialog() {
        val minutes = intArrayOf(15, 30, 45, 60, 90, 120)
        val labels = ArrayList<String>()
        minutes.forEach { labels.add("Éteindre la TV dans $it min") }
        val remaining = SleepTimer.remainingMinutes(act)
        if (remaining != null) labels.add("Annuler le minuteur (reste $remaining min)")

        AlertDialog.Builder(act)
            .setTitle("Minuteur d'extinction")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < minutes.size) {
                    SleepTimer.set(act, minutes[i])
                    toast("La TV s'éteindra dans ${minutes[i]} min")
                } else {
                    SleepTimer.cancel(act)
                    toast("Minuteur annulé")
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }
}
