package com.example.gitzippush

/** Dépôt deviné, avec la raison affichée à l'utilisateur. */
class Guess(val repo: String, val branch: String, val score: Int, val reason: String)

/** Devine le dépôt de destination d'un zip / dossier : association apprise, nom, contenu. */
object RepoGuess {

    /** « GitZipPush-v3.zip » -> « gitzippush » ; vide si trop court pour être fiable. */
    fun key(name: String): String {
        var s = name.trim()
        if (s.lowercase().endsWith(".zip")) s = s.dropLast(4)
        s = s.lowercase()
        val suffix = Regex("[\\s._\\-]*(\\(\\d+\\)|v?\\d+([._]\\d+)*|final|new|copy|bak|backup|maj|update)$")
        while (true) {
            val m = suffix.find(s) ?: break
            if (m.range.first == 0) break
            s = s.substring(0, m.range.first)
        }
        s = s.replace(Regex("[^a-z0-9]"), "")
        return if (s.length >= 3) s else ""
    }

    fun byName(keys: List<String>, entries: List<RepoEntry>, learned: (String) -> RepoEntry?): Guess? {
        for (k in keys) {
            if (k.isEmpty()) continue
            val l = learned(k)
            if (l != null) return Guess(l.repo, l.branch, 100, "déjà envoyé sous ce nom")
        }
        var best: Guess? = null
        for (e in entries) {
            val rn = key(e.repo.substringAfter('/'))
            if (rn.isEmpty()) continue
            for (k in keys) {
                if (k.isEmpty()) continue
                val score = when {
                    k == rn -> 90
                    k.length >= 4 && rn.length >= 4 && (rn.contains(k) || k.contains(rn)) -> 70
                    else -> 0
                }
                if (score > (best?.score ?: 0)) {
                    best = Guess(e.repo, e.branch, score, if (score == 90) "même nom" else "nom proche")
                }
            }
        }
        return best
    }

    private val GENERIC = setOf("README.md", "LICENSE", ".gitignore", ".github/workflows/build.yml", "gradle.properties")

    /** Compare les chemins du zip avec le contenu des dépôts candidats. */
    fun byTree(paths: Collection<String>, candidates: Map<String, Set<String>>): Guess? {
        val mine = paths.filter { it !in GENERIC }.toSet()
        if (mine.isEmpty()) return null
        var best: String? = null
        var bestRatio = 0.0
        var bestCount = 0
        var second = 0.0
        for ((repo, theirs) in candidates) {
            val inter = mine.count { it in theirs }
            val ratio = inter.toDouble() / mine.size
            if (ratio > bestRatio) {
                second = bestRatio
                best = repo
                bestRatio = ratio
                bestCount = inter
            } else if (ratio > second) {
                second = ratio
            }
        }
        val enough = bestCount >= 3 || (mine.size < 3 && bestCount == mine.size)
        if (best == null || bestRatio < 0.5 || !enough || bestRatio - second < 0.15) return null
        return Guess(best, "", 40 + (50 * bestRatio).toInt(), "${bestCount} fichiers déjà présents dans ce dépôt")
    }
}
