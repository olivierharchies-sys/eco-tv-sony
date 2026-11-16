package com.example.braviaeco

import android.app.AlertDialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

/** Association (une seule fois) d'un appareil Android TV : connexion, puis saisie du code affiché sur l'appareil. */
internal class PairingUi(private val act: MainActivity) {

    private fun tell(text: String) = act.tell(text)

    fun pair(device: AtvDevice) {
        act.saveAll()
        tell(
            when (device) {
                AtvDevice.TV -> act.getString(R.string.settings_connexion_a_la_tv)
                AtvDevice.EDENWOOD -> act.getString(R.string.settings_connexion_a_la_edenwood)
                AtvDevice.DECODER -> act.getString(R.string.settings_connexion_au_decodeur)
            }
        )
        Bg.run {
            val started = AtvRemote.startPairing(act, device)
            act.runOnUiThread {
                val pairing = started.getOrNull()
                if (pairing == null || act.isFinishing) {
                    pairing?.close()
                    tell("Échec : ${started.exceptionOrNull()?.message}")
                } else {
                    tell(if (device == AtvDevice.DECODER) act.getString(R.string.settings_saisis_le_code_affiche_a) else act.getString(R.string.settings_saisis_le_code_affiche_sur))
                    askPairingCode(pairing, device)
                }
            }
        }
    }

    private fun askPairingCode(pairing: AtvRemote.Pairing, device: AtvDevice) {
        val edit = EditText(act)
        edit.hint = act.getString(R.string.settings_code_a_6_caracteres)
        edit.setSingleLine(true)
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.setTextColor(Color.WHITE)
        edit.setHintTextColor(Ui.DIM)
        val box = LinearLayout(act)
        val pad = Ui.dp(act, 20)
        box.setPadding(pad, Ui.dp(act, 8), pad, 0)
        box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (device == AtvDevice.DECODER) act.getString(R.string.settings_code_affiche_a_l_ecran) else act.getString(R.string.settings_code_affiche_sur_la_tv))
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(act.getString(R.string.settings_valider)) { _, _ ->
                val code = edit.text.toString()
                Bg.run {
                    val done = pairing.finish(act, code)
                    act.runOnUiThread {
                        if (done.isSuccess) {
                            tell(
                                when (device) {
                                    AtvDevice.TV -> act.getString(R.string.settings_tv_associee)
                                    AtvDevice.EDENWOOD -> act.getString(R.string.settings_edenwood_associee)
                                    AtvDevice.DECODER -> act.getString(R.string.settings_decodeur_associe)
                                }
                            )
                        } else {
                            tell("Échec : ${done.exceptionOrNull()?.message}")
                        }
                        act.refreshCards()
                    }
                }
            }
            .setNegativeButton(act.getString(R.string.dw_sleep_cancel)) { _, _ -> Bg.run { pairing.close() } }
            .show()
    }
}
