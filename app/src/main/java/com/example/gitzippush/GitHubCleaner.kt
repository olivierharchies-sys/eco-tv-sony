package com.example.gitzippush

import org.json.JSONObject

/**
 * Nettoyage d'un dépôt GitHub : compilations (runs) échouées ou anciennes, artefacts, caches Actions,
 * branches déjà fusionnées dans la branche par défaut. Analyse d'abord ([plan]), supprime ensuite ([execute]).
 */
object GitHubCleaner {

    class Options(
        val failedRuns: Boolean,
        val oldRuns: Boolean,
        val keepRuns: Int,
        val artifacts: Boolean,
        val caches: Boolean,
        val branches: Boolean
    )

    class Plan(
        val repo: String,
        val runs: List<Long>,
        val artifacts: List<Long>,
        val artifactBytes: Long,
        val caches: List<Long>,
        val cacheBytes: Long,
        val branches: List<String>
    ) {
        val total: Int get() = runs.size + artifacts.size + caches.size + branches.size

        fun summary(): String {
            val parts = ArrayList<String>()
            if (runs.isNotEmpty()) parts.add("${runs.size} compilation(s)")
            if (artifacts.isNotEmpty()) parts.add("${artifacts.size} artefact(s) (${FilesActivity.formatSize(artifactBytes)})")
            if (caches.isNotEmpty()) parts.add("${caches.size} cache(s) (${FilesActivity.formatSize(cacheBytes)})")
            if (branches.isNotEmpty()) parts.add("${branches.size} branche(s) fusionnée(s) : " + branches.joinToString(", "))
            return "$repo : " + if (parts.isEmpty()) "rien à nettoyer" else parts.joinToString(" · ")
        }
    }

    private val BAD = setOf("failure", "cancelled", "timed_out", "startup_failure")
    private const val MAX_PAGES = 10

    /** Liste ce qui serait supprimé. Hors fil UI. */
    fun plan(api: GitHubApi, repo: String, o: Options, cancelled: () -> Boolean): Plan {
        val base = "/repos/$repo"

        // --- compilations
        val runs = ArrayList<Long>()
        if (o.failedRuns || o.oldRuns) {
            val all = pages(api, "$base/actions/runs", "workflow_runs", cancelled)
                .filter { it.optString("status") == "completed" }
                .sortedByDescending { it.optString("created_at") }
            val keep = o.keepRuns.coerceAtLeast(1)
            for ((i, r) in all.withIndex()) {
                val bad = r.optString("conclusion") in BAD
                if ((o.failedRuns && bad) || (o.oldRuns && i >= keep)) runs.add(r.optLong("id"))
            }
        }

        // --- artefacts
        val artifacts = ArrayList<Long>()
        var artifactBytes = 0L
        if (o.artifacts) {
            for (a in pages(api, "$base/actions/artifacts", "artifacts", cancelled)) {
                artifacts.add(a.optLong("id"))
                artifactBytes += a.optLong("size_in_bytes")
            }
        }

        // --- caches
        val caches = ArrayList<Long>()
        var cacheBytes = 0L
        if (o.caches) {
            for (c in pages(api, "$base/actions/caches", "actions_caches", cancelled)) {
                caches.add(c.optLong("id"))
                cacheBytes += c.optLong("size_in_bytes")
            }
        }

        // --- branches entièrement contenues dans la branche par défaut
        val branches = ArrayList<String>()
        if (o.branches) {
            val info = api.get(base)
            if (!info.ok) throw GitHubApi.explain(info, "Lecture du dépôt")
            val def = info.json?.optString("default_branch").orEmpty()
            val list = api.get("$base/branches?per_page=100")
            if (!list.ok) throw GitHubApi.explain(list, "Lecture des branches")
            val arr = try {
                org.json.JSONArray(list.text)
            } catch (e: Exception) {
                org.json.JSONArray()
            }
            for (i in 0 until arr.length()) {
                if (cancelled()) throw PushException("Annulé.")
                val b = arr.getJSONObject(i)
                val name = b.optString("name")
                if (name.isEmpty() || name == def || b.optBoolean("protected")) continue
                val cmp = api.get("$base/compare/$def...$name")
                if (cmp.ok && cmp.json?.optInt("ahead_by", -1) == 0) branches.add(name)
            }
        }
        return Plan(repo, runs, artifacts, artifactBytes, caches, cacheBytes, branches)
    }

    /** Supprime ce que liste [p]. Renvoie (supprimés, erreurs). Hors fil UI. */
    fun execute(
        api: GitHubApi, p: Plan, progress: (done: Int, total: Int) -> Unit, cancelled: () -> Boolean
    ): Pair<Int, Int> {
        val base = "/repos/${p.repo}"
        val paths = ArrayList<String>()
        for (id in p.runs) paths.add("$base/actions/runs/$id")
        for (id in p.artifacts) paths.add("$base/actions/artifacts/$id")
        for (id in p.caches) paths.add("$base/actions/caches/$id")
        for (b in p.branches) paths.add("$base/git/refs/heads/$b")
        var ok = 0
        var errors = 0
        for ((i, path) in paths.withIndex()) {
            if (cancelled()) throw PushException("Annulé.")
            val r = api.call("DELETE", path, null)
            if (r.ok || r.code == 404) ok++ else errors++
            progress(i + 1, paths.size)
        }
        return ok to errors
    }

    private fun pages(api: GitHubApi, path: String, key: String, cancelled: () -> Boolean): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (page in 1..MAX_PAGES) {
            if (cancelled()) throw PushException("Annulé.")
            val r = api.get("$path?per_page=100&page=$page")
            if (!r.ok) throw GitHubApi.explain(r, "Lecture de ${path.substringAfterLast('/')}")
            val arr = r.json?.optJSONArray(key) ?: break
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            if (arr.length() < 100) break
        }
        return out
    }
}
