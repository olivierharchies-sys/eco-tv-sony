package com.example.gitzippush

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream

/** Ce qu'on envoie à GitHub : un zip, un dossier complet ou quelques fichiers. */
interface FileSource {
    fun scan(): ScanResult

    /** Parcourt tous les fichiers (noms nettoyés, chemins relatifs avec « / »). */
    fun forEach(action: (name: String, bytes: ByteArray) -> Unit)

    /** Contenu texte d'un petit fichier (ou null s'il n'existe pas). */
    fun readText(name: String): String? {
        var result: String? = null
        forEach { n, b ->
            if (n == name && result == null && b.size < 100_000) result = String(b, Charsets.UTF_8)
        }
        return result
    }
}

/** Dossiers générés par les outils de compilation : inutiles dans un dépôt. */
object Generated {
    private val DIRS = setOf(
        "build", ".gradle", ".idea", "node_modules", ".cxx", ".externalNativeBuild", "__pycache__",
        ".dart_tool", ".venv", "captures", ".kotlin"
    )
    private val FILES = setOf("local.properties", ".DS_Store")

    fun isIgnoredDir(name: String): Boolean = name in DIRS

    fun isIgnoredPath(path: String): Boolean {
        val parts = path.split('/')
        for (i in 0 until parts.size - 1) if (parts[i] in DIRS) return true
        val last = parts.last()
        return last in FILES || last.endsWith(".iml")
    }
}

/** Retire les chemins générés (build/, .gradle/, node_modules/…) d'une autre source. */
class FilteredSource(private val base: FileSource) : FileSource {
    override fun scan(): ScanResult {
        val s = base.scan()
        val kept = s.files.filter { !Generated.isIgnoredPath(it.name) }
        return ScanResult(kept, ZipTools.detectRoot(kept), s.skipped + (s.files.size - kept.size))
    }

    override fun forEach(action: (name: String, bytes: ByteArray) -> Unit) {
        base.forEach { n, b -> if (!Generated.isIgnoredPath(n)) action(n, b) }
    }

    override fun readText(name: String): String? = base.readText(name)
}

class ZipSource(private val open: () -> InputStream) : FileSource {
    override fun scan(): ScanResult = ZipTools.scan(open)
    override fun forEach(action: (name: String, bytes: ByteArray) -> Unit) {
        try {
            ZipTools.forEachFile(open, action)
        } catch (e: java.util.zip.ZipException) {
            throw PushException("Ce fichier n'est pas un zip valide : ${e.message}")
        }
    }
}

/** Fichier d'une source : chemin relatif, adresse, taille annoncée (-1 si inconnue). */
class DocEntry(val path: String, val uri: Uri, val size: Long)

private fun readDoc(resolver: ContentResolver, e: DocEntry): ByteArray {
    if (e.size > ZipTools.MAX_FILE) {
        throw PushException("« ${e.path} » dépasse 95 Mo : GitHub refuse les fichiers de plus de 100 Mo.")
    }
    val input = resolver.openInputStream(e.uri) ?: throw PushException("Impossible d'ouvrir « ${e.path} ».")
    val bytes = input.use { it.readBytes() }
    if (bytes.size > ZipTools.MAX_FILE) {
        throw PushException("« ${e.path} » dépasse 95 Mo : GitHub refuse les fichiers de plus de 100 Mo.")
    }
    return bytes
}

private fun scanDocs(resolver: ContentResolver, entries: List<DocEntry>): ScanResult {
    val files = LinkedHashMap<String, ScannedFile>()
    for (e in entries) {
        val bytes = readDoc(resolver, e)
        files[e.path] = ScannedFile(e.path, bytes.size.toLong(), ZipTools.gitBlobSha(bytes))
    }
    val list = files.values.toList()
    return ScanResult(list, ZipTools.detectRoot(list), 0)
}

/** Un dossier complet choisi avec le sélecteur Android (Storage Access Framework). */
class TreeSource(
    private val context: Context,
    private val treeUri: Uri,
    private val ignoreGenerated: Boolean
) : FileSource {
    private var cache: List<DocEntry>? = null
    private var skipped = 0

    private fun entries(): List<DocEntry> {
        cache?.let { return it }
        val out = ArrayList<DocEntry>()
        skipped = 0
        walk(DocumentsContract.getTreeDocumentId(treeUri), "", out)
        cache = out
        return out
    }

    private fun walk(docId: String, prefix: String, out: MutableList<DocEntry>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val dirs = ArrayList<Pair<String, String>>()
        val cursor = context.contentResolver.query(childrenUri, cols, null, null, null)
            ?: throw PushException("Impossible de lire le dossier choisi.")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2) ?: ""
                val size = if (it.isNull(3)) -1L else it.getLong(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name == ".git" || (ignoreGenerated && Generated.isIgnoredDir(name))) {
                        skipped++
                    } else {
                        dirs.add(id to name)
                    }
                } else {
                    val clean = ZipTools.cleanName(prefix + name)
                    if (clean == null) {
                        skipped++
                    } else {
                        out.add(DocEntry(clean, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), size))
                        if (out.size > MAX_FILES) {
                            throw PushException(
                                "Plus de $MAX_FILES fichiers dans ce dossier : c'est trop pour un envoi depuis le téléphone. " +
                                    "Envoie un zip, ou exclus les dossiers générés."
                            )
                        }
                    }
                }
            }
        }
        for ((id, name) in dirs) walk(id, "$prefix$name/", out)
    }

    override fun scan(): ScanResult {
        val list = entries()
        val s = scanDocs(context.contentResolver, list)
        return ScanResult(s.files, s.root, skipped)
    }

    override fun forEach(action: (name: String, bytes: ByteArray) -> Unit) {
        for (e in entries()) action(e.path, readDoc(context.contentResolver, e))
    }

    companion object {
        const val MAX_FILES = 2500

        /** Nom du dossier choisi (pour l'affichage). */
        fun folderName(context: Context, treeUri: Uri): String {
            try {
                val id = DocumentsContract.getTreeDocumentId(treeUri)
                val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                context.contentResolver.query(
                    doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            } catch (e: Exception) {
                // repli ci-dessous
            }
            return "dossier"
        }
    }
}

/** Quelques fichiers choisis un par un (tous placés à la racine, ou dans le dossier cible). */
class FilesSource(private val context: Context, private val uris: List<Uri>) : FileSource {
    private fun entries(): List<DocEntry> {
        val out = ArrayList<DocEntry>()
        for (u in uris) {
            val name = displayName(context, u)
            val clean = ZipTools.cleanName(name) ?: continue
            out.add(DocEntry(clean, u, -1L))
        }
        return out
    }

    override fun scan(): ScanResult = scanDocs(context.contentResolver, entries())

    override fun forEach(action: (name: String, bytes: ByteArray) -> Unit) {
        for (e in entries()) action(e.path, readDoc(context.contentResolver, e))
    }

    companion object {
        fun displayName(context: Context, uri: Uri): String {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (i >= 0) c.getString(i)?.let { if (it.isNotEmpty()) return it }
                        }
                    }
            } catch (e: Exception) {
                // repli ci-dessous
            }
            return uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
        }
    }
}
