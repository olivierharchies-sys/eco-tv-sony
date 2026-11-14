package com.example.gitzippush

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Outils Markdown communs (bloc de code, détection des binaires). */
object MdTools {
    private val BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "jar", "apk", "aab", "so", "zip", "gz", "7z", "rar",
        "tar", "pdf", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "mov", "avi", "wav", "ogg", "keystore",
        "jks", "class", "dex", "bin", "db", "sqlite", "ttc", "exe", "dll", "psd", "ai", "p12", "pkcs12"
    )

    fun binaryByName(path: String): Boolean =
        path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in BINARY_EXT

    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        if (bytes.isEmpty()) return false
        val s = String(bytes, 0, minOf(bytes.size, 20000), Charsets.UTF_8)
        var bad = 0
        for (c in s) if (c == '�') bad++
        return bad * 100 > s.length
    }

    fun fenced(title: String, path: String, text: String): String {
        var run = 0
        var longest = 0
        for (c in text) {
            if (c == '`') {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        val fence = "`".repeat(maxOf(3, longest + 1))
        val ext = path.substringAfterLast('.', "").lowercase()
        val lang = if (ext.length in 1..6 && ext.all { it.isLetterOrDigit() }) ext else ""
        val body = if (text.endsWith("\n")) text else text + "\n"
        return "### $title\n\n$fence$lang\n$body$fence\n\n"
    }
}

/** Un fichier du zip consulté. */
class ZipItem(val path: String, val entryName: String, val size: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** Résultat d'une recherche dans le contenu : numéros de ligne et extraits. */
class ContentHit(val item: ZipItem, val count: Int, val lines: List<Pair<Int, String>>)

/**
 * Lecture d'un zip pour le consulter : liste des fichiers, lecture du texte, recherche par nom et dans le contenu.
 * Les textes lus sont gardés en mémoire (dans une limite raisonnable) pour que les recherches suivantes soient rapides.
 */
class ZipIndex(val file: File) : Closeable {
    private val zip = ZipFile(file)
    val items: List<ZipItem>
    private val byPath: Map<String, ZipItem>

    /** Dossier unique qui contient tout, s'il y en a un (ex. « MonAppli/ »). */
    val root: String?

    private val cache = HashMap<String, String?>()
    private var cachedChars = 0L

    init {
        val list = ArrayList<ZipItem>()
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) continue
            val clean = ZipTools.cleanName(e.name) ?: continue
            list.add(ZipItem(clean, e.name, e.size))
        }
        list.sortBy { it.path.lowercase() }
        items = list
        byPath = list.associateBy { it.path }
        root = ZipTools.detectRoot(list.map { ScannedFile(it.path, it.size, "") })
    }

    fun item(path: String): ZipItem? = byPath[path]

    /** Sous-dossiers et fichiers directement dans [dir] (« » = racine, sinon « a/b/ »). */
    fun children(dir: String): Pair<List<String>, List<ZipItem>> {
        val dirs = LinkedHashSet<String>()
        val files = ArrayList<ZipItem>()
        for (it in items) {
            if (!it.path.startsWith(dir)) continue
            val rest = it.path.substring(dir.length)
            val slash = rest.indexOf('/')
            if (slash < 0) files.add(it) else dirs.add(rest.substring(0, slash))
        }
        return dirs.sortedBy { it.lowercase() } to files
    }

    /** Fichiers sous le dossier [dir] (récursif). */
    fun under(dir: String): List<ZipItem> = items.filter { it.path.startsWith(dir) }

    fun bytes(item: ZipItem): ByteArray {
        val e = zip.getEntry(item.entryName) ?: throw PushException("« ${item.path} » introuvable dans le zip.")
        return zip.getInputStream(e).use { it.readBytes() }
    }

    /** Texte du fichier, ou null s'il est binaire ou trop gros. */
    @Synchronized
    fun text(item: ZipItem): String? {
        if (cache.containsKey(item.path)) return cache[item.path]
        val value: String? = if (item.size > MAX_TEXT_BYTES || MdTools.binaryByName(item.path)) {
            null
        } else {
            val b = bytes(item)
            if (MdTools.looksBinary(b)) null else String(b, Charsets.UTF_8)
        }
        val len = value?.length ?: 0
        if (cachedChars + len <= MAX_CACHE_CHARS) {
            cache[item.path] = value
            cachedChars += len
        }
        return value
    }

    /** Recherche par nom : morceau du chemin, ou motif avec * et ? (ex. « *.kt »). */
    fun searchNames(query: String): List<ZipItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (q.contains('*') || q.contains('?')) {
            val rx = globToRegex(q)
            return items.filter { rx.matches(it.name) || rx.matches(it.path) }
        }
        val terms = q.lowercase().split(Regex("\\s+"))
        return items.filter { i -> val p = i.path.lowercase(); terms.all { p.contains(it) } }
    }

    /** Recherche dans le contenu des fichiers texte (sans tenir compte des majuscules). */
    fun searchContent(
        query: String,
        among: List<ZipItem> = items,
        cancelled: () -> Boolean,
        progress: (Int) -> Unit
    ): List<ContentHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<ContentHit>()
        for ((n, item) in among.withIndex()) {
            if (cancelled()) break
            if (n % 20 == 0) progress(n * 100 / maxOf(1, among.size))
            val text = try {
                text(item)
            } catch (e: Exception) {
                null
            } ?: continue
            if (!text.contains(q, ignoreCase = true)) continue
            var count = 0
            val lines = ArrayList<Pair<Int, String>>()
            var lineNo = 0
            for (line in text.lineSequence()) {
                lineNo++
                if (line.contains(q, ignoreCase = true)) {
                    count++
                    if (lines.size < 3) lines.add(lineNo to line.trim().take(160))
                }
            }
            out.add(ContentHit(item, count, lines))
            if (out.size >= MAX_HITS) break
        }
        progress(100)
        return out
    }

    override fun close() {
        zip.close()
    }

    companion object {
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        const val MAX_CACHE_CHARS = 12_000_000L
        const val MAX_HITS = 500

        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            for (c in glob) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

/** Fichiers Markdown préparés pour Claude. */
class Bundle(val parts: List<File>, val chars: Int, val included: Int, val skipped: List<String>, val label: String) {
    /** Texte complet (pour le presse-papiers) si une seule partie raisonnable. */
    fun singleText(): String? =
        if (parts.size == 1 && chars <= ClaudeBundle.CLIP_MAX) parts[0].readText(Charsets.UTF_8) else null
}

/** Met une sélection de fichiers (ou un extrait) en Markdown, découpé en parties si besoin. */
object ClaudeBundle {
    const val PART_CHARS = 300_000
    const val MAX_PARTS = 8
    const val CLIP_MAX = 200_000

    fun dir(ctx: android.content.Context): File = File(ctx.cacheDir, "claude").also { it.mkdirs() }

    private fun clean(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(50).ifEmpty { "selection" }

    /** [title] : nom du projet (ex. nom du zip) ; [paths] : fichiers choisis. */
    fun build(
        ctx: android.content.Context, index: ZipIndex, title: String, paths: List<String>, note: String? = null
    ): Bundle {
        val out = dir(ctx)
        clean(out)
        val prefix = index.root?.let { "$it/" } ?: ""
        val chunks = ArrayList<StringBuilder>()
        var cur = StringBuilder()
        chunks.add(cur)
        val skipped = ArrayList<String>()
        var included = 0
        val sorted = paths.distinct().sortedBy { it.lowercase() }
        for (p in sorted) {
            val item = index.item(p) ?: continue
            val text = try {
                index.text(item)
            } catch (e: Exception) {
                null
            }
            val shown = p.removePrefix(prefix)
            if (text == null) {
                skipped.add(shown)
                continue
            }
            val block = MdTools.fenced(shown, shown, text)
            if (cur.length + block.length > PART_CHARS && cur.isNotEmpty()) {
                if (chunks.size >= MAX_PARTS) {
                    skipped.add(shown)
                    continue
                }
                cur = StringBuilder()
                chunks.add(cur)
            }
            cur.append(block)
            included++
        }

        val header = StringBuilder()
        header.append("# ").append(title).append(" — ").append(included).append(" fichier(s)\n\n")
        if (!note.isNullOrBlank()) header.append(note.trim()).append("\n\n")
        header.append("## Fichiers choisis\n\n```\n")
        for (p in sorted) header.append(p.removePrefix(prefix)).append('\n')
        header.append("```\n")
        if (skipped.isNotEmpty()) {
            header.append("\nNon inclus (binaires, trop gros ou au-delà de la limite) : ")
                .append(skipped.take(40).joinToString(", "))
            if (skipped.size > 40) header.append("…")
            header.append("\n")
        }
        header.append("\n## Contenu\n\n")
        return write(out, title, header.toString(), chunks, included, skipped)
    }

    /** Un extrait de fichier (lignes [fromLine]..[toLine]). */
    fun excerpt(
        ctx: android.content.Context, title: String, path: String, text: String, fromLine: Int, toLine: Int
    ): Bundle {
        val out = dir(ctx)
        clean(out)
        val header = "# $title — extrait de $path (lignes $fromLine à $toLine)\n\n"
        val chunk = StringBuilder(MdTools.fenced("$path, lignes $fromLine-$toLine", path, text))
        return write(out, title, header, arrayListOf(chunk), 1, emptyList())
    }

    private fun write(
        out: File, title: String, header: String, chunks: List<StringBuilder>, included: Int, skipped: List<String>
    ): Bundle {
        val base = safeName(title)
        val files = ArrayList<File>()
        var chars = 0
        for ((i, c) in chunks.withIndex()) {
            val sb = StringBuilder()
            if (chunks.size > 1) sb.append("<!-- Partie ${i + 1} sur ${chunks.size} -->\n\n")
            if (i == 0) sb.append(header) else sb.append("(suite de $title)\n\n")
            sb.append(c)
            val name = if (chunks.size == 1) "$base.md" else "$base-partie${i + 1}sur${chunks.size}.md"
            val f = File(out, name)
            f.writeText(sb.toString(), Charsets.UTF_8)
            chars += sb.length
            files.add(f)
        }
        val label = if (included == 1) "1 fichier" else "$included fichiers"
        return Bundle(files, chars, included, skipped, label)
    }
}
