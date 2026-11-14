package com.example.gitzippush

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Accès au dossier Téléchargements pour trouver le dernier zip.
 * Sur Android 11 et plus, il faut l'autorisation « Accès à tous les fichiers » (à donner une seule fois).
 */
object Downloads {

    /** Disponible à partir d'Android 11 (sur les versions plus anciennes : choisir le zip à la main). */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Écran de réglages où l'utilisateur autorise l'accès à tous les fichiers pour cette appli. */
    fun accessIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + context.packageName)
        )

    /** Réglage général, si l'écran propre à l'appli n'existe pas sur ce téléphone. */
    fun accessFallbackIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** Le fichier .zip le plus récent du dossier Téléchargements, ou null. */
    @Suppress("DEPRECATION")
    fun latestZip(): File? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = dir.listFiles() ?: return null
        var best: File? = null
        for (f in files) {
            if (!f.isFile || f.length() <= 0L) continue
            if (!f.name.endsWith(".zip", ignoreCase = true)) continue
            val current = best
            if (current == null || f.lastModified() > current.lastModified()) best = f
        }
        return best
    }

    fun ago(timeMs: Long): String {
        val seconds = (System.currentTimeMillis() - timeMs) / 1000
        return when {
            seconds < 60 -> "à l'instant"
            seconds < 3600 -> "il y a ${seconds / 60} min"
            seconds < 86400 -> "il y a ${seconds / 3600} h"
            else -> "il y a ${seconds / 86400} j"
        }
    }
}
