package com.example.gitzippush

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Un fichier du zip : nom nettoyé (chemin relatif dans le zip), taille et empreinte Git. */
class ScannedFile(val name: String, val size: Long, val sha: String)

/** Résultat de l'analyse d'un zip. [root] = dossier unique qui contient tout, s'il y en a un. */
class ScanResult(val files: List<ScannedFile>, val root: String?, val skipped: Int)

object ZipTools {

    /** GitHub refuse les fichiers de plus de 100 Mo. */
    const val MAX_FILE: Long = 95L * 1024 * 1024

    /**
     * Nettoie un nom d'entrée de zip. Renvoie null si l'entrée doit être ignorée
     * (dossier, métadonnées macOS/Windows, dossier .git, chemin dangereux).
     */
    fun cleanName(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || raw.endsWith("/") || raw.endsWith("\\")) return null
        if (parts.any { it == ".." }) return null
        if (parts[0] == "__MACOSX") return null
        if (parts.any { it == ".git" }) return null
        val last = parts.last()
        if (last == ".DS_Store" || last == "Thumbs.db") return null
        return parts.joinToString("/")
    }

    /** Empreinte Git d'un fichier : SHA-1 de « blob <taille>\0<contenu> ». */
    fun gitBlobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(("blob " + bytes.size + "\u0000").toByteArray(Charsets.US_ASCII))
        md.update(bytes)
        val out = StringBuilder()
        for (b in md.digest()) {
            out.append(String.format("%02x", b.toInt() and 0xff))
        }
        return out.toString()
    }

    /**
     * Parcourt les fichiers du zip (un seul passage) et appelle [action] avec le nom nettoyé et le contenu.
     * Si un nom apparaît deux fois, les deux sont transmis dans l'ordre du zip.
     */
    fun forEachFile(open: () -> InputStream, action: (name: String, bytes: ByteArray) -> Unit): Int {
        var skipped = 0
        ZipInputStream(BufferedInputStream(open())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = cleanName(entry.name)
                if (name == null) {
                    skipped++
                    continue
                }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE) {
                        throw PushException("« $name » dépasse 95 Mo : GitHub refuse les fichiers de plus de 100 Mo.")
                    }
                    out.write(buffer, 0, n)
                }
                action(name, out.toByteArray())
            }
        }
        return skipped
    }

    fun scan(open: () -> InputStream): ScanResult {
        val files = LinkedHashMap<String, ScannedFile>()
        val skipped = try {
            forEachFile(open) { name, bytes ->
                files[name] = ScannedFile(name, bytes.size.toLong(), gitBlobSha(bytes))
            }
        } catch (e: PushException) {
            throw e
        } catch (e: java.util.zip.ZipException) {
            throw PushException("Ce fichier n'est pas un zip valide : ${e.message}")
        } catch (e: java.io.IOException) {
            throw PushException("Lecture du zip impossible : ${e.message}")
        }
        val list = files.values.toList()
        return ScanResult(list, detectRoot(list), skipped)
    }

    /**
     * Dossiers qui font partie du projet lui-même : un zip qui ne contient que « app/… »
     * ne doit pas perdre ce dossier (sinon les fichiers arrivent à la racine du dépôt).
     */
    private val PROJECT_FOLDERS = setOf(
        "app", "src", "gradle", ".github", "lib", "libs", "assets", "res",
        "android", "ios", "web", "public", "test", "tests", "docs", "scripts"
    )

    fun isProjectFolder(name: String?): Boolean = name != null && name.lowercase() in PROJECT_FOLDERS

    /** Dossier racine commun, si tous les fichiers sont dans le même dossier de premier niveau. */
    fun detectRoot(files: List<ScannedFile>): String? {
        if (files.isEmpty()) return null
        var root: String? = null
        for (f in files) {
            val slash = f.name.indexOf('/')
            if (slash <= 0) return null
            val first = f.name.substring(0, slash)
            if (root == null) root = first else if (root != first) return null
        }
        return root
    }
}
