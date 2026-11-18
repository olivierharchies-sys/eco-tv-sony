package com.example.braviaeco

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Commande vocale (reconnaissance du téléphone, sans permission micro) :
 * - [STREOLI] : cherche le titre dicté dans StreOli (téléphone, TV, décodeur ou Edenwood) ;
 * - [TV] : tape le texte dicté dans le champ affiché sur l'appareil.
 */
object Voice {
    const val STREOLI = 4101
    const val TV = 4102

    /** Appareil de la télécommande qui a lancé la dictée (null = selon la cible Sony). */
    private var device: AtvDevice? = null

    fun start(act: Activity, mode: Int, device: AtvDevice? = null) {
        this.device = device
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                if (mode == STREOLI) "Film, série, acteur…" else "Texte pour la TV",
            )
        try {
            act.startActivityForResult(i, mode)
        } catch (_: ActivityNotFoundException) {
            Ui.toast(act, "Reconnaissance vocale indisponible")
        }
    }

    /** À appeler depuis onActivityResult ; true si la demande était vocale. */
    fun handle(act: Activity, actions: TvActions, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != STREOLI && requestCode != TV) return false
        if (resultCode != Activity.RESULT_OK) return true
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (text.isNullOrEmpty()) return true
        if (requestCode == STREOLI) StreOliLink.searchOn(act, text, device ?: actions.remoteDevice())
        else actions.pasteText(text, device)
        return true
    }
}
