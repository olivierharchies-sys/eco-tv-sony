package com.example.gitzippush

import android.util.Base64

/** Source dont un fichier est remplacé par [bytes] (empreinte recalculée). */
class ReplacedSource(
    private val base: FileSource,
    private val name: String,
    private val bytes: ByteArray
) : FileSource {
    override fun scan(): ScanResult {
        val s = base.scan()
        val files = s.files.map {
            if (it.name == name) ScannedFile(name, bytes.size.toLong(), ZipTools.gitBlobSha(bytes)) else it
        }
        return ScanResult(files, s.root, s.skipped)
    }

    override fun forEach(action: (name: String, bytes: ByteArray) -> Unit) {
        base.forEach { n, b -> action(n, if (n == name) bytes else b) }
    }

    override fun readText(name: String): String? =
        if (name == this.name) String(bytes, Charsets.UTF_8) else base.readText(name)
}

/**
 * Incrément automatique de versionCode dans app/build.gradle(.kts) :
 * si le zip a la même valeur (ou moins) que le dépôt, il reçoit « valeur du dépôt + 1 ».
 * Rien n'est fait si aucun autre fichier n'a changé (pas de commit inutile).
 */
object VersionBump {
    private val CODE = Regex("(versionCode\\s*=?\\s*)(\\d+)")

    fun code(text: String?): Int? = text?.let { CODE.find(it)?.groupValues?.get(2)?.toIntOrNull() }

    /** Renvoie la source modifiée, ou null si inutile. Hors fil UI. */
    fun prepare(
        api: GitHubApi, source: FileSource, owner: String, repo: String, branch: String,
        stripRoot: Boolean, targetDir: String, log: (String) -> Unit
    ): FileSource? {
        val scan = source.scan()
        val prefix = if (stripRoot && scan.root != null) scan.root + "/" else ""
        val dir = targetDir.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }
        fun repoPath(n: String) = dir + n.removePrefix(prefix)

        val gradle = scan.files.firstOrNull {
            val p = repoPath(it.name)
            p == dir + "app/build.gradle.kts" || p == dir + "app/build.gradle"
        } ?: return null
        val localText = source.readText(gradle.name) ?: return null
        val local = code(localText) ?: return null

        val base = "/repos/$owner/$repo"
        val ref = branch.trim().ifEmpty {
            api.get(base).json?.optString("default_branch").orEmpty().ifEmpty { "main" }
        }
        val tree = api.get("$base/git/trees/${android.net.Uri.encode(ref)}?recursive=1")
        if (!tree.ok) return null // dépôt vide ou branche nouvelle : rien à comparer
        val remote = HashMap<String, String>()
        val arr = tree.json?.optJSONArray("tree") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "blob") remote[o.optString("path")] = o.optString("sha")
        }
        val gradlePath = repoPath(gradle.name)
        val otherChanges = scan.files.any { it.name != gradle.name && remote[repoPath(it.name)] != it.sha }
        if (!otherChanges) return null

        val r = api.get("$base/contents/${gradlePath.split('/').joinToString("/") { android.net.Uri.encode(it) }}?ref=${android.net.Uri.encode(ref)}")
        val remoteText = r.json?.optString("content")?.takeIf { r.ok && it.isNotEmpty() }?.let {
            try {
                String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
        val remoteCode = code(remoteText) ?: return null
        if (local > remoteCode) return null
        val next = remoteCode + 1
        val newText = CODE.replaceFirst(localText, "$1$next")
        log("versionCode : $remoteCode → $next ($gradlePath)")
        return ReplacedSource(source, gradle.name, newText.toByteArray(Charsets.UTF_8))
    }
}
