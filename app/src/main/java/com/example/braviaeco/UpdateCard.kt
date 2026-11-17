package com.example.braviaeco

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Carte « Mise à jour » : vérifie la release GitHub à l'ouverture, installe sur demande. */
internal class UpdateCard(act: MainActivity) : SettingsCard(act) {

    private lateinit var state: TextView
    private lateinit var installBtn: Button
    private var url: String? = null
    private var busy = false

    override fun add() {
        val card = newCard()
        val installed = Updater.installedCode(act)
        card.addView(cardText("Mise à jour", 17f, Color.WHITE, true))
        card.addView(cardText("Version installée : $installed", 12f, Ui.DIM, false))
        state = cardText("", 13f, Ui.ACCENT, false)
        card.addView(state)
        cardButton(card, "Vérifier") { check() }
        cardButton(card, "Installer") { install() }
        installBtn = card.getChildAt(card.childCount - 1) as Button
        installBtn.visibility = View.GONE
        attach(card)
        check()
    }

    private fun check() {
        if (busy) return
        busy = true
        state.text = "Vérification…"
        Bg.run {
            val r = runCatching { Updater.latest() }
            act.runOnUiThread {
                busy = false
                if (act.isFinishing) return@runOnUiThread
                r.onSuccess { rel ->
                    when {
                        rel == null -> { url = null; state.text = "Aucune version publiée." }
                        rel.versionCode > Updater.installedCode(act) -> {
                            url = rel.url
                            state.text = "Version ${rel.versionCode} disponible."
                        }
                        else -> { url = null; state.text = "Déjà à jour." }
                    }
                }.onFailure { url = null; state.text = "Vérification impossible : ${it.message}" }
                installBtn.visibility = if (url != null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun install() {
        val u = url ?: return
        if (busy) return
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            runCatching {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            }
            tell("Autorise « sources inconnues » pour Éco TV, puis réessaie.")
            return
        }
        busy = true
        state.text = "Téléchargement…"
        Bg.run {
            val r = runCatching { Updater.install(act, Updater.download(act, u)) }
            act.runOnUiThread {
                busy = false
                if (act.isFinishing) return@runOnUiThread
                state.text = r.fold({ "Installation lancée." }, { "Échec : ${it.message}" })
            }
        }
    }
}
