package com.example.braviaeco

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * « Lire sur TV » envoyé par StreOli : ouvre le lien (ou l'appli) sur l'appareil Android TV de la pièce
 * (Android TV Remote, [AtvRemote.launch]), puis affiche la télécommande.
 * Seuls les liens https et les noms de paquet sont acceptés.
 */
class StreamLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.getStringExtra(EXTRA_LINK)?.trim().orEmpty()
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)?.trim().orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty()

        val validPkg = pkg.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$"))
        val target = when {
            link.startsWith("https://") || link.startsWith("nflx://") -> link
            // Fiche ou recherche StreOli ouverte dans StreOli sur l'appareil.
            link.matches(Regex("^streoli://(title/(movie|tv)/\\d+|search\\?q=[^\\s]*)$")) -> link
            validPkg -> pkg
            else -> ""
        }
        if (target.isEmpty()) {
            Ui.toast(this, "StreOli : lien non valide")
            finish()
            return
        }

        // Même logique que la télécommande : la pièce détectée décide de la TV visée.
        RoomSwitch.pick(this)?.let { Prefs.saveRemoteDevice(this, it) }
        // Appareil imposé par StreOli (« tv », « decoder », « edenwood »), sinon celui de la pièce.
        val wanted = when (intent.getStringExtra(EXTRA_DEVICE)) {
            "tv" -> AtvDevice.TV
            "decoder" -> AtvDevice.DECODER
            "edenwood" -> AtvDevice.EDENWOOD
            else -> null
        }?.takeIf { it.paired(this) && it.ip(this).isNotEmpty() }
        val device = wanted ?: pickDevice()
        if (device == null) {
            Ui.toast(this, "Aucun appareil Android TV associé (Réglages d'Éco TV)")
            openRemote()
            return
        }

        val app = applicationContext
        Bg.run {
            var result = AtvRemote.launch(app, target, device)
            if (result.isFailure && target != pkg && validPkg) result = AtvRemote.launch(app, pkg, device)
            val name = title.ifEmpty { "Lecture" }
            Ui.toast(
                app,
                if (result.isSuccess) "« $name » → ${device.label}"
                else "Échec vers ${device.label} : ${result.exceptionOrNull()?.message ?: "erreur"}",
            )
        }
        openRemote()
    }

    /** Appareil de la pièce : Edenwood, ou Sony (décodeur / TV selon la cible de la télécommande). */
    private fun pickDevice(): AtvDevice? {
        val order = when {
            Prefs.remoteDevice(this) == Prefs.DEVICE_EDENWOOD -> listOf(AtvDevice.EDENWOOD)
            Prefs.target(this) == Target.DECODER -> listOf(AtvDevice.DECODER, AtvDevice.TV)
            else -> listOf(AtvDevice.TV, AtvDevice.DECODER)
        }
        return order.firstOrNull { it.paired(this) && it.ip(this).isNotEmpty() }
    }

    /** Télécommande (pas l'écran de configuration) : reprise si déjà ouverte, sinon créée. */
    private fun openRemote() {
        startActivity(
            Intent(this, RemoteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }

    companion object {
        const val ACTION = "be.streoli.action.PLAY_ON_TV"
        const val EXTRA_LINK = "link"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DEVICE = "device"
    }
}
