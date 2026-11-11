package com.example.braviaeco

import android.content.Context
import org.json.JSONObject

/**
 * Profils d'image mémorisés : des emplacements nommés (Jour, Nuit, Jeu…) qui contiennent les réglages
 * d'image de la TV au moment de la mémorisation. Enregistrés sur le téléphone.
 * Un emplacement peut être associé à une entrée (HDMI 2…) : il est alors appliqué à l'ouverture de la
 * télécommande quand l'entrée a changé depuis la dernière fois.
 */
object PictureSlots {
    private const val FILE = "bravia_eco"
    private const val K_SLOTS = "picture_slots"
    private const val K_LAST = "picture_slot_last"
    private const val K_AUTO = "picture_slot_auto"
    private const val K_AUTO_SEEN = "picture_slot_auto_seen"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun readAll(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString(K_SLOTS, null) ?: "{}") }.getOrDefault(JSONObject())

    private fun readAuto(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString(K_AUTO, null) ?: "{}") }.getOrDefault(JSONObject())

    fun names(ctx: Context): List<String> = readAll(ctx).keys().asSequence().toList()

    fun get(ctx: Context, name: String): Map<String, String>? {
        val o = readAll(ctx).optJSONObject(name) ?: return null
        val map = LinkedHashMap<String, String>()
        for (k in o.keys()) map[k] = o.optString(k)
        return map
    }

    /** Crée l'emplacement, ou remplace celui qui porte déjà ce nom. */
    fun put(ctx: Context, name: String, values: Map<String, String>) {
        val all = readAll(ctx)
        val o = JSONObject()
        values.forEach { (k, v) -> o.put(k, v) }
        all.put(name, o)
        sp(ctx).edit().putString(K_SLOTS, all.toString()).putString(K_LAST, name).apply()
    }

    fun remove(ctx: Context, name: String) {
        val all = readAll(ctx)
        all.remove(name)
        val autos = readAuto(ctx)
        autos.keys().asSequence().toList().forEach { if (autos.optString(it) == name) autos.remove(it) }
        val edit = sp(ctx).edit().putString(K_SLOTS, all.toString()).putString(K_AUTO, autos.toString())
        if (last(ctx) == name) edit.remove(K_LAST)
        edit.apply()
    }

    fun last(ctx: Context): String? = sp(ctx).getString(K_LAST, null)

    fun markUsed(ctx: Context, name: String) {
        sp(ctx).edit().putString(K_LAST, name).apply()
    }

    /** Emplacement qui suit le dernier utilisé (en boucle), ou null s'il n'y en a aucun. */
    fun next(ctx: Context): String? {
        val list = names(ctx)
        if (list.isEmpty()) return null
        val i = list.indexOf(last(ctx))
        return list[(i + 1) % list.size]
    }

    // ---------------------------------------------------------------- association à une entrée

    /** Clé stable d'une entrée : « extInput:hdmi?port=2 » devient « hdmi2 » ; sinon l'URI en minuscules. */
    fun inputKey(uri: String): String {
        val port = Regex("[?&]port=(\\d+)", RegexOption.IGNORE_CASE).find(uri)?.groupValues?.get(1)
        return if (uri.startsWith("extInput:", ignoreCase = true) && port != null) {
            uri.substringAfter(':').substringBefore('?').lowercase() + port
        } else {
            uri.lowercase()
        }
    }

    fun inputLabel(uri: String): String = inputKey(uri).uppercase()

    fun setAuto(ctx: Context, inputUri: String, name: String?) {
        val autos = readAuto(ctx)
        val key = inputKey(inputUri)
        if (name == null) autos.remove(key) else autos.put(key, name)
        sp(ctx).edit().putString(K_AUTO, autos.toString()).apply()
    }

    /** Emplacement associé à cette entrée, s'il existe encore. */
    fun autoFor(ctx: Context, inputUri: String): String? =
        readAuto(ctx).optString(inputKey(inputUri)).takeIf { it.isNotEmpty() && get(ctx, it) != null }

    fun lastSeenInput(ctx: Context): String? = sp(ctx).getString(K_AUTO_SEEN, null)

    fun saveSeenInput(ctx: Context, uri: String) {
        sp(ctx).edit().putString(K_AUTO_SEEN, inputKey(uri)).apply()
    }
}
