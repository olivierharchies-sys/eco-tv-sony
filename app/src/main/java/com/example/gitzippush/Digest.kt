package com.example.gitzippush

import java.io.File
import java.net.URLEncoder
import java.util.zip.ZipFile

class DigestResult(
    val parts: List<File>,
    val zip: File,
    val chars: Int,
    val included: Int,
    val omitted: Int,
    val total: Int
)

/**
 * Transforme un dépôt GitHub en document Markdown prêt à être joint à une conversation Claude :
 * arborescence + contenu des fichiers texte, découpé en parties si le dépôt est gros.
 * Le zip brut du dépôt est aussi conservé (pour les conversations où Claude peut exécuter du code).
 */
class RepoDigest(
    private val api: GitHubApi,
    private val log: (String) -> Unit,
    private val progress: (percent: Int) -> Unit,
    private val cancelled: () -> Boolean
) {
    private class Item(val path: String, val entryName: String, val size: Long)

    fun build(owner: String, repo: String, branchIn: String, outDir: File): DigestResult {
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }

        val info = api.get("/repos/$owner/$repo")
        if (!info.ok) throw GitHubApi.explain(info, "Dépôt $owner/$repo")
        val defaultBranch = info.json?.optString("default_branch", "main").orEmpty().ifEmpty { "main" }
        val branch = branchIn.trim().ifEmpty { defaultBranch }
        val description = info.json?.optString("description", "").orEmpty()

        log("Téléchargement de $owner/$repo ($branch)…")
        val zip = File(outDir, "$repo.zip")
        val enc = branch.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        api.download(
            "/repos/$owner/$repo/zipball/$enc", zip, 0L,
            { done, total -> if (total > 0) progress((done * 60 / total).toInt()) },
            cancelled
        )

        log("Mise en forme pour Claude…")
        val items = ArrayList<Item>()
        ZipFile(zip).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                val rel = e.name.substringAfter('/', "")
                val path = ZipTools.cleanName(rel) ?: continue
                items.add(Item(path, e.name, e.size))
            }
        }
        items.sortWith(compareBy<Item>({ priority(it.path) }, { it.path }))

        // partie de texte en cours
        val chunks = ArrayList<StringBuilder>()
        var cur = StringBuilder()
        chunks.add(cur)
        val notes = HashMap<String, String>()
        var included = 0
        var budgetLeft = true

        ZipFile(zip).use { zf ->
            for ((i, it) in items.withIndex()) {
                if (cancelled()) throw PushException("Annulé.")
                progress(60 + (i * 40 / maxOf(1, items.size)))
                val why = skipReason(it)
                if (why != null) {
                    notes[it.path] = why
                    continue
                }
                if (!budgetLeft) {
                    notes[it.path] = "volume"
                    continue
                }
                val entry = zf.getEntry(it.entryName) ?: continue
                val bytes = zf.getInputStream(entry).use { s -> s.readBytes() }
                if (looksBinary(bytes)) {
                    notes[it.path] = "binaire"
                    continue
                }
                val text = String(bytes, Charsets.UTF_8)
                val block = fenced(it.path, text)
                if (cur.length + block.length > PART_CHARS && cur.isNotEmpty()) {
                    if (chunks.size >= MAX_PARTS) {
                        budgetLeft = false
                        notes[it.path] = "volume"
                        continue
                    }
                    cur = StringBuilder()
                    chunks.add(cur)
                }
                cur.append(block)
                included++
            }
        }

        val header = StringBuilder()
        header.append("# ").append(owner).append('/').append(repo).append(" (branche ").append(branch).append(")\n\n")
        if (description.isNotBlank()) header.append(description.trim()).append("\n\n")
        header.append("Dépôt GitHub importé : ").append(items.size).append(" fichier(s), ")
            .append(included).append(" inclus ci-dessous.\n")
        header.append("Les fichiers binaires, trop gros ou générés sont seulement listés dans l'arborescence.\n\n")
        header.append("## Arborescence\n\n").append(tree(items, notes)).append("\n## Fichiers\n\n")

        val partCount = chunks.size
        val files = ArrayList<File>()
        var chars = 0
        for ((i, c) in chunks.withIndex()) {
            val sb = StringBuilder()
            if (partCount > 1) sb.append("<!-- Partie ${i + 1} sur $partCount de $owner/$repo -->\n\n")
            if (i == 0) sb.append(header) else sb.append("(suite des fichiers de $owner/$repo)\n\n")
            sb.append(c)
            val name = if (partCount == 1) "$repo.md" else "$repo-partie${i + 1}sur$partCount.md"
            val f = File(outDir, name)
            f.writeText(sb.toString(), Charsets.UTF_8)
            chars += sb.length
            files.add(f)
        }
        progress(100)
        return DigestResult(files, zip, chars, included, items.size - included, items.size)
    }

    private fun priority(path: String): Int {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return when {
            name.startsWith("readme") && !path.contains('/') -> 0
            !path.contains('/') -> 1
            lower.endsWith("androidmanifest.xml") || name.startsWith("build.gradle") -> 2
            else -> 3
        }
    }

    private fun skipReason(it: Item): String? {
        val name = it.path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            Generated.isIgnoredPath(it.path) -> "généré"
            name in LOCK_FILES -> "verrou"
            ext in BINARY_EXT -> "binaire"
            it.size > MAX_FILE_BYTES -> "trop gros"
            else -> null
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        if (bytes.isEmpty()) return false
        val s = String(bytes, 0, minOf(bytes.size, 20000), Charsets.UTF_8)
        var bad = 0
        for (c in s) if (c == '�') bad++
        return bad * 100 > s.length
    }

    private fun fenced(path: String, text: String): String {
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
        return "### $path\n\n$fence$lang\n$body$fence\n\n"
    }

    private fun tree(items: List<Item>, notes: Map<String, String>): String {
        val sorted = items.map { it.path }.sorted()
        val sb = StringBuilder("```\n")
        val printed = HashSet<String>()
        var lines = 0
        for (p in sorted) {
            if (lines >= MAX_TREE_LINES) {
                sb.append("… (liste tronquée)\n")
                break
            }
            val parts = p.split('/')
            var acc = ""
            for (d in 0 until parts.size - 1) {
                acc += parts[d] + "/"
                if (printed.add(acc)) {
                    sb.append("  ".repeat(d)).append(parts[d]).append("/\n")
                    lines++
                }
            }
            sb.append("  ".repeat(parts.size - 1)).append(parts.last())
            notes[p]?.let { sb.append("  (omis : ").append(it).append(")") }
            sb.append('\n')
            lines++
        }
        sb.append("```\n")
        return sb.toString()
    }

    companion object {
        const val PART_CHARS = 300_000
        const val MAX_PARTS = 8
        const val MAX_FILE_BYTES = 150_000L
        const val MAX_TREE_LINES = 3000
        private val LOCK_FILES = setOf(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "Cargo.lock", "poetry.lock",
            "composer.lock", "gradle.lockfile", "Gemfile.lock"
        )
        private val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "jar", "apk", "aab", "so", "zip", "gz", "7z", "rar",
            "tar", "pdf", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "mov", "avi", "wav", "ogg", "keystore",
            "jks", "class", "dex", "bin", "db", "sqlite", "ttc", "exe", "dll", "psd", "ai"
        )
    }
}
