package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * Liens StreOli (streoli://search?q=…, streoli://title/movie/123) ouverts sur le téléphone,
 * la TV Sony, le décodeur ou la Edenwood (StreOli 0.11+ installé sur l'appareil).
 */
object StreOliLink {
    const val PKG = "be.streoli.app"
    private val LIST_URI: Uri = Uri.parse("content://be.streoli.app.list/watchlist")

    data class Item(val type: String, val id: Int, val name: String, val year: String)

    fun search(q: String) = "streoli://search?q=" + URLEncoder.encode(q.take(120), "UTF-8")
    fun title(type: String, id: Int) = "streoli://title/$type/$id"

    /** Appareils Android TV associés et joignables. */
    fun devices(ctx: Context): List<AtvDevice> =
        AtvDevice.entries.filter { it.paired(ctx) && it.ip(ctx).isNotEmpty() }

    /**
     * Choix de l'appareil ; null = téléphone. [first] passe en tête de liste.
     * Un seul choix possible : pas de dialogue.
     */
    fun choose(act: Activity, title: String, withPhone: Boolean, first: AtvDevice?, onPick: (AtvDevice?) -> Unit) {
        val list = devices(act).sortedBy { if (it == first) 0 else 1 }
        val options: List<AtvDevice?> = list + if (withPhone) listOf(null) else emptyList()
        when (options.size) {
            0 -> Ui.toast(act, "Aucun appareil Android TV associé (Réglages d'Éco TV)")
            1 -> onPick(options[0])
            else -> AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(options.map { it?.label ?: "Téléphone" }.toTypedArray()) { _, i -> onPick(options[i]) }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    /** Ouvre [link] dans StreOli sur [device] (null = ce téléphone). */
    fun open(act: Activity, device: AtvDevice?, link: String, what: String) {
        if (device == null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setPackage(PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                act.startActivity(i)
            } catch (_: ActivityNotFoundException) {
                Ui.toast(act, "StreOli absent ou trop ancien (0.11 requis)")
            }
            return
        }
        val app = act.applicationContext
        Ui.toast(act, "« $what » → ${device.label}…")
        Bg.run {
            val r = AtvRemote.launch(app, link, device)
            if (r.isFailure) {
                Ui.toast(app, "Échec vers ${device.label} : StreOli 0.11+ installé dessus ?")
            }
        }
    }

    /** Recherche [q] dans StreOli, sur l'appareil choisi. */
    fun searchOn(act: Activity, q: String, first: AtvDevice?) {
        choose(act, "Chercher « $q » sur…", withPhone = true, first = first) { d -> open(act, d, search(q), q) }
    }

    /** « Ma liste » du profil actif de StreOli (téléphone). */
    fun watchlist(ctx: Context): List<Item> = runCatching {
        ctx.contentResolver.query(LIST_URI, null, null, null, null)?.use { c ->
            val out = mutableListOf<Item>()
            while (c.moveToNext()) {
                out += Item(c.getString(0), c.getInt(1), c.getString(2) ?: "?", c.getString(3) ?: "")
            }
            out
        }
    }.getOrNull() ?: emptyList()

    /** Ma liste StreOli → fiche ouverte sur l'appareil choisi. */
    fun showWatchlist(act: Activity, first: AtvDevice?) {
        val items = watchlist(act)
        if (items.isEmpty()) {
            Ui.toast(act, "Ma liste StreOli vide, ou StreOli 0.11+ absent")
            return
        }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ma liste StreOli")
            .setItems(items.map { if (it.year.isEmpty()) it.name else "${it.name} (${it.year})" }.toTypedArray()) { _, i ->
                val it = items[i]
                choose(act, "Ouvrir « ${it.name} » sur…", withPhone = false, first = first) { d ->
                    open(act, d, title(it.type, it.id), it.name)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }
}
