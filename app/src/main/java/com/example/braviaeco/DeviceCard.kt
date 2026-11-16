package com.example.braviaeco

import android.app.AlertDialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Cartes « Décodeur en Wi-Fi direct » (avec maintien éveillé et design) et « Clavier dans les applis de la TV ». */
internal class DeviceCard(act: MainActivity) : SettingsCard(act) {

    private lateinit var decoderIpField: EditText
    private lateinit var decoderState: TextView
    private lateinit var tvKeysState: TextView

    override fun persist() {
        if (!::decoderIpField.isInitialized) return
        AtvPrefs.saveIp(act, decoderIpField.text.toString())
    }

    override fun refresh() {
        if (::decoderState.isInitialized) {
            decoderState.text = if (Decoder.wifi(act)) {
                act.getString(R.string.settings_etat_decodeur_associe_touches_envoyees)
            } else {
                act.getString(R.string.settings_etat_decodeur_non_associe_touches)
            }
        }
        if (::tvKeysState.isInitialized) {
            tvKeysState.text = if (AtvDevice.TV.paired(act)) act.getString(R.string.settings_etat_tv_associee) else act.getString(R.string.settings_etat_tv_non_associee)
        }
    }

    /** Cartes « Décodeur en Wi-Fi direct » et « Clavier dans les applis de la TV », ajoutées avant le texte d'aide. */
    override fun add() {
        val card = newCard()

        card.addView(cardText(act.getString(R.string.settings_decodeur_en_wi_fi_direct), 17f, Color.WHITE, true))
        card.addView(cardText(
            act.getString(R.string.settings_pilote_le_decodeur_android_tv),
            12f, Ui.DIM, false
        ))
        decoderIpField = EditText(act)
        decoderIpField.hint = act.getString(R.string.settings_adresse_ip_du_decodeur_ex)
        decoderIpField.setText(AtvPrefs.ip(act))
        decoderIpField.setSingleLine(true)
        decoderIpField.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        decoderIpField.setTextColor(Color.WHITE)
        decoderIpField.setHintTextColor(Ui.DIM)
        card.addView(decoderIpField, fieldLp())

        decoderState = cardText("", 14f, Color.WHITE, false)
        card.addView(decoderState)
        refresh()

        cardButton(card, act.getString(R.string.settings_detecter_le_decodeur)) { detectDecoder() }
        cardButton(card, act.getString(R.string.settings_associer_le_decodeur)) { act.pairing.pair(AtvDevice.DECODER) }
        cardButton(card, act.getString(R.string.settings_dissocier_le_decodeur)) {
            AtvPrefs.savePaired(act, false)
            Bg.run { AtvRemote.disconnect() }
            refresh()
        }

        card.addView(cardText(
            act.getString(R.string.settings_evite_la_mise_en_veille),
            12f, Ui.DIM, false
        ))
        val keepAwake = Button(act)
        keepAwake.isAllCaps = false
        keepAwake.setTextColor(Color.WHITE)
        keepAwake.setBackgroundResource(R.drawable.btn_surface)
        fun paintKeepAwake() {
            keepAwake.text = if (KeepAwake.enabled(act)) act.getString(R.string.settings_garder_le_decodeur_eveille_active) else act.getString(R.string.settings_garder_le_decodeur_eveille_desactive)
        }
        paintKeepAwake()
        keepAwake.setOnClickListener {
            act.saveAll()
            val on = !KeepAwake.enabled(act)
            KeepAwake.setEnabled(act, on)
            paintKeepAwake()
            if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8)
            }
            tell(
                if (!on) act.getString(R.string.settings_decodeur_veille_automatique_non_bloquee)
                else if (Decoder.wifi(act)) act.getString(R.string.settings_decodeur_garde_eveille_tant_que)
                else act.getString(R.string.settings_active_mais_associe_d_abord)
            )
        }
        val keepLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48))
        keepLp.topMargin = Ui.dp(act, 8)
        card.addView(keepAwake, keepLp)

        val design = Button(act)
        design.isAllCaps = false
        design.setTextColor(Color.WHITE)
        design.setBackgroundResource(R.drawable.btn_surface)
        fun paintDesign() {
            design.text = act.getString(R.string.settings_design_de_la_telecommande) + RemoteDesign.NAMES[RemoteDesign.current(act)]
        }
        paintDesign()
        design.setOnClickListener {
            AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(act.getString(R.string.settings_design_de_la_telecommande_2))
                .setSingleChoiceItems(RemoteDesign.NAMES, RemoteDesign.current(act)) { d, which ->
                    RemoteDesign.save(act, which)
                    paintDesign()
                    tell("Design « ${RemoteDesign.NAMES[which]} » : visible à l'ouverture de la télécommande")
                    d.dismiss()
                }
                .setNegativeButton(act.getString(R.string.dw_sleep_cancel), null)
                .show()
        }
        val designLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48))
        designLp.topMargin = Ui.dp(act, 8)
        card.addView(design, designLp)

        val sep2 = TextView(act)
        sep2.setPadding(0, Ui.dp(act, 14), 0, 0)
        card.addView(sep2)

        card.addView(cardText(act.getString(R.string.settings_clavier_dans_les_applis_de), 17f, Color.WHITE, true))
        card.addView(cardText(
            act.getString(R.string.settings_certaines_applis_de_la_tv),
            12f, Ui.DIM, false
        ))
        tvKeysState = cardText("", 14f, Color.WHITE, false)
        card.addView(tvKeysState)
        refresh()

        cardButton(card, act.getString(R.string.settings_associer_la_tv)) { act.pairing.pair(AtvDevice.TV) }
        cardButton(card, act.getString(R.string.settings_dissocier_la_tv)) {
            AtvDevice.TV.savePaired(act, false)
            Bg.run { AtvRemote.disconnect(AtvDevice.TV) }
            refresh()
        }

        attach(card)
    }

    private fun detectDecoder() {
        act.saveAll()
        tell(act.getString(R.string.settings_recherche_du_decodeur))
        Discovery.findDecoder(act, Prefs.ip(act)) { ip ->
            if (!act.isFinishing) {
                if (ip == null) {
                    tell(act.getString(R.string.settings_aucun_decodeur_trouve_saisis_son))
                } else {
                    decoderIpField.setText(ip)
                    act.saveAll()
                    tell("Décodeur trouvé : $ip")
                }
            }
        }
    }
}
