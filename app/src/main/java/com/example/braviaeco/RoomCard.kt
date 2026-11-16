package com.example.braviaeco

import android.app.AlertDialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Carte « Bascule automatique Sony / Edenwood » : bornes Wi-Fi et Bluetooth associés à chaque TV. */
internal class RoomCard(act: MainActivity) : SettingsCard(act) {

    private lateinit var roomState: TextView
    private lateinit var roomToggle: Button
    private lateinit var roomMethod: Button
    private lateinit var roomFallback: Button

    /** Carte « Bascule automatique » : associe les points d'accès du mesh aux deux TV. */
    override fun add() {
        val card = newCard()

        card.addView(cardText(act.getString(R.string.settings_bascule_automatique_sony_edenwood), 17f, Color.WHITE, true))
        card.addView(cardText(
            act.getString(R.string.settings_le_telephone_ne_peut_pas),
            12f, Ui.DIM, false
        ))
        roomState = cardText("", 14f, Color.WHITE, false)
        card.addView(roomState)

        roomToggle = Button(act)
        roomToggle.isAllCaps = false
        roomToggle.setTextColor(Color.WHITE)
        roomToggle.setBackgroundResource(R.drawable.btn_surface)
        roomToggle.setOnClickListener {
            val on = !RoomSwitch.enabled(act)
            RoomSwitch.setEnabled(act, on)
            if (on && !RoomSwitch.hasPermission(act)) {
                act.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 9)
                tell(act.getString(R.string.settings_autorise_la_localisation_sert_seulement))
            } else {
                tell(if (on) act.getString(R.string.settings_bascule_automatique_activee) else act.getString(R.string.settings_bascule_automatique_desactivee))
            }
            refresh()
        }
        val toggleLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48))
        toggleLp.topMargin = Ui.dp(act, 8)
        card.addView(roomToggle, toggleLp)

        roomMethod = Button(act)
        roomMethod.isAllCaps = false
        roomMethod.setTextColor(Color.WHITE)
        roomMethod.setBackgroundResource(R.drawable.btn_surface)
        roomMethod.setOnClickListener {
            val bt = RoomSwitch.method(act) != RoomSwitch.METHOD_BT
            RoomSwitch.setMethod(act, if (bt) RoomSwitch.METHOD_BT else RoomSwitch.METHOD_WIFI)
            if (bt) askBtPermission()
            refresh()
        }
        card.addView(roomMethod, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48))
            .apply { topMargin = Ui.dp(act, 8) })

        roomFallback = Button(act)
        roomFallback.isAllCaps = false
        roomFallback.setTextColor(Color.WHITE)
        roomFallback.setBackgroundResource(R.drawable.btn_surface)
        roomFallback.setOnClickListener {
            RoomSwitch.setFallback(act, !RoomSwitch.fallback(act))
            refresh()
        }
        card.addView(roomFallback, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48))
            .apply { topMargin = Ui.dp(act, 8) })

        cardButton(card, act.getString(R.string.settings_cette_borne_tv_sony)) { learnAccessPoint(Prefs.DEVICE_SONY, act.getString(R.string.settings_la_sony)) }
        cardButton(card, act.getString(R.string.settings_cette_borne_tv_edenwood)) { learnAccessPoint(Prefs.DEVICE_EDENWOOD, act.getString(R.string.settings_la_edenwood)) }
        cardButton(card, act.getString(R.string.settings_oublier_les_bornes_associees)) {
            RoomSwitch.clearAccessPoints(act)
            tell(act.getString(R.string.settings_bornes_oubliees))
            refresh()
        }
        cardButton(card, act.getString(R.string.settings_bluetooth_de_la_tv_sony)) { learnBt(Prefs.DEVICE_SONY, act.getString(R.string.settings_la_sony)) }
        cardButton(card, act.getString(R.string.settings_bluetooth_de_la_tv_edenwood)) { learnBt(Prefs.DEVICE_EDENWOOD, act.getString(R.string.settings_la_edenwood)) }
        cardButton(card, act.getString(R.string.settings_oublier_les_bluetooth_associes)) {
            BtRoom.clear(act)
            tell(act.getString(R.string.settings_bluetooth_oublies))
            refresh()
        }
        refresh()

        attach(card)
    }

    private fun learnAccessPoint(device: String, name: String) {
        val current = RoomSwitch.current(act)
        if (current == null) {
            if (!RoomSwitch.hasPermission(act)) {
                act.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 9)
            }
            tell(act.getString(R.string.settings_borne_illisible_autorise_la_localisation))
            return
        }
        RoomSwitch.addAccessPoint(act, device, current.first)
        tell("Borne ${current.first} associée à $name")
        refresh()
    }

    private fun askBtPermission(): Boolean {
        if (BtRoom.hasPermission(act)) return true
        act.requestPermissions(BtRoom.permissions(), 10)
        tell(act.getString(R.string.settings_autorise_les_appareils_a_proximite))
        return false
    }

    /** Scan ~10 s puis liste des appareils vus (plus fort en haut) : on choisit celui de la TV. */
    private fun learnBt(device: String, name: String) {
        if (!askBtPermission()) return
        if (!BtRoom.isOn(act)) { tell(act.getString(R.string.settings_active_le_bluetooth_du_telephone)); return }
        tell(act.getString(R.string.settings_recherche_bluetooth_10_s))
        BtRoom.scan(act) { list ->
            if (act.isFinishing) return@scan
            if (list.isEmpty()) { tell(act.getString(R.string.settings_aucun_appareil_bluetooth_vu)); return@scan }
            val labels = list.map { "${it.name.ifEmpty { "(sans nom)" }} · ${it.rssi} dBm\n${it.address}" }.toTypedArray()
            AlertDialog.Builder(act)
                .setTitle("Bluetooth de $name")
                .setItems(labels) { _, i ->
                    BtRoom.associate(act, device, list[i])
                    tell("${list[i].name.ifEmpty { list[i].address }} associé à $name")
                    refresh()
                }
                .setNegativeButton(act.getString(R.string.dw_sleep_cancel), null)
                .show()
        }
    }

    override fun refresh() {
        if (!::roomState.isInitialized) return
        val current = RoomSwitch.current(act)
        val now = when {
            !RoomSwitch.hasPermission(act) -> act.getString(R.string.settings_permission_de_localisation_non_accordee)
            current == null -> act.getString(R.string.settings_illisible_localisation_desactivee_ou_wi)
            else -> "${current.first} (${current.second} dBm)"
        }
        roomState.text = "Borne actuelle : $now\n" +
            "Associées : Sony ${RoomSwitch.accessPoints(act, Prefs.DEVICE_SONY).size} · " +
            "Edenwood ${RoomSwitch.accessPoints(act, Prefs.DEVICE_EDENWOOD).size}\n" +
            "Bluetooth : Sony ${btLabel(Prefs.DEVICE_SONY)} · Edenwood ${btLabel(Prefs.DEVICE_EDENWOOD)}\n" +
            "Wi-Fi ${if (RoomSwitch.wifiOn(act)) "actif" else "coupé"} · Bluetooth " +
            "${if (BtRoom.isOn(act)) "actif" else "coupé"} · en cours : " +
            when (RoomSwitch.effectiveMethod(act)) {
                RoomSwitch.METHOD_WIFI -> act.getString(R.string.settings_bornes_wi_fi)
                RoomSwitch.METHOD_BT -> act.getString(R.string.settings_bluetooth)
                else -> act.getString(R.string.settings_aucune)
            }
        roomMethod.text = if (RoomSwitch.method(act) == RoomSwitch.METHOD_BT) act.getString(R.string.settings_methode_bluetooth_des_tv)
            else act.getString(R.string.settings_methode_bornes_wi_fi)
        roomFallback.text = if (RoomSwitch.fallback(act)) act.getString(R.string.settings_repli_sur_l_autre_methode)
            else act.getString(R.string.settings_repli_sur_l_autre_methode_2)
        roomToggle.text = if (RoomSwitch.enabled(act)) act.getString(R.string.settings_bascule_automatique_activee_2) else act.getString(R.string.settings_bascule_automatique_desactivee_2)
    }

    private fun btLabel(device: String): String {
        if (!BtRoom.associated(act, device)) return "—"
        val n = BtRoom.name(act, device).ifEmpty { BtRoom.address(act, device) }
        return BtRoom.level(act, device)?.let { "$n ($it dBm)" } ?: n
    }
}
