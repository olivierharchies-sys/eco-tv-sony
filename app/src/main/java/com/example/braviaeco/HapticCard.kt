package com.example.braviaeco

import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.widget.Button

/** Carte « Retour haptique » : active ou coupe la petite vibration à chaque appui sur la télécommande. */
internal class HapticCard(act: MainActivity) : SettingsCard(act) {

    private lateinit var toggle: Button

    override fun add() {
        val card = newCard()
        card.addView(cardText("Retour haptique", 17f, Color.WHITE, true))
        card.addView(cardText("Petite vibration à chaque appui sur les touches, la croix et le pavé tactile.", 12f, Ui.DIM, false))
        cardButton(card, "") {
            val on = !Haptics.enabled(act)
            Haptics.setEnabled(act, on)
            paint()
            // Aperçu immédiat quand on l'active.
            if (on) toggle.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            tell(if (on) "Retour haptique activé" else "Retour haptique désactivé")
        }
        toggle = card.getChildAt(card.childCount - 1) as Button
        paint()
        attach(card)
    }

    private fun paint() {
        toggle.text = if (Haptics.enabled(act)) "Retour haptique : activé ✓" else "Retour haptique : désactivé"
    }
}
