package com.example.braviaeco

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Contenu de la sauvegarde des réglages : export en JSON et restauration (sans interface). */
internal object BackupStore {

    /** Réglages propres à cette installation : ni exportés ni restaurés (jeton des widgets). */
    private val SKIP = setOf("widget_token")

    /** Contenu de la sauvegarde, en JSON clair (à chiffrer avant tout partage). */
    fun toJson(ctx: Context): String {
        val prefs = AppPrefs.sp(ctx)
        val root = JSONObject()
        for ((key, value) in prefs.all) {
            if (key in SKIP) continue
            val item = JSONObject()
            when (value) {
                is String -> item.put("t", "s").put("v", value)
                is Boolean -> item.put("t", "b").put("v", value)
                is Int -> item.put("t", "i").put("v", value)
                is Long -> item.put("t", "l").put("v", value)
                is Float -> item.put("t", "f").put("v", value.toDouble())
                is Set<*> -> item.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> {}
            }
            if (item.length() > 0) root.put(key, item)
        }
        return root.toString()
    }

    /**
     * Remplace les réglages par ceux de la sauvegarde [text] et renvoie le nombre d'éléments restaurés.
     * Toute la sauvegarde est lue avant de toucher aux réglages : une sauvegarde illisible lève une exception
     * sans rien changer.
     */
    fun restore(ctx: Context, text: String): Int {
        val root = JSONObject(text.trim())
        val ops = ArrayList<(SharedPreferences.Editor) -> Unit>()
        for (key in root.keys()) {
            if (key in SKIP) continue
            val item = root.getJSONObject(key)
            when (item.getString("t")) {
                "s" -> { val v = item.getString("v"); ops.add { it.putString(key, v) } }
                "b" -> { val v = item.getBoolean("v"); ops.add { it.putBoolean(key, v) } }
                "i" -> { val v = item.getInt("v"); ops.add { it.putInt(key, v) } }
                "l" -> { val v = item.getLong("v"); ops.add { it.putLong(key, v) } }
                "f" -> { val v = item.getDouble("v").toFloat(); ops.add { it.putFloat(key, v) } }
                "ss" -> {
                    val arr = item.getJSONArray("v")
                    val v = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    ops.add { it.putStringSet(key, v) }
                }
                else -> error("Type inconnu pour « $key »")
            }
        }
        if (ops.isEmpty()) error("Sauvegarde vide")
        val prefs = AppPrefs.sp(ctx)
        val keep = SKIP.mapNotNull { k -> prefs.getString(k, null)?.let { k to it } }
        val editor = prefs.edit()
        editor.clear()
        ops.forEach { it(editor) }
        keep.forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
        // Clé et certificat d'association remplacés : l'appli doit oublier ceux gardés en mémoire.
        Bg.run { AtvRemote.reset() }
        return ops.size
    }
}
