package com.example.gitzippush

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Une copie du zip envoyé. [state] : « pending » tant que la compilation GitHub n'est pas terminée,
 * « ok » quand elle a réussi (seules celles-ci sont gardées).
 */
class ArchiveEntry(
    val id: String,
    val repo: String,
    val branch: String,
    val sha: String,
    val source: String,
    val fileName: String,
    val size: Long,
    val createdAt: Long,
    val state: String,
    val builtAt: Long,
    val runUrl: String,
    val publicPath: String,
    /** Lien du zip d'origine sur le téléphone (vide si dossier ou fichiers). */
    val srcUri: String = ""
) {
    val isSaved: Boolean get() = state == BuildArchive.OK

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("repo", repo).put("branch", branch).put("sha", sha).put("source", source)
        .put("file", fileName).put("size", size).put("t", createdAt).put("state", state)
        .put("built", builtAt).put("run", runUrl).put("pub", publicPath).put("src", srcUri)

    fun with(state: String, builtAt: Long, runUrl: String, publicPath: String) = ArchiveEntry(
        id, repo, branch, sha, source, fileName, size, createdAt, state, builtAt, runUrl, publicPath, srcUri
    )

    companion object {
        fun of(o: JSONObject) = ArchiveEntry(
            o.optString("id"), o.optString("repo"), o.optString("branch"), o.optString("sha"),
            o.optString("source"), o.optString("file"), o.optLong("size"), o.optLong("t"),
            o.optString("state"), o.optLong("built"), o.optString("run"), o.optString("pub"),
            o.optString("src")
        )
    }
}

/**
 * Sauvegardes des zips envoyés dont la compilation GitHub Actions a réussi.
 *
 * À chaque envoi qui crée un commit, une copie du zip est mise de côté (« en attente »).
 * Quand la compilation de ce commit réussit, la copie devient une sauvegarde ; si elle échoue, la copie est supprimée.
 */
object BuildArchive {
    const val PENDING = "pending"
    const val OK = "ok"

    /** Copie en attente sans aucune compilation au bout de ce délai : le dépôt n'a pas de workflow. */
    private const val NO_BUILD_AFTER_MS = 60L * 60 * 1000
    private const val GIVE_UP_AFTER_MS = 2L * 24 * 60 * 60 * 1000

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    /** Appelé (sur le fil principal) quand la liste change : l'écran se met à jour. */
    @Volatile
    var listener: (() -> Unit)? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "archives").also { it.mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")
    fun fileOf(ctx: Context, e: ArchiveEntry) = File(dir(ctx), e.fileName)

    // ------------------------------------------------------------------ index

    private fun load(ctx: Context): MutableList<ArchiveEntry> {
        val f = indexFile(ctx)
        if (!f.isFile) return ArrayList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<ArchiveEntry>()
            for (i in 0 until arr.length()) out.add(ArchiveEntry.of(arr.getJSONObject(i)))
            out
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun save(ctx: Context, list: List<ArchiveEntry>) {
        val arr = JSONArray()
        for (e in list) arr.put(e.toJson())
        val tmp = File(dir(ctx), "index.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        tmp.renameTo(indexFile(ctx))
    }

    private fun changed() {
        main.post { listener?.invoke() }
    }

    /** Signale un changement (sauvegardes ou rapports) à l'écran. */
    fun notifyChanged() = changed()

    /** Toutes les copies, les plus récentes d'abord. */
    fun list(ctx: Context): List<ArchiveEntry> = synchronized(lock) {
        load(ctx).filter { fileOf(ctx, it).isFile }.sortedByDescending { it.createdAt }
    }

    fun saved(ctx: Context) = list(ctx).filter { it.isSaved }
    fun pending(ctx: Context) = list(ctx).filter { it.state == PENDING }
    fun get(ctx: Context, id: String) = list(ctx).firstOrNull { it.id == id }

    fun remove(ctx: Context, id: String) {
        synchronized(lock) {
            val all = load(ctx)
            val e = all.firstOrNull { it.id == id } ?: return
            fileOf(ctx, e).delete()
            all.remove(e)
            save(ctx, all)
        }
        changed()
    }

    // ------------------------------------------------------------------ création

    /**
     * Met de côté une copie de ce qui vient d'être envoyé, en attendant le résultat de la compilation.
     * [write] écrit le contenu du zip.
     */
    fun createPending(
        ctx: Context, repo: String, branch: String, sha: String, source: String,
        write: (OutputStream) -> Unit, srcUri: String = ""
    ): ArchiveEntry {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.FRANCE).format(Date(now))
        val repoName = safe(repo.substringAfter('/'))
        val name = "${repoName}_${stamp}_${sha.take(7)}.zip"
        val target = File(dir(ctx), name)
        val tmp = File(dir(ctx), "$name.part")
        try {
            FileOutputStream(tmp).use { write(it) }
            if (!tmp.renameTo(target)) throw PushException("Copie de sauvegarde impossible.")
        } finally {
            tmp.delete()
        }
        val e = ArchiveEntry(
            id = sha + "_" + now, repo = repo, branch = branch, sha = sha, source = source,
            fileName = name, size = target.length(), createdAt = now, state = PENDING,
            builtAt = 0L, runUrl = "", publicPath = "", srcUri = srcUri
        )
        synchronized(lock) {
            val all = load(ctx)
            all.add(e)
            save(ctx, all)
        }
        changed()
        return e
    }

    /** Copie brute d'un zip. */
    fun copyZip(open: () -> InputStream): (OutputStream) -> Unit = { out -> open().use { it.copyTo(out) } }

    /** Zip fabriqué à partir d'un dossier ou de fichiers choisis. */
    fun zipOf(source: FileSource): (OutputStream) -> Unit = { out ->
        ZipOutputStream(out).use { zip ->
            val seen = HashSet<String>()
            source.forEach { name, bytes ->
                if (seen.add(name)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "depot" }

    // ------------------------------------------------------------------ résultat de la compilation

    /**
     * La compilation du commit [sha] a réussi : les copies en attente deviennent des sauvegardes,
     * et chacune est copiée dans Documents/Github/<dépôt>/ (3 derniers zips gardés). [log] reçoit les comptes rendus.
     */
    fun onBuildSuccess(ctx: Context, repo: String, sha: String, runUrl: String, log: (String) -> Unit = {}): Int {
        val promoted = ArrayList<ArchiveEntry>()
        synchronized(lock) {
            val all = load(ctx)
            for (i in all.indices) {
                val e = all[i]
                if (e.state == PENDING && e.sha == sha && e.repo.equals(repo, ignoreCase = true)) {
                    all[i] = e.with(OK, System.currentTimeMillis(), runUrl, "")
                    promoted.add(all[i])
                }
            }
            if (promoted.isNotEmpty()) save(ctx, all)
        }
        if (promoted.isEmpty()) return 0
        val settings = Settings(ctx)
        if (settings.archivePublic) for (e in promoted) exportPublic(ctx, e)
        if (settings.archiveDocs) {
            for (e in promoted) {
                val path = DocsArchive.save(ctx, e.repo, e.fileName, fileOf(ctx, e))
                if (path != null) {
                    log("Copie du zip : $path (les ${DocsArchive.KEEP} derniers sont gardés).")
                } else {
                    log("Copie dans ${DocsArchive.label(e.repo) ?: "Documents/${DocsArchive.ROOT}"} impossible : autorise l'accès aux fichiers (Envoyer > Autoriser l'accès).")
                }
            }
        }
        prune(ctx, settings.archiveKeep)
        changed()
        return promoted.size
    }

    /**
     * Retire les copies en attente du commit [sha]. Si [deleteSource] (vraie erreur de compilation) et le réglage
     * l'autorise, supprime aussi le zip d'origine du téléphone. Renvoie le nombre de zips d'origine supprimés.
     */
    fun onBuildFailed(ctx: Context, repo: String, sha: String, deleteSource: Boolean = false): Int {
        val drop: List<ArchiveEntry>
        synchronized(lock) {
            val all = load(ctx)
            drop = all.filter { it.state == PENDING && it.sha == sha && it.repo.equals(repo, ignoreCase = true) }
            if (drop.isEmpty()) return 0
            for (e in drop) fileOf(ctx, e).delete()
            all.removeAll(drop.toSet())
            save(ctx, all)
        }
        var deleted = 0
        if (deleteSource && Settings(ctx).deleteFailedZip) {
            for (e in drop) {
                if (SourceZip.delete(ctx, e.srcUri, e.source, e.size)) deleted++
            }
        }
        changed()
        return deleted
    }

    /** Ne garde que les [keep] sauvegardes les plus récentes (0 = toutes). */
    fun prune(ctx: Context, keep: Int) {
        if (keep <= 0) return
        synchronized(lock) {
            val all = load(ctx)
            val ok = all.filter { it.isSaved }.sortedByDescending { it.createdAt }
            if (ok.size <= keep) return
            val drop = ok.drop(keep)
            for (e in drop) fileOf(ctx, e).delete()
            all.removeAll(drop.toSet())
            save(ctx, all)
        }
    }

    /** Nouveau lien du zip d'origine (après son rangement dans le dossier du dépôt). */
    fun setSource(ctx: Context, repo: String, sha: String, srcUri: String) {
        synchronized(lock) {
            val all = load(ctx)
            var changed = false
            for (i in all.indices) {
                val e = all[i]
                if (e.sha == sha && e.repo.equals(repo, ignoreCase = true)) {
                    all[i] = ArchiveEntry(
                        e.id, e.repo, e.branch, e.sha, e.source, e.fileName, e.size, e.createdAt,
                        e.state, e.builtAt, e.runUrl, e.publicPath, srcUri
                    )
                    changed = true
                }
            }
            if (changed) save(ctx, all)
        }
    }

    /** Copie la sauvegarde dans Téléchargements/Envoi GitHub/<dépôt>/Compilations réussies. */
    fun exportPublic(ctx: Context, e: ArchiveEntry): String? {
        val path = PublicFiles.save(ctx, "Compilations réussies", e.fileName, fileOf(ctx, e), "application/zip", e.repo)
            ?: return null
        synchronized(lock) {
            val all = load(ctx)
            val i = all.indexOfFirst { it.id == e.id }
            if (i >= 0) {
                all[i] = all[i].with(all[i].state, all[i].builtAt, all[i].runUrl, path)
                save(ctx, all)
            }
        }
        changed()
        return path
    }

    // ------------------------------------------------------------------ interrogation de GitHub

    enum class Outcome { WAITING, SUCCESS, FAILED, NO_BUILD }

    class Check(val outcome: Outcome, val runUrl: String, val detail: String, val failedRun: JSONObject? = null)

    /** État des compilations GitHub Actions du commit de [e]. */
    fun check(api: GitHubApi, e: ArchiveEntry): Check {
        val reply = api.get("/repos/${e.repo}/actions/runs?head_sha=${e.sha}&per_page=30")
        if (!reply.ok) throw GitHubApi.explain(reply, "Lecture des compilations")
        val runs = reply.json?.optJSONArray("workflow_runs")
        val age = System.currentTimeMillis() - e.createdAt
        if (runs == null || runs.length() == 0) {
            return if (age > NO_BUILD_AFTER_MS) {
                Check(Outcome.NO_BUILD, "", "aucune compilation pour ce commit")
            } else {
                Check(Outcome.WAITING, "", "compilation pas encore démarrée")
            }
        }
        var success: String? = null
        var failed: String? = null
        var failedRun: JSONObject? = null
        var running = 0
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("status") != "completed") {
                running++
                continue
            }
            when (val c = r.optString("conclusion")) {
                "success" -> if (success == null) success = r.optString("html_url")
                "skipped", "neutral" -> {}
                else -> if (failed == null) {
                    failed = c
                    failedRun = r
                }
            }
        }
        return when {
            failed != null -> Check(Outcome.FAILED, "", "compilation échouée ($failed)", failedRun)
            running > 0 -> Check(Outcome.WAITING, "", "compilation en cours")
            success != null -> Check(Outcome.SUCCESS, success, "compilation réussie")
            else -> Check(Outcome.NO_BUILD, "", "aucune compilation utile")
        }
    }

    /** Rapport (si échec) et notification de fin de compilation. Hors fil UI. */
    fun notifyResult(ctx: Context, api: GitHubApi, e: ArchiveEntry, c: Check, log: (String) -> Unit) {
        when (c.outcome) {
            Outcome.SUCCESS -> Notifier.buildSuccess(ctx, e)
            Outcome.FAILED -> {
                val f = ErrorReport.copyBuildFailure(ctx, api, e.repo, c)
                if (f != null) log("📋 Rapport COURT copié : colle-le dans Claude (moyen/complet : tuile Rapports).")
                Notifier.buildFailed(ctx, e, f, c.failedRun?.optLong("id") ?: 0L, c.detail)
            }
            else -> {}
        }
    }

    /** Applique le résultat d'une vérification. Renvoie une ligne de compte rendu. */
    fun apply(ctx: Context, e: ArchiveEntry, c: Check): String {
        val label = "${e.repo} (${e.sha.take(7)})"
        return when (c.outcome) {
            Outcome.SUCCESS -> {
                val lines = ArrayList<String>()
                onBuildSuccess(ctx, e.repo, e.sha, c.runUrl) { lines.add(it) }
                "✓ $label : compilation réussie, zip sauvegardé." + lines.joinToString("") { "\n$it" }
            }
            Outcome.FAILED -> {
                val gone = onBuildFailed(ctx, e.repo, e.sha, deleteSource = true)
                "✗ $label : ${c.detail}, copie supprimée" +
                    (if (gone > 0) ", zip « ${e.source} » supprimé du téléphone." else ".")
            }
            Outcome.NO_BUILD -> {
                onBuildFailed(ctx, e.repo, e.sha)
                "$label : ${c.detail}, copie supprimée (le dépôt a-t-il un workflow ?)."
            }
            Outcome.WAITING -> {
                if (System.currentTimeMillis() - e.createdAt > GIVE_UP_AFTER_MS) {
                    onBuildFailed(ctx, e.repo, e.sha)
                    "$label : toujours rien après 2 jours, copie supprimée."
                } else {
                    "… $label : ${c.detail}."
                }
            }
        }
    }
}

/**
 * Surveille en arrière-plan la compilation d'un commit qui vient d'être envoyé (25 minutes au plus),
 * puis garde ou supprime la copie du zip. Une seule surveillance par commit.
 */
object BuildWatcher {
    private val watching = HashSet<String>()

    fun isWatching(sha: String) = synchronized(watching) { sha in watching }

    fun watch(ctx: Context, token: String, entry: ArchiveEntry, log: (String) -> Unit) {
        synchronized(watching) { if (!watching.add(entry.sha)) return }
        val app = ctx.applicationContext
        val handle = TaskHandle(app, "Compilation de ${entry.repo}")
        Thread {
            var line = ""
            var ok = false
            try {
                val api = GitHubApi(token)
                val start = System.currentTimeMillis()
                while (true) {
                    val current = BuildArchive.get(app, entry.id)
                    if (current == null || current.state != BuildArchive.PENDING) {
                        ok = current != null
                        line = if (ok) "Compilation réussie : zip sauvegardé" else "Copie retirée"
                        break
                    }
                    val c = BuildArchive.check(api, current)
                    if (c.outcome != BuildArchive.Outcome.WAITING || System.currentTimeMillis() - start > 25 * 60 * 1000L) {
                        if (c.outcome == BuildArchive.Outcome.WAITING) {
                            line = "Compilation toujours en cours : vérifie plus tard (tuile Historique)"
                            ok = true
                        } else {
                            val msg = BuildArchive.apply(app, current, c)
                            log(msg)
                            BuildArchive.notifyResult(app, api, current, c, log)
                            ok = c.outcome == BuildArchive.Outcome.SUCCESS
                            line = if (ok) "Compilation réussie : zip sauvegardé" else "Pas de sauvegarde : ${c.detail}"
                        }
                        break
                    }
                    handle.progress(null, c.detail)
                    Thread.sleep(15_000)
                }
            } catch (e: InterruptedException) {
                line = "Surveillance interrompue"
            } catch (e: PushException) {
                line = e.message ?: "Erreur"
            } catch (e: Exception) {
                line = "Erreur : ${e.javaClass.simpleName}"
            } finally {
                synchronized(watching) { watching.remove(entry.sha) }
                if (ok) handle.done(line) else handle.fail(line)
            }
        }.start()
    }
}

/** Enregistre des fichiers dans le dossier public Téléchargements/Envoi GitHub/… (visible dans « Fichiers »). */
object PublicFiles {
    const val ROOT = "Envoi GitHub"

    /** Nom de dossier d'un dépôt (« pseudo/depot » → « depot »). */
    fun repoDir(repo: String?): String? =
        repo?.substringAfter('/')?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.trim()?.take(60)?.ifEmpty { null }

    /**
     * Copie dans Téléchargements/Envoi GitHub/[repo]/[sub] (sans dépôt : Envoi GitHub/[sub]).
     * Renvoie le chemin affichable, ou null si ce téléphone ne le permet pas.
     */
    @Suppress("DEPRECATION")
    fun save(ctx: Context, subDir: String, name: String, src: File, mime: String, repo: String? = null): String? {
        val sub = repoDir(repo)?.let { "$it/$subDir" } ?: subDir
        val shown = "Téléchargements/$ROOT/$sub/$name"
        try {
            if (Downloads.hasAccess()) {
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(base, "$ROOT/$sub")
                dir.mkdirs()
                src.copyTo(File(dir, name), overwrite = true)
                return shown
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$ROOT/$sub")
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    ?: return null
                return shown
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}
