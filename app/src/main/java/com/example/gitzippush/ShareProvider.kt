package com.example.gitzippush

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Donne à d'autres applis (Claude…) un accès en lecture aux fichiers préparés dans le cache. */
class ShareProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        appContext = context?.applicationContext
        return true
    }

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("pas de contexte")
        val segs = uri.pathSegments
        val name = uri.lastPathSegment ?: throw FileNotFoundException("nom manquant")
        if (name.contains('/') || name.contains("..")) throw FileNotFoundException("nom invalide")
        val root = when {
            segs.size == 1 -> dir(ctx)
            segs.size == 2 -> roots(ctx)[segs[0]] ?: throw FileNotFoundException("dossier inconnu")
            else -> throw FileNotFoundException("chemin invalide")
        }
        val f = File(root, name)
        if (!f.isFile) throw FileNotFoundException(name)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("lecture seule")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment ?: return null
        return when {
            name.endsWith(".md") -> "text/markdown"
            name.endsWith(".zip") -> "application/zip"
            name.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val f = try {
            fileOf(uri)
        } catch (e: FileNotFoundException) {
            return null
        }
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = cols.map { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.example.gitzippush.share"

        fun dir(ctx: android.content.Context): File = File(ctx.cacheDir, "share")

        /** Dossiers partageables : s = imports, c = sélections pour Claude, a = sauvegardes de compilations,
         *  b = zip ouvert dans l'explorateur. */
        private fun roots(ctx: android.content.Context): Map<String, File> = mapOf(
            "s" to dir(ctx),
            "c" to ClaudeBundle.dir(ctx),
            "a" to BuildArchive.dir(ctx),
            "b" to FilesActivity.browseDir(ctx),
            "r" to Reports.dir(ctx)
        )

        private var appContext: android.content.Context? = null

        fun uriFor(file: File): Uri {
            val ctx = appContext
            val parent = file.parentFile?.absolutePath
            if (ctx != null && parent != null) {
                for ((k, d) in roots(ctx)) {
                    if (k != "s" && d.absolutePath == parent) {
                        return Uri.parse("content://$AUTHORITY/$k/" + Uri.encode(file.name))
                    }
                }
            }
            return Uri.parse("content://$AUTHORITY/" + Uri.encode(file.name))
        }
    }
}
