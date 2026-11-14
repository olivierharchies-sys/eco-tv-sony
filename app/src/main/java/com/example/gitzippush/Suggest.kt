package com.example.gitzippush

import android.content.Context
import android.net.Uri

/** Valeurs proposées dans les listes déroulantes (dépôts, branches, propriétaires). */
object Suggest {
    private const val TTL = 10 * 60 * 1000L
    @Volatile private var repoCache: Pair<Long, List<String>>? = null
    private val branchCache = HashMap<String, Pair<Long, List<String>>>()

    fun repos(ctx: Context): List<String> = Settings(ctx).repos().map { it.repo }

    /** Dépôts du compte GitHub (cache 10 min). Hors fil UI. */
    fun remoteRepos(ctx: Context): List<String> {
        repoCache?.let { if (System.currentTimeMillis() - it.first < TTL) return it.second }
        val token = Settings(ctx).token() ?: return emptyList()
        val list = Pusher(GitHubApi(token), { }, { _, _ -> }).listRepos()
        repoCache = System.currentTimeMillis() to list
        return list
    }

    fun branches(ctx: Context, repo: String?): List<String> {
        val out = LinkedHashSet<String>()
        val r = repo?.trim().orEmpty()
        Settings(ctx).repos().filter { it.repo.equals(r, true) && it.branch.isNotBlank() }.forEach { out.add(it.branch) }
        synchronized(branchCache) { branchCache[r.lowercase()]?.second?.let { out.addAll(it) } }
        out.add("main")
        out.add("master")
        return out.toList()
    }

    /** Branches du dépôt sur GitHub (cache 10 min). Hors fil UI. */
    fun remoteBranches(ctx: Context, repo: String?): List<String> {
        val ref = RepoRef.parse(repo.orEmpty()) ?: return emptyList()
        val key = "${ref.first}/${ref.second}".lowercase()
        synchronized(branchCache) {
            branchCache[key]?.let { if (System.currentTimeMillis() - it.first < TTL) return it.second }
        }
        val token = Settings(ctx).token() ?: return emptyList()
        val r = GitHubApi(token).get("/repos/${ref.first}/${Uri.encode(ref.second)}/branches?per_page=100")
        val arr = try {
            if (r.ok) org.json.JSONArray(r.text) else null
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val list = (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotEmpty() }
        synchronized(branchCache) { branchCache[key] = System.currentTimeMillis() to list }
        return list
    }

    /** Propriétaires connus (comptes et organisations des dépôts mémorisés). */
    fun owners(ctx: Context): List<String> = repos(ctx).map { it.substringBefore('/') }.distinct()
}
