package com.example.gitzippush

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rapports d'erreur enregistrés (fichiers .md complets, à transmettre à Claude). */
object Reports {
    private const val KEEP = 40

    fun dir(ctx: Context): File = File(ctx.filesDir, "reports").also { it.mkdirs() }

    /** Les rapports, les plus récents d'abord. */
    fun list(ctx: Context): List<File> =
        (dir(ctx).listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".md") }
            .sortedByDescending { it.lastModified() }

    /** Enregistre le rapport dans l'appli (le texte va dans le presse-papiers, pas dans Téléchargements). */
    fun save(ctx: Context, label: String, text: String): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH'h'mm'm'ss", Locale.FRANCE).format(Date())
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "rapport" }
        val f = File(dir(ctx), "erreur_${safe}_$stamp.md")
        f.writeText(text, Charsets.UTF_8)
        prune(ctx)
        BuildArchive.notifyChanged()
        return f
    }

    fun remove(ctx: Context, f: File) {
        f.delete()
        BuildArchive.notifyChanged()
    }

    fun clear(ctx: Context) {
        for (f in list(ctx)) f.delete()
        BuildArchive.notifyChanged()
    }

    private fun prune(ctx: Context) {
        for (f in list(ctx).drop(KEEP)) f.delete()
    }
}

/** Suppression du zip d'origine (sur le téléphone) quand sa compilation a échoué. */
object SourceZip {

    /**
     * Range le zip d'origine dans Téléchargements/Envoi GitHub/<dépôt>/Zips (après un envoi réussi).
     * Renvoie le nouveau lien (file://), ou null s'il n'a pas pu être déplacé.
     */
    @Suppress("DEPRECATION")
    fun moveToRepo(ctx: Context, uriText: String, name: String, size: Long, repo: String): Uri? {
        if (uriText.isEmpty() || !Downloads.hasAccess()) return null
        val uri = Uri.parse(uriText)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val expected = if (size > 0) size else try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        val src: File = when {
            uri.scheme == "file" -> File(uri.path ?: return null)
            // lien « content » : le même fichier (nom ET taille) à la racine de Téléchargements
            name.isNotEmpty() && expected > 0 ->
                File(downloads, name).takeIf { it.isFile && it.length() == expected } ?: return null
            else -> return null
        }
        if (!src.isFile) return null
        val canon = src.canonicalPath
        // jamais un fichier interne, ni un zip déjà rangé
        if (canon.startsWith(ctx.filesDir.canonicalPath) || canon.contains("/${PublicFiles.ROOT}/")) return null
        val dir = File(downloads, "${PublicFiles.ROOT}/${PublicFiles.repoDir(repo) ?: return null}/Zips")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        var target = File(dir, src.name)
        var n = 2
        while (target.exists()) {
            target = File(dir, src.nameWithoutExtension + " ($n)." + src.extension)
            n++
        }
        val ok = src.renameTo(target) || try {
            src.copyTo(target)
            if (src.delete()) true else {
                target.delete()
                false
            }
        } catch (e: Exception) {
            target.delete()
            false
        }
        if (!ok) return null
        try {
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath, src.absolutePath), null, null)
        } catch (e: Exception) {
            // affichage dans les gestionnaires de fichiers seulement
        }
        return Uri.fromFile(target)
    }

    /** Vrai si le fichier a bien été supprimé. [size] sert à retrouver le fichier dans Téléchargements. */
    @Suppress("DEPRECATION")
    fun delete(ctx: Context, uriText: String, name: String, size: Long): Boolean {
        if (uriText.isEmpty()) return false
        val uri = Uri.parse(uriText)
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return false)
            // jamais une version de l'historique (restauration) ni un fichier interne
            if (f.canonicalPath.startsWith(ctx.filesDir.canonicalPath)) return false
            return f.isFile && f.delete()
        }
        try {
            if (DocumentsContract.isDocumentUri(ctx, uri) && DocumentsContract.deleteDocument(ctx.contentResolver, uri)) {
                return true
            }
        } catch (e: Exception) {
            // pas le droit de supprimer via ce lien
        }
        try {
            if (ctx.contentResolver.delete(uri, null, null) > 0) return true
        } catch (e: Exception) {
            // idem
        }
        // dernier recours : le même fichier (nom + taille) dans Téléchargements
        if (!Downloads.hasAccess() || name.isEmpty()) return false
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val f = File(dir, name)
        return f.isFile && (size <= 0 || f.length() == size) && f.delete()
    }
}
