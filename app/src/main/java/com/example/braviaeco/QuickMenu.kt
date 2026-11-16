package com.example.braviaeco

import android.app.Activity

/**
 * Bouton d'actions rapides de la télécommande : écran éteint (le son continue) et minuteur d'extinction.
 */
class QuickMenu(private val act: Activity) {

    private fun toast(message: String) = Ui.toast(act, message)

    fun show() {
        val remaining = SleepTimer.remainingMinutes(act)
        val items = listOf(
            GridItem("Écran éteint / rallumé", "🌙", sub = "le son continue"),
            GridItem("Minuteur d'extinction", "⏱", sub = if (remaining != null) "reste $remaining min" else "", active = remaining != null)
        )
        TileGrid.show(act, "Actions rapides", items, columns = 2) { which ->
            if (which == 0) togglePictureOff() else timerDialog()
        }
    }

    // ---------------------------------------------------------------- écran éteint

    /** Mode « Image off » de la TV : l'écran s'éteint, le son et le décodeur continuent. */
    private fun togglePictureOff() {
        toast("Écran…")
        Bg.run {
            val message = DesignActions.togglePictureOff(act)
            act.runOnUiThread {
                toast(message)
                EcoWidgetProvider.refreshAll(act)
            }
        }
    }

    // ---------------------------------------------------------------- minuteur

    private fun timerDialog() {
        val minutes = intArrayOf(15, 30, 45, 60, 90, 120)
        val items = ArrayList<GridItem>()
        minutes.forEach { items.add(GridItem("$it min", "⏱")) }
        val remaining = SleepTimer.remainingMinutes(act)
        if (remaining != null) items.add(GridItem("Annuler", "✖", sub = "reste $remaining min"))

        TileGrid.show(act, "Éteindre la TV dans…", items) { i ->
                if (i < minutes.size) {
                    SleepTimer.set(act, minutes[i])
                    toast("La TV s'éteindra dans ${minutes[i]} min")
                } else {
                    SleepTimer.cancel(act)
                    toast("Minuteur annulé")
                }
        }
    }
}
