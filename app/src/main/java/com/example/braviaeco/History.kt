package com.example.braviaeco

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Un élément de l'historique. kind : "app" (application de la TV, ref = URI),
 * "decoder" (raccourci du décodeur, keys = touches), "content" (chaîne de la TV, ref = URI).
 */
data class HistoryEntry(
    val kind: String,
    val title: String,
    val ref: String,
    val keys: List<String> = emptyList(),
    val time: Long = System.currentTimeMillis()
) {
    val key get() = "$kind|${if (kind == "decoder") title else ref}"
}

/** Derniers programmes / applications lancés depuis la télécommande, enregistrés sur le téléphone. */
object History {
    private const val FILE = "bravia_eco"
    private const val MAX = 30

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun list(ctx: Context): List<HistoryEntry> {
        val arr = runCatching { JSONArray(sp(ctx).getString("history", "[]")) }.getOrElse { JSONArray() }
        val out = ArrayList<HistoryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ka = o.optJSONArray("keys") ?: JSONArray()
            out.add(
                HistoryEntry(
                    o.optString("kind"), o.optString("title"), o.optString("ref"),
                    (0 until ka.length()).map { ka.optString(it) }, o.optLong("time")
                )
            )
        }
        return out
    }

    /** Ajoute en tête ; un même élément déjà présent est remplacé (pas de doublon). */
    @Synchronized
    fun add(ctx: Context, entry: HistoryEntry) {
        val all = list(ctx).toMutableList()
        if (all.firstOrNull()?.key == entry.key) all.removeAt(0) else all.removeAll { it.key == entry.key }
        all.add(0, entry)
        save(ctx, all.take(MAX))
    }

    @Synchronized
    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (e in list) {
            val ka = JSONArray()
            e.keys.forEach { ka.put(it) }
            arr.put(JSONObject().put("kind", e.kind).put("title", e.title).put("ref", e.ref).put("keys", ka).put("time", e.time))
        }
        sp(ctx).edit().putString("history", arr.toString()).apply()
    }
}

/** Icônes des applications de la TV : adresses lues dans la liste de la TV, images gardées en mémoire. */
object AppIcons {
    private val urls = ConcurrentHashMap<String, String>()
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val ui = Handler(Looper.getMainLooper())

    fun remember(apps: List<TvApp>) {
        apps.forEach { if (it.icon.isNotEmpty()) urls[it.uri] = it.icon }
    }

    fun bitmap(uri: String): Bitmap? = bitmaps[uri]

    /** Télécharge en arrière-plan les icônes manquantes ; [onUpdate] est appelé (thread principal) à chaque nouvelle icône. */
    fun prepare(ctx: Context, uris: Collection<String>, onUpdate: () -> Unit) {
        val app = ctx.applicationContext
        val wanted = uris.filter { it.isNotEmpty() && !bitmaps.containsKey(it) }
        if (wanted.isEmpty()) return
        Thread {
            if (wanted.any { !urls.containsKey(it) }) {
                BraviaClient.getApps(app).getOrNull()?.let { remember(it) }
            }
            for (uri in wanted) {
                val bmp = urls[uri]?.let { download(app, it) } ?: continue
                bitmaps[uri] = bmp
                ui.post(onUpdate)
            }
        }.start()
    }

    private fun download(ctx: Context, spec: String): Bitmap? = runCatching {
        val full = if (spec.startsWith("/")) "http://${Prefs.ip(ctx)}$spec" else spec
        val conn = URL(full).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            val psk = Prefs.psk(ctx)
            if (psk.isNotEmpty()) conn.setRequestProperty("X-Auth-PSK", psk)
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
