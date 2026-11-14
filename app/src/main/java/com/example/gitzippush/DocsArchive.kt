package com.example.gitzippush

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copie du zip envoyé dans Documents/Github/<nom du dépôt>/ quand la compilation a réussi.
 * Seuls les [KEEP] zips les plus récents de ce dossier sont gardés.
 */
object DocsArchive {
    const val ROOT = "Github"
    const val KEEP = 3

    /** « Documents/Github/depot » : dossier affiché à l'utilisateur, ou null si le nom du dépôt est inutilisable. */
    fun label(repo: String): String? = PublicFiles.repoDir(repo)?.let { "Documents/$ROOT/$it" }

    /**
     * Copie [src] sous le nom [name] dans Documents/Github/<dépôt>/, puis supprime les zips au-delà des [KEEP] plus récents.
     * Renvoie le chemin affichable de la copie, ou null si elle est impossible (Android < 10 sans accès aux fichiers…).
     */
    @Suppress("DEPRECATION")
    fun save(ctx: Context, repo: String, name: String, src: File): String? {
        val repoDir = PublicFiles.repoDir(repo) ?: return null
        val relative = "$ROOT/$repoDir"
        val shown = "Documents/$relative/$name"
        try {
            if (Downloads.hasAccess()) {
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val dir = File(base, relative)
                if (!dir.isDirectory && !dir.mkdirs()) return null
                val target = File(dir, name)
                src.copyTo(target, overwrite = true)
                val removed = pruneFiles(dir)
                scan(ctx, listOf(target) + removed)
                return shown
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/$relative")
                val uri = ctx.contentResolver.insert(files, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    ?: return null
                pruneStore(ctx, relative)
                return shown
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    /** Ne garde que les [KEEP] zips les plus récents du dossier. Renvoie les fichiers supprimés. */
    private fun pruneFiles(dir: File): List<File> {
        val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) } ?: return emptyList()
        val old = zips.sortedByDescending { it.lastModified() }.drop(KEEP)
        val removed = ArrayList<File>()
        for (f in old) if (f.delete()) removed.add(f)
        return removed
    }

    /** Même chose via MediaStore (sans « accès à tous les fichiers ») : ne voit que les fichiers créés par l'appli. */
    private fun pruneStore(ctx: Context, relative: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val ids = ArrayList<Long>()
        ctx.contentResolver.query(
            files,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("Documents/$relative/", "%.zip"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        )?.use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        for (id in ids.drop(KEEP)) {
            try {
                ctx.contentResolver.delete(ContentUris.withAppendedId(files, id), null, null)
            } catch (e: Exception) {
                // fichier qui n'appartient pas à l'appli : laissé en place
            }
        }
    }

    private fun scan(ctx: Context, files: List<File>) {
        try {
            MediaScannerConnection.scanFile(ctx, files.map { it.absolutePath }.toTypedArray(), null, null)
        } catch (e: Exception) {
            // affichage dans les gestionnaires de fichiers seulement
        }
    }
}
