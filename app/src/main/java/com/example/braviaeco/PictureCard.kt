package com.example.braviaeco

import android.app.AlertDialog
import android.graphics.Color
import android.widget.ScrollView
import android.widget.TextView

/** Carte « Image : 4K ou upscalé » : détection de la résolution et profils d'image. */
internal class PictureCard(act: MainActivity) : SettingsCard(act) {

    override fun add() {
        val card = newCard()

        card.addView(cardText(act.getString(R.string.settings_image_4k_ou_upscale), 17f, Color.WHITE, true))
        card.addView(cardText(
            act.getString(R.string.settings_detecte_si_l_image_est),
            12f, Ui.DIM, false
        ))

        cardButton(card, act.getString(R.string.settings_detecter_et_adapter_l_image)) {
            act.saveAll()
            tell(act.getString(R.string.settings_detection_de_l_image))
            Bg.run {
                val d = Picture.detect(act)
                act.runOnUiThread {
                    val kind = d.kind
                    if (kind == null) {
                        askPictureKind(d.reason)
                    } else {
                        applyPicture(kind, "${kind.label} (${d.reason})")
                    }
                }
            }
        }
        cardButton(card, act.getString(R.string.settings_appliquer_le_profil_4k_natif)) { act.saveAll(); applyPicture(Picture.Kind.NATIVE_4K, act.getString(R.string.settings_choix_manuel)) }
        cardButton(card, act.getString(R.string.settings_appliquer_le_profil_upscale)) { act.saveAll(); applyPicture(Picture.Kind.UPSCALED, act.getString(R.string.settings_choix_manuel)) }
        cardButton(card, act.getString(R.string.settings_analyser_la_tv_lecture_seule)) {
            act.saveAll()
            tell(act.getString(R.string.settings_analyse_de_la_tv))
            Bg.run {
                val report = Picture.analyze(act)
                act.runOnUiThread { showReport(act.getString(R.string.settings_analyse_de_la_tv_2), report) }
            }
        }

        attach(card)
    }

    private fun askPictureKind(reason: String) {
        tell("Résolution inconnue : $reason")
        // Le dernier choix est proposé en premier (bouton principal), l'autre profil en second.
        val last = lastPictureKind()
        val first = last ?: Picture.Kind.NATIVE_4K
        val second = if (first == Picture.Kind.NATIVE_4K) Picture.Kind.UPSCALED else Picture.Kind.NATIVE_4K
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.settings_quel_type_d_image))
            .setMessage("Cette TV ne donne pas la résolution du signal ($reason). Choisis le profil à appliquer.")
            .setPositiveButton(if (last != null) "${first.label} (dernier choix)" else first.label) { _, _ ->
                applyPicture(first, act.getString(R.string.settings_choix_manuel))
            }
            .setNegativeButton(second.label) { _, _ -> applyPicture(second, act.getString(R.string.settings_choix_manuel)) }
            .setNeutralButton(act.getString(R.string.dw_sleep_cancel), null)
            .show()
    }

    private fun lastPictureKind(): Picture.Kind? = runCatching {
        val name = AppPrefs.sp(act).getString("picture_kind", null)
        Picture.Kind.values().firstOrNull { it.name == name }
    }.getOrNull()

    private fun savePictureKind(kind: Picture.Kind) {
        AppPrefs.sp(act).edit().putString("picture_kind", kind.name).apply()
    }

    private fun applyPicture(kind: Picture.Kind, why: String) {
        tell("Réglage de l'image : ${kind.label}…")
        Bg.run {
            val result = Picture.adapt(act, kind)
            act.runOnUiThread {
                val body = result.getOrNull()
                if (body != null) {
                    savePictureKind(kind)
                    tell("Image réglée : ${kind.label}")
                    showReport("Profil ${kind.label}", "Détecté : $why\n\n$body")
                } else {
                    tell("Échec : ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    /** Compte rendu long : fenêtre défilante plutôt qu'une bulle. */
    private fun showReport(title: String, text: String) {
        val tv = TextView(act)
        tv.text = text
        tv.textSize = 13f
        tv.setTextIsSelectable(true)
        val p = Ui.dp(act, 20)
        tv.setPadding(p, p / 2, p, 0)
        val sv = ScrollView(act)
        sv.addView(tv)
        AlertDialog.Builder(act)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton(act.getString(R.string.dw_pad_ok), null)
            .show()
    }
}
