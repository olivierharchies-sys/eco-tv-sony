package com.example.braviaeco

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Page des réglages : adresse et clé de la TV Sony, essais des modes éco. Le reste est dans des cartes
 * ([SettingsCard]) ajoutées sous le formulaire : une classe par section.
 */
class MainActivity : Activity() {

    private lateinit var ipField: EditText
    private lateinit var pskField: EditText
    private lateinit var status: TextView

    /** Cartes ajoutées sous le formulaire, dans l'ordre d'affichage. */
    private val cards = ArrayList<SettingsCard>()

    /** Association des appareils Android TV (partagée par les cartes). */
    internal val pairing = PairingUi(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Icône de l'appli : droit sur la télécommande dès que la TV est configurée
        // (les réglages restent accessibles par la roue dentée).
        if (savedInstanceState == null &&
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true &&
            Prefs.ip(this).isNotEmpty()
        ) {
            RoomSwitch.startup(this)
            startActivity(Intent(this, RemoteActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        ipField = findViewById(R.id.field_ip)
        pskField = findViewById(R.id.field_psk)
        status = findViewById(R.id.status)

        ipField.setText(Prefs.ip(this))
        pskField.setText(Prefs.psk(this))

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            saveAll()
            show(getString(R.string.settings_reglages_enregistres))
        }

        findViewById<Button>(R.id.btn_detect_tv).setOnClickListener { detectTv() }

        findViewById<Button>(R.id.btn_open_remote).setOnClickListener {
            saveAll()
            startActivity(Intent(this, RemoteActivity::class.java))
        }

        findViewById<Button>(R.id.btn_read).setOnClickListener {
            saveAll()
            runAction(getString(R.string.settings_lecture)) {
                BraviaClient.getMode(this).fold(
                    onSuccess = { "Mode actuel de la TV : ${it.label}" },
                    onFailure = { "Échec : ${it.message}" }
                )
            }
        }

        cards.addAll(
            listOf(
                WebCard(this), DeviceCard(this), EdenwoodCard(this),
                RoomCard(this), PictureCard(this), BackupCard(this),
                UpdateCard(this)
            )
        )
        cards.forEach { it.add() }

        mapOf(
            R.id.test_off to EcoMode.OFF,
            R.id.test_low to EcoMode.LOW,
            R.id.test_high to EcoMode.HIGH,
            R.id.test_picture_off to EcoMode.PICTURE_OFF
        ).forEach { (id, mode) ->
            findViewById<Button>(id).setOnClickListener {
                saveAll()
                runAction("Envoi : ${mode.label}…") {
                    BraviaClient.setMode(this, mode).fold(
                        onSuccess = { "OK : éco ${mode.label}" },
                        onFailure = { "Échec : ${it.message}" }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCards() // la permission de localisation, ou une association, a pu changer entre-temps
    }

    /** Enregistre le formulaire et toutes les cartes. */
    internal fun saveAll() {
        Prefs.saveConnection(this, ipField.text.toString(), pskField.text.toString())
        cards.forEach { it.persist() }
    }

    /** Remet à jour les textes d'état de toutes les cartes. */
    internal fun refreshCards() {
        cards.forEach { it.refresh() }
    }

    private fun detectTv() {
        tell(getString(R.string.settings_recherche_de_la_tv))
        Bg.run {
            val result = Discovery.findTv()
            runOnUiThread {
                val ip = result.getOrNull()
                if (ip == null) {
                    tell("Échec : ${result.exceptionOrNull()?.message}")
                } else {
                    ipField.setText(ip)
                    saveAll()
                    tell("TV trouvée : $ip")
                }
            }
        }
    }

    /** Message dans la zone d'état. */
    internal fun show(text: String) {
        status.text = text
    }

    /** Message dans la zone d'état et en bulle, visible où que l'on soit dans la page. */
    internal fun tell(text: String) {
        show(text)
        Ui.toast(this, text)
    }

    /** loud : le résultat s'affiche aussi en bulle, pour les boutons situés sous la zone d'état. */
    internal fun runAction(progress: String, loud: Boolean = false, work: () -> String) {
        if (loud) tell(progress) else show(progress)
        Bg.run {
            val message = work()
            runOnUiThread {
                if (loud) tell(message) else show(message)
                EcoWidgetProvider.refreshAll(this)
            }
        }
    }
}
