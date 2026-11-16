package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

/**
 * Bouton « image » de la télécommande : emplacements d'image mémorisés (Jour, Nuit, Jeu…)
 * et profils 4K natif / upscalé.
 *  - appui court : applique l'emplacement suivant (sans emplacement : ouvre le menu) ;
 *  - appui long : menu complet (mémoriser, appliquer, profils, supprimer).
 */
class PictureMenu(private val act: Activity) {

    private fun toast(message: String) = Ui.toast(act, message)

    fun onTap() {
        val next = PictureSlots.next(act)
        if (next == null) showMenu() else applySlot(next)
    }

    fun onLongPress() = showMenu()

    // ---------------------------------------------------------------- menu

    private val KIND_EMOJI = mapOf(
        Picture.Kind.NATIVE_4K to "🎞",
        Picture.Kind.UPSCALED to "📺",
        Picture.Kind.GAME to "🎮",
        Picture.Kind.NIGHT to "🌙",
        Picture.Kind.SPORT to "⚽",
        Picture.Kind.DAY to "☀️"
    )

    fun showMenu() {
        val names = PictureSlots.names(act)
        val items = ArrayList<GridItem>()
        val actions = ArrayList<() -> Unit>()

        items.add(GridItem("Mémoriser", "➕", sub = "réglages actuels"))
        actions.add { askName() }
        for (n in names) {
            items.add(GridItem(n, "💾", sub = "emplacement", active = n == PictureSlots.last(act)))
            actions.add { applySlot(n) }
        }
        for (kind in Picture.Kind.values()) {
            items.add(GridItem(kind.label, KIND_EMOJI[kind] ?: "🖼"))
            actions.add { applyKind(kind) }
        }
        items.add(GridItem("Tous les réglages", "⚙️"))
        actions.add { act.startActivity(Intent(act, PictureSettingsActivity::class.java)) }
        if (names.isNotEmpty()) {
            items.add(GridItem("Supprimer", "🗑", sub = "un emplacement"))
            actions.add { askDelete(names) }
        }

        TileGrid.show(act, "Image", items) { which -> actions[which]() }
    }

    // ---------------------------------------------------------------- emplacements

    fun applySlot(name: String) {
        val values = PictureSlots.get(act, name)
        if (values == null) {
            toast("Emplacement introuvable : $name")
            return
        }
        toast("Image : $name…")
        Bg.run {
            val result = Picture.applyValues(act, values)
            act.runOnUiThread {
                if (result.isSuccess) {
                    PictureSlots.markUsed(act, name)
                    toast("Image : $name ✓")
                } else {
                    toast("Échec : ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    private fun askName() {
        val used = PictureSlots.names(act)
        val suggestion = listOf("Jour", "Nuit", "Jeu", "Sport", "Film").firstOrNull { it !in used }
            ?: "Profil ${used.size + 1}"
        val edit = EditText(act)
        edit.setSingleLine(true)
        edit.setText(suggestion)
        edit.setSelectAllOnFocus(true)
        AlertDialog.Builder(act)
            .setTitle("Nom de l'emplacement")
            .setMessage("Les réglages d'image actuels de la TV seront mémorisés. Un nom déjà utilisé est remplacé.")
            .setView(edit)
            .setPositiveButton("Mémoriser") { _, _ -> save(edit.text.toString().trim().take(24)) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun save(name: String) {
        if (name.isEmpty()) {
            toast("Donne un nom à l'emplacement")
            return
        }
        toast("Lecture des réglages de la TV…")
        Bg.run {
            val captured = Picture.capture(act)
            val input = if (captured.isSuccess) Picture.currentInput(act) else null
            act.runOnUiThread {
                val values = captured.getOrNull()
                if (values == null) {
                    toast("Échec : ${captured.exceptionOrNull()?.message}")
                } else {
                    PictureSlots.put(act, name, values)
                    toast("Mémorisé : $name (${values.size} réglages)")
                    if (input != null) askAuto(name, input)
                }
            }
        }
    }

    private fun askAuto(name: String, input: String) {
        val label = PictureSlots.inputLabel(input)
        AlertDialog.Builder(act)
            .setTitle("Appliquer automatiquement ?")
            .setMessage(
                "Appliquer « $name » à l'ouverture de la télécommande quand la TV est sur $label, " +
                    "si l'entrée a changé depuis la dernière fois ?"
            )
            .setPositiveButton("Oui") { _, _ ->
                PictureSlots.setAuto(act, input, name)
                PictureSlots.saveSeenInput(act, input)
                toast("« $name » liée à $label")
            }
            .setNegativeButton("Non", null)
            .show()
    }

    private fun askDelete(names: List<String>) {
        AlertDialog.Builder(act)
            .setTitle("Supprimer quel emplacement ?")
            .setItems(names.toTypedArray()) { _, i ->
                PictureSlots.remove(act, names[i])
                toast("Supprimé : ${names[i]}")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------------------------------------------------------------- profils 4K / upscalé

    private fun applyKind(kind: Picture.Kind) {
        toast("Réglage de l'image : ${kind.label}…")
        Bg.run {
            val result = Picture.adapt(act, kind)
            act.runOnUiThread {
                val body = result.getOrNull()
                if (body != null) {
                    AppPrefs.sp(act)
                        .edit().putString("picture_kind", kind.name).apply()
                    toast("Image réglée : ${kind.label}")
                    report("Profil ${kind.label}", body)
                } else {
                    toast("Échec : ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    private fun report(title: String, text: String) {
        val tv = TextView(act)
        tv.text = text
        tv.textSize = 13f
        tv.setTextIsSelectable(true)
        val p = Ui.dp(act, 20)
        tv.setPadding(p, p / 2, p, 0)
        val sv = ScrollView(act)
        sv.addView(tv)
        AlertDialog.Builder(act).setTitle(title).setView(sv).setPositiveButton("OK", null).show()
    }
}
