package com.example.braviaeco

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    private const val MAX = 30

    private fun sp(ctx: Context) = AppPrefs.sp(ctx)

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

/** Icônes des applications de la TV : adresses lues dans la liste de la TV, images gardées en mémoire et sur le disque. */
object AppIcons {
    private val urls = ConcurrentHashMap<String, String>()
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var tvApps: List<TvApp> = emptyList()

    /** Préfixe des clés d'icône des applications du décodeur (icône reprise de la même appli sur la TV). */
    private const val DEC = "dec:"

    fun remember(apps: List<TvApp>) {
        if (apps.isNotEmpty()) tvApps = apps
        apps.forEach { if (it.icon.isNotEmpty()) urls[it.uri] = it.icon }
    }

    /** Clé d'icône d'une appli du décodeur : nom + paquet Android (touche « APP:… ») s'il y en a un. */
    fun decoderKey(name: String, keys: List<String>): String {
        val pkg = keys.firstOrNull { it.startsWith(Decoder.APP_PREFIX) }?.substring(Decoder.APP_PREFIX.length) ?: ""
        return DEC + name.trim().lowercase() + "|" + pkg
    }

    /** Icône de l'appli TV équivalente : même paquet dans l'URI Sony, sinon même nom. */
    private fun resolveDecoder(key: String) {
        if (urls.containsKey(key)) return
        val (name, pkg) = key.removePrefix(DEC).split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val apps = tvApps.filter { it.icon.isNotEmpty() }
        val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        val n = norm(name)
        val hit = (if (pkg.isNotEmpty()) apps.firstOrNull { it.uri.contains(pkg, ignoreCase = true) } else null)
            ?: apps.firstOrNull { norm(it.title) == n }
            ?: apps.firstOrNull { n.length >= 3 && (norm(it.title).contains(n) || n.contains(norm(it.title))) }
        if (hit != null) urls[key] = hit.icon
    }

    private fun file(ctx: Context, uri: String): File {
        val dir = File(ctx.applicationContext.filesDir, "icons")
        dir.mkdirs()
        return File(dir, Integer.toHexString(uri.hashCode()) + "_" + uri.length + ".png")
    }

    /** Icône déjà connue (mémoire, sinon fichier gardé sur le téléphone) ; null si à télécharger. */
    fun bitmap(ctx: Context, uri: String): Bitmap? {
        bitmaps[uri]?.let { return it }
        val f = file(ctx, uri)
        if (!f.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
        if (bmp == null) f.delete() else bitmaps[uri] = bmp
        return bmp
    }

    /** Télécharge en arrière-plan les icônes jamais vues (puis gardées sur le disque) ; [onUpdate] à chaque nouvelle icône. */
    fun prepare(ctx: Context, uris: Collection<String>, onUpdate: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val wanted = uris.filter { it.isNotEmpty() && bitmap(app, it) == null }
            if (wanted.isEmpty()) return@Thread
            if (wanted.any { !urls.containsKey(it) }) {
                BraviaClient.getApps(app).getOrNull()?.let { remember(it) }
            }
            wanted.filter { it.startsWith(DEC) }.forEach { resolveDecoder(it) }
            for (uri in wanted) {
                val bytes = urls[uri]?.let { download(app, it) } ?: continue
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: continue
                runCatching { file(app, uri).writeBytes(bytes) }
                bitmaps[uri] = bmp
                ui.post(onUpdate)
            }
        }.start()
    }

    private fun download(ctx: Context, spec: String): ByteArray? = runCatching {
        val full = if (spec.startsWith("/")) "http://${Prefs.ip(ctx)}$spec" else spec
        val conn = URL(full).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            val psk = Prefs.psk(ctx)
            if (psk.isNotEmpty()) conn.setRequestProperty("X-Auth-PSK", psk)
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
