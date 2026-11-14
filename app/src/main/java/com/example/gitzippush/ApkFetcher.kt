package com.example.gitzippush

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.zip.ZipInputStream

class FetchResult(val apk: File, val artifactName: String, val sha: String, val runUrl: String)

/**
 * Récupère l'APK de la dernière compilation GitHub Actions : attend la fin de la compilation si besoin,
 * télécharge l'artefact (un zip) et en extrait le fichier .apk.
 */
class ApkFetcher(
    private val api: GitHubApi,
    private val workDir: File,
    private val log: (String) -> Unit,
    private val progress: (percent: Int) -> Unit,
    private val cancelled: () -> Boolean
) {

    /**
     * @param expectedSha si non null : on attend la compilation de CE commit (utile juste après un envoi).
     * @param branch vide = toutes les branches (la plus récente).
     */
    fun fetch(owner: String, repo: String, branch: String, expectedSha: String?): FetchResult {
        val base = "/repos/$owner/$repo"

        workDir.mkdirs()
        workDir.listFiles()?.forEach { it.delete() }

        var run = try {
            waitForRun(base, branch.trim(), expectedSha)
        } catch (e: BuildFailedException) {
            // sans commit précis : on se rabat sur la dernière compilation réussie
            if (expectedSha != null) throw e
            val ok = successWithArtifact(base, branch.trim(), -1L) ?: throw e
            log("⚠ Dernière compilation échouée (${e.run.optString("head_sha").take(7)}) : " +
                "installation de la dernière réussie (${ok.first.optString("head_sha").take(7)}). Voir Rapports d'erreur.")
            ok.first
        }

        // --- artefact de cette compilation
        var artifact = artifactOf(base, run.optLong("id"))
        if (artifact == null && expectedSha == null) {
            successWithArtifact(base, branch.trim(), run.optLong("id"))?.let {
                log("⚠ Cette compilation n'a pas d'APK (expiré ou supprimé) : version précédente (${it.first.optString("head_sha").take(7)}).")
                run = it.first
                artifact = it.second
            }
        }
        val art = artifact ?: throw PushException(
            "Aucun APK téléchargeable : artefact absent, expiré (rétention) ou supprimé par le nettoyage. " +
                "Relance une compilation (Outils GitHub > Lancer compilation)."
        )
        val name = art.optString("name", "artefact")
        val size = art.optLong("size_in_bytes", 0L)
        log("Téléchargement de « $name » (${formatSize(size)})…")

        val zipFile = File(workDir, "artefact.zip")
        api.download(
            "$base/actions/artifacts/${art.optLong("id")}/zip",
            zipFile,
            size,
            { done, total -> if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 100)) },
            cancelled
        )

        // --- extraction du .apk
        log("Extraction de l'APK…")
        val apk = extractApk(zipFile)
        zipFile.delete()
        return FetchResult(apk, name, run.optString("head_sha"), run.optString("html_url"))
    }

    /** Artefact APK (sinon le premier) non expiré d'une compilation. */
    private fun artifactOf(base: String, runId: Long): JSONObject? {
        val artifacts = api.get("$base/actions/runs/$runId/artifacts")
        if (!artifacts.ok) throw GitHubApi.explain(artifacts, "Lecture des artefacts")
        val list = artifacts.json?.optJSONArray("artifacts") ?: return null
        var chosen: JSONObject? = null
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            if (a.optBoolean("expired", false)) continue
            if (a.optString("name").contains("apk", ignoreCase = true)) return a
            if (chosen == null) chosen = a
        }
        return chosen
    }

    /** Dernière compilation réussie (hors [skipId]) ayant encore un artefact. */
    private fun successWithArtifact(base: String, branch: String, skipId: Long): Pair<JSONObject, JSONObject>? {
        var path = "$base/actions/runs?status=success&per_page=15"
        if (branch.isNotEmpty()) path += "&branch=" + URLEncoder.encode(branch, "UTF-8")
        val runs = api.get(path).json?.optJSONArray("workflow_runs") ?: return null
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optLong("id") == skipId) continue
            val a = artifactOf(base, r.optLong("id")) ?: continue
            return r to a
        }
        return null
    }

    // ------------------------------------------------------------------ attente de la compilation

    private fun waitForRun(base: String, branch: String, expectedSha: String?): JSONObject {
        var waited = 0
        var lastLogged = -100
        while (true) {
            if (cancelled()) throw PushException("Annulé.")

            var path = "$base/actions/runs?per_page=10"
            if (branch.isNotEmpty()) path += "&branch=" + URLEncoder.encode(branch, "UTF-8")
            val reply = api.get(path)
            if (!reply.ok) throw GitHubApi.explain(reply, "Lecture des compilations")
            val runs = reply.json?.optJSONArray("workflow_runs")

            var candidate: JSONObject? = null
            if (runs != null) {
                for (i in 0 until runs.length()) {
                    val r = runs.getJSONObject(i)
                    if (expectedSha == null || r.optString("head_sha") == expectedSha) {
                        candidate = r
                        break
                    }
                }
            }

            if (candidate == null) {
                if (expectedSha == null && branch.isNotEmpty()) {
                    log("Aucune compilation sur « $branch » : recherche sur toutes les branches.")
                    return waitForRun(base, "", null)
                }
                if (expectedSha != null && waited < 90) {
                    if (waited - lastLogged >= 20) {
                        log("En attente du démarrage de la compilation…")
                        lastLogged = waited
                    }
                    pause(5)
                    waited += 5
                    continue
                }
                throw PushException(
                    if (runs == null || runs.length() == 0) {
                        "Aucune compilation trouvée : ce dépôt n'a pas de workflow GitHub Actions " +
                            "(fichier .github/workflows)."
                    } else {
                        "Aucune compilation trouvée pour ce commit."
                    }
                )
            }

            val status = candidate.optString("status")
            if (status != "completed") {
                if (waited - lastLogged >= 20) {
                    log("Compilation en cours… (${waited} s)")
                    lastLogged = waited
                }
                if (waited > 900) throw PushException("La compilation dure plus de 15 minutes : abandon.")
                pause(10)
                waited += 10
                continue
            }

            val conclusion = candidate.optString("conclusion")
            if (conclusion != "success") {
                throw BuildFailedException("La compilation a échoué ($conclusion).", candidate)
            }
            log("Compilation terminée avec succès.")
            return candidate
        }
    }

    private fun pause(seconds: Int) {
        for (i in 0 until seconds) {
            if (cancelled()) throw PushException("Annulé.")
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PushException("Interrompu.")
            }
        }
    }

    // ------------------------------------------------------------------ extraction

    private fun extractApk(zipFile: File): File {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // on ne garde que le nom du fichier : aucun chemin du zip n'est réutilisé
                val fileName = File(entry.name).name
                if (!fileName.endsWith(".apk", ignoreCase = true)) continue
                val out = File(workDir, fileName)
                FileOutputStream(out).use { zip.copyTo(it) }
                return out
            }
        }
        throw PushException("Aucun fichier .apk dans l'artefact téléchargé.")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "taille inconnue"
        bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
        else -> String.format("%.1f Mo", bytes / 1048576.0)
    }
}
