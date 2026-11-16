package com.example.braviaeco

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.widget.EditText

/** Carte « Sauvegarde des réglages » : export chiffré et import. Le contenu est géré par [BackupStore]. */
internal class BackupCard(act: MainActivity) : SettingsCard(act) {

    override fun add() {
        val card = newCard()

        card.addView(cardText(act.getString(R.string.settings_sauvegarde_des_reglages), 17f, Color.WHITE, true))
        card.addView(cardText(
            act.getString(R.string.settings_exporte_tout_tv_decodeur_raccourcis),
            12f, Ui.DIM, false
        ))
        cardButton(card, act.getString(R.string.settings_exporter)) { exportSettings() }
        cardButton(card, act.getString(R.string.settings_importer)) { importSettings() }

        attach(card)
    }

    private fun passphraseField(hint: String): EditText {
        val field = EditText(act)
        field.hint = hint
        field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        return field
    }

    private fun charsOf(field: EditText): CharArray =
        CharArray(field.length()).also { field.text.getChars(0, field.length(), it, 0) }

    /** Demande un mot de passe, chiffre la sauvegarde (elle contient les clés de la TV), puis la propose au partage. */
    private fun exportSettings() {
        val pass = passphraseField("Mot de passe (${BackupCrypto.MIN_PASSPHRASE} caractères minimum)")
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.settings_chiffrer_la_sauvegarde))
            .setMessage(
                act.getString(R.string.settings_la_sauvegarde_contient_la_cle)
            )
            .setView(pass)
            .setPositiveButton(act.getString(R.string.settings_exporter)) { _, _ ->
                val chars = charsOf(pass)
                if (chars.size < BackupCrypto.MIN_PASSPHRASE) {
                    tell("Mot de passe trop court (${BackupCrypto.MIN_PASSPHRASE} caractères minimum)")
                } else {
                    tell(act.getString(R.string.settings_chiffrement))
                    Bg.run {
                        val encrypted = runCatching { BackupCrypto.encrypt(BackupStore.toJson(act), chars) }
                        act.runOnUiThread {
                            if (act.isFinishing) return@runOnUiThread
                            encrypted
                                .onSuccess { shareBackup(it) }
                                .onFailure { tell("Export impossible : ${it.message}") }
                        }
                    }
                }
            }
            .setNegativeButton(act.getString(R.string.dw_sleep_cancel), null)
            .show()
    }

    private fun shareBackup(text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, act.getString(R.string.settings_sauvegarde_eco_tv_chiffree))
            .putExtra(Intent.EXTRA_TEXT, text)
        act.startActivity(Intent.createChooser(send, act.getString(R.string.settings_exporter_les_reglages)))
    }

    /** Sauvegarde chiffrée : demande le mot de passe. Ancienne sauvegarde en clair : importée telle quelle. */
    private fun importText(text: String) {
        if (!BackupCrypto.isEncrypted(text)) {
            restoreSettings(text)
            return
        }
        val pass = passphraseField(act.getString(R.string.settings_mot_de_passe_de_la))
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.settings_sauvegarde_chiffree))
            .setView(pass)
            .setPositiveButton(act.getString(R.string.settings_dechiffrer)) { _, _ ->
                val chars = charsOf(pass)
                Bg.run {
                    val plain = runCatching { BackupCrypto.decrypt(text, chars) }
                    act.runOnUiThread {
                        if (act.isFinishing) return@runOnUiThread
                        plain
                            .onSuccess { restoreSettings(it) }
                            .onFailure { tell(it.message ?: act.getString(R.string.settings_mot_de_passe_incorrect)) }
                    }
                }
            }
            .setNegativeButton(act.getString(R.string.dw_sleep_cancel), null)
            .show()
    }

    private fun importSettings() {
        val edit = EditText(act)
        edit.hint = act.getString(R.string.settings_colle_ici_la_sauvegarde)
        edit.minLines = 4
        edit.gravity = android.view.Gravity.TOP
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.settings_importer_des_reglages))
            .setMessage(act.getString(R.string.settings_les_reglages_actuels_de_l))
            .setView(edit)
            .setPositiveButton(act.getString(R.string.settings_importer)) { _, _ -> importText(edit.text.toString()) }
            .setNegativeButton(act.getString(R.string.dw_sleep_cancel), null)
            .show()
    }
    /** Lit toute la sauvegarde avant de toucher aux réglages : une sauvegarde illisible ne change rien. */
    private fun restoreSettings(text: String) {
        try {
            val count = BackupStore.restore(act, text)
            tell("Réglages importés : $count éléments")
            act.recreate()
        } catch (e: Exception) {
            tell("Sauvegarde illisible : ${e.message}")
        }
    }
}
