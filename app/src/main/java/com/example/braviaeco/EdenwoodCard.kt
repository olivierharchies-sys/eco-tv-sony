package com.example.braviaeco

import android.content.Intent
import android.graphics.Color
import android.widget.EditText
import android.widget.TextView

/** Carte « TV Edenwood » : adresse IP, adresse MAC (réveil réseau), association et accès à sa télécommande. */
internal class EdenwoodCard(act: MainActivity) : SettingsCard(act) {

    private lateinit var edenwoodIpField: EditText
    private lateinit var edenwoodMacField: EditText
    private lateinit var edenwoodState: TextView

    override fun persist() {
        if (!::edenwoodIpField.isInitialized) return
        EdenwoodPrefs.saveIp(act, edenwoodIpField.text.toString())
        // Adresse MAC : enregistrée normalisée ; un texte invalide est ignoré (le champ est corrigé).
        val mac = WakeOnLan.normalize(edenwoodMacField.text.toString())
        if (mac != null) {
            EdenwoodPrefs.saveMac(act, mac)
            if (edenwoodMacField.text.toString() != mac) edenwoodMacField.setText(mac)
        } else if (edenwoodMacField.text.isBlank()) {
            EdenwoodPrefs.saveMac(act, "")
        }
    }

    override fun refresh() {
        if (!::edenwoodState.isInitialized) return
        edenwoodState.text =
            if (AtvDevice.EDENWOOD.paired(act)) act.getString(R.string.settings_etat_edenwood_associee) else act.getString(R.string.settings_etat_edenwood_non_associee)
    }

    /** Carte « TV Edenwood » : adresse IP, association (code affiché sur la TV) et accès à sa télécommande bleue. */
    override fun add() {
        val card = newCard()

        card.addView(cardText(act.getString(R.string.settings_tv_edenwood), 17f, Color.WHITE, true))
        card.addView(cardText(
            act.getString(R.string.settings_pilote_la_tv_edenwood_android),
            12f, Ui.DIM, false
        ))
        edenwoodIpField = EditText(act)
        edenwoodIpField.hint = act.getString(R.string.settings_adresse_ip_de_la_edenwood)
        edenwoodIpField.setText(EdenwoodPrefs.ip(act))
        edenwoodIpField.setSingleLine(true)
        edenwoodIpField.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        edenwoodIpField.setTextColor(Color.WHITE)
        edenwoodIpField.setHintTextColor(Ui.DIM)
        card.addView(edenwoodIpField, fieldLp())

        edenwoodMacField = EditText(act)
        edenwoodMacField.hint = act.getString(R.string.settings_adresse_mac_pour_l_allumer)
        edenwoodMacField.setText(EdenwoodPrefs.mac(act))
        edenwoodMacField.setSingleLine(true)
        edenwoodMacField.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edenwoodMacField.setTextColor(Color.WHITE)
        edenwoodMacField.setHintTextColor(Ui.DIM)
        card.addView(edenwoodMacField, fieldLp())
        card.addView(cardText(
            act.getString(R.string.settings_l_adresse_mac_sert_au),
            12f, Ui.DIM, false
        ))

        edenwoodState = cardText("", 14f, Color.WHITE, false)
        card.addView(edenwoodState)
        refresh()

        cardButton(card, act.getString(R.string.settings_detecter_la_tv_edenwood)) { detectEdenwood() }
        cardButton(card, act.getString(R.string.settings_lire_la_mac_tv_allumee)) { learnEdenwoodMac() }
        cardButton(card, act.getString(R.string.settings_tester_le_reveil_reseau)) { testEdenwoodWake() }
        cardButton(card, act.getString(R.string.settings_associer_la_tv_edenwood)) { act.pairing.pair(AtvDevice.EDENWOOD) }
        cardButton(card, act.getString(R.string.settings_dissocier_la_tv_edenwood)) {
            AtvDevice.EDENWOOD.savePaired(act, false)
            Bg.run { AtvRemote.disconnect(AtvDevice.EDENWOOD) }
            refresh()
        }
        cardButton(card, act.getString(R.string.settings_ouvrir_la_telecommande_edenwood)) {
            act.saveAll()
            Prefs.saveRemoteDevice(act, Prefs.DEVICE_EDENWOOD)
            act.startActivity(Intent(act, EdenwoodRemoteActivity::class.java))
        }

        attach(card)
    }

    private fun learnEdenwoodMac() {
        act.saveAll()
        val ip = EdenwoodPrefs.ip(act)
        if (ip.isEmpty()) {
            tell(act.getString(R.string.settings_saisis_d_abord_l_adresse))
            return
        }
        tell(act.getString(R.string.settings_lecture_de_l_adresse_mac))
        Bg.run {
            val mac = WakeOnLan.learnMac(ip, 6466)
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (mac == null) {
                    tell(act.getString(R.string.settings_mac_non_lisible_par_l))
                } else {
                    edenwoodMacField.setText(mac)
                    act.saveAll()
                    tell("MAC trouvée : $mac")
                }
            }
        }
    }

    private fun testEdenwoodWake() {
        act.saveAll()
        val mac = EdenwoodPrefs.mac(act)
        if (mac.isEmpty()) {
            tell(act.getString(R.string.settings_saisis_d_abord_l_adresse_2))
            return
        }
        Bg.run {
            val sent = WakeOnLan.send(mac, EdenwoodPrefs.ip(act))
            act.runOnUiThread {
                tell(if (sent) act.getString(R.string.settings_reveil_envoye_eteins_la_edenwood) else act.getString(R.string.settings_envoi_impossible_verifie_le_wi))
            }
        }
    }

    private fun detectEdenwood() {
        act.saveAll()
        tell(act.getString(R.string.settings_recherche_de_la_tv_edenwood))
        // On écarte la Sony et le décodeur : seule la Edenwood doit rester.
        val others = setOf(AtvPrefs.ip(act)).filter { it.isNotEmpty() }.toSet()
        Discovery.findDecoder(act, Prefs.ip(act), others) { ip ->
            if (!act.isFinishing) {
                if (ip == null) {
                    tell(act.getString(R.string.settings_aucune_tv_trouvee_saisis_son))
                } else {
                    edenwoodIpField.setText(ip)
                    act.saveAll()
                    tell("TV Edenwood trouvée : $ip")
                }
            }
        }
    }
}
