package com.example.braviaeco

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour depuis la release GitHub « latest » (publiée par le workflow).
 * Dépôt public : aucun jeton. Installation via PackageInstaller (pas de FileProvider).
 */
object Updater {
    private const val REPO = "olivierharchies-sys/Eco-Tv-Sony"
    const val ACTION_STATUS = "com.example.braviaeco.UPDATE_STATUS"

    data class Release(val versionCode: Int, val url: String)

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Int {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else p.versionCode
    }

    /** Bloquant : à appeler hors du thread principal. null si aucune release exploitable. */
    fun latest(): Release? {
        val c = open("https://api.github.com/repos/$REPO/releases/tags/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IllegalStateException("GitHub ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val code = Regex("v(\\d+)").find(j.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(code, a.optString("browser_download_url").ifBlank { return null })
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Bloquant. Suit les redirections (stockage GitHub) manuellement. */
    fun download(ctx: Context, url: String): File {
        val file = File(File(ctx.cacheDir, "update"), "Eco-Tv-Sony.apk")
        file.parentFile?.mkdirs()
        var u = url
        repeat(5) {
            val c = open(u)
            c.instanceFollowRedirects = false
            try {
                when (c.responseCode) {
                    in 300..399 -> u = c.getHeaderField("Location") ?: throw IllegalStateException("Redirection vide")
                    200 -> {
                        c.inputStream.use { inp -> file.outputStream().use { inp.copyTo(it) } }
                        return file
                    }
                    else -> throw IllegalStateException("Téléchargement ${c.responseCode}")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IllegalStateException("Trop de redirections")
    }

    /** Ouvre la session d'installation ; Android demande ensuite la confirmation (voir [UpdateReceiver]). */
    fun install(ctx: Context, file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            file.inputStream().use { inp ->
                s.openWrite("app.apk", 0, file.length()).use { out ->
                    inp.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Eco-Tv-Sony")
        }
}

/** Résultat de l'installation : ouvre l'écran de confirmation d'Android si nécessaire. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Ui.toast(ctx, "Mise à jour échouée : " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erreur"))
        }
    }
}
