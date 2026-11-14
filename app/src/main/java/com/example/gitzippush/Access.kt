package com.example.gitzippush

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Qui appelle l'API : l'appli, Claude (dialogue), le nettoyage auto, l'écran Accès (gestion, non bridé). */
enum class Service { APP, CLAUDE, CLEANER, ACCESS }

/** Niveau d'accès à un dépôt. */
enum class Level(val label: String) {
    BLOCKED("Bloqué"), READ("Lecture seule"), WRITE("Lecture-écriture");
}

/** Opération GitHub déduite de la requête. */
enum class Op { READ, WRITE, BRANCH, DELETE, ADMIN }

/** Règle d'un dépôt. [tokenId] vide = jeton par défaut. */
data class AccessRule(
    val repo: String,
    val level: Level = Level.WRITE,
    val branch: Boolean = true,
    val delete: Boolean = true,
    val admin: Boolean = true,
    val claude: Level = Level.WRITE,
    val cleaner: Boolean = true,
    val tokenId: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("repo", repo).put("level", level.name).put("branch", branch).put("delete", delete)
        .put("admin", admin).put("claude", claude.name).put("cleaner", cleaner).put("token", tokenId)

    fun summary(tokenLabel: String?): String {
        val f = ArrayList<String>()
        if (level == Level.WRITE) {
            if (branch) f.add("branches")
            if (delete) f.add("suppr.")
            if (admin) f.add("admin")
        }
        if (cleaner) f.add("nettoyage")
        return "$repo — ${level.label}" + (if (f.isNotEmpty()) " (${f.joinToString(", ")})" else "") +
            " · Claude : ${claude.label}" + (tokenLabel?.let { " · jeton : $it" } ?: "")
    }

    companion object {
        fun from(o: JSONObject) = AccessRule(
            o.optString("repo").lowercase(),
            lvl(o.optString("level"), Level.WRITE), o.optBoolean("branch", true), o.optBoolean("delete", true),
            o.optBoolean("admin", true), lvl(o.optString("claude"), Level.WRITE), o.optBoolean("cleaner", true),
            o.optString("token")
        )

        private fun lvl(s: String, d: Level) = Level.values().firstOrNull { it.name == s } ?: d
    }
}

/** Jeton supplémentaire (chiffré). */
class TokenEntry(val id: String, val label: String, val enc: String)

/** Erreur de droit, levée avant tout appel réseau. */
class AccessDenied(message: String) : PushException(message)

/** Règles d'accès + coffre de jetons. Persistant dans les préférences de l'appli. */
class AccessStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("envoi_github", Context.MODE_PRIVATE)

    /** Liste blanche : les dépôts sans règle sont bloqués. */
    var whitelist: Boolean
        get() = prefs.getBoolean("access_whitelist", false)
        set(v) {
            prefs.edit().putBoolean("access_whitelist", v).apply(); AccessGuard.reload()
        }

    fun rules(): List<AccessRule> = try {
        val a = JSONArray(prefs.getString("access_rules", "[]") ?: "[]")
        (0 until a.length()).map { AccessRule.from(a.getJSONObject(it)) }.filter { it.repo.isNotEmpty() }
            .sortedBy { it.repo }
    } catch (e: Exception) {
        emptyList()
    }

    fun rule(repo: String): AccessRule? = rules().firstOrNull { it.repo == repo.lowercase() }

    fun save(rule: AccessRule) {
        val r = rule.copy(repo = rule.repo.lowercase())
        write(rules().filter { it.repo != r.repo } + r)
    }

    fun remove(repo: String) = write(rules().filter { it.repo != repo.lowercase() })

    private fun write(list: List<AccessRule>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs.edit().putString("access_rules", a.toString()).apply()
        AccessGuard.reload()
    }

    // ------------------------------------------------------------------ jetons

    fun tokens(): List<TokenEntry> = try {
        val a = JSONArray(prefs.getString("access_tokens", "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { TokenEntry(it.optString("id"), it.optString("label"), it.optString("enc")) }
    } catch (e: Exception) {
        emptyList()
    }

    fun addToken(label: String, token: String): TokenEntry {
        val e = TokenEntry("t" + System.currentTimeMillis().toString(36), label.ifBlank { "Jeton" }, SecureStore.encrypt(token))
        writeTokens(tokens() + e)
        return e
    }

    /** Retire le jeton de l'appli ; les règles qui l'utilisaient repassent au jeton par défaut. */
    fun removeToken(id: String) {
        writeTokens(tokens().filter { it.id != id })
        write(rules().map { if (it.tokenId == id) it.copy(tokenId = "") else it })
    }

    fun tokenValue(id: String): String? = tokens().firstOrNull { it.id == id }?.let { SecureStore.decrypt(it.enc) }

    fun tokenLabel(id: String): String? = if (id.isEmpty()) null else tokens().firstOrNull { it.id == id }?.label

    private fun writeTokens(list: List<TokenEntry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label).put("enc", it.enc)) }
        prefs.edit().putString("access_tokens", a.toString()).apply()
        AccessGuard.reload()
    }
}

/**
 * Contrôle central, appelé par [GitHubApi] avant chaque requête : droits du service sur le dépôt
 * et jeton propre au dépôt. Sans règle ni liste blanche : tout est permis (comportement d'avant).
 */
object AccessGuard {
    private class Snapshot(val whitelist: Boolean, val rules: Map<String, AccessRule>)

    @Volatile private var store: AccessStore? = null
    @Volatile private var snap: Snapshot? = null
    private val tokenCache = HashMap<String, String?>()

    private val REPO_PATH = Regex("^/repos/([^/?]+)/([^/?]+)(/[^?]*)?")
    private val DEFAULT = AccessRule("*")

    fun init(ctx: Context) {
        store = AccessStore(ctx)
        reload()
    }

    fun reload() {
        val s = store ?: return
        snap = Snapshot(s.whitelist, s.rules().associateBy { it.repo })
        synchronized(tokenCache) { tokenCache.clear() }
    }

    fun repoOf(path: String): String? =
        REPO_PATH.find(path)?.let { "${it.groupValues[1]}/${it.groupValues[2]}".lowercase() }

    /** Règle effective (null = dépôt bloqué par la liste blanche). */
    fun ruleFor(repo: String): AccessRule? {
        val s = snap ?: return DEFAULT
        return s.rules[repo.lowercase()] ?: if (s.whitelist) null else DEFAULT
    }

    /** Jeton propre au dépôt, sinon null (= jeton fourni par l'appelant). */
    fun tokenFor(repo: String?): String? {
        if (repo == null) return null
        val id = snap?.rules?.get(repo)?.tokenId?.takeIf { it.isNotEmpty() } ?: return null
        synchronized(tokenCache) {
            if (!tokenCache.containsKey(id)) tokenCache[id] = store?.tokenValue(id)
            return tokenCache[id]
        }
    }

    /** Lève [AccessDenied] si [service] n'a pas le droit d'exécuter la requête. */
    fun check(service: Service, method: String, path: String, body: JSONObject?) {
        if (service == Service.ACCESS) return
        val m = REPO_PATH.find(path) ?: return
        val repo = "${m.groupValues[1]}/${m.groupValues[2]}".lowercase()
        val rest = m.groupValues[3]
        val op = opOf(method.uppercase(), rest, body)
        val rule = ruleFor(repo) ?: throw AccessDenied("Accès refusé : $repo n'est pas dans la liste blanche (Accès).")
        val level = when (service) {
            Service.CLAUDE -> minOf(rule.level, rule.claude)
            else -> rule.level
        }
        val who = when (service) {
            Service.CLAUDE -> "Claude"
            Service.CLEANER -> "Nettoyage"
            else -> "Appli"
        }
        if (level == Level.BLOCKED) throw AccessDenied("Accès refusé : $who bloqué sur $repo (Accès).")
        if (op == Op.READ) return
        if (level != Level.WRITE) throw AccessDenied("Accès refusé : $who en lecture seule sur $repo (Accès).")
        if (service == Service.CLEANER && !rule.cleaner) throw AccessDenied("Accès refusé : nettoyage désactivé pour $repo (Accès).")
        when (op) {
            Op.BRANCH -> if (!rule.branch) throw AccessDenied("Accès refusé : création de branche interdite sur $repo (Accès).")
            Op.DELETE -> if (!rule.delete) throw AccessDenied("Accès refusé : suppressions interdites sur $repo (Accès).")
            Op.ADMIN -> if (!rule.admin) throw AccessDenied("Accès refusé : administration interdite sur $repo (Accès).")
            else -> {}
        }
    }

    /** Classe la requête. [rest] : chemin après /repos/o/r. */
    fun opOf(method: String, rest: String, body: JSONObject?): Op {
        if (method == "GET" || method == "HEAD") return Op.READ
        val adminPaths = listOf("/collaborators", "/keys", "/hooks", "/transfer", "/actions/permissions", "/rulesets", "/invitations")
        if (rest.isEmpty() || adminPaths.any { rest.startsWith(it) } || rest.contains("/protection")) return Op.ADMIN
        if (method == "DELETE") return Op.DELETE
        if (method == "POST" && rest == "/git/refs") return Op.BRANCH
        if (rest.startsWith("/git/refs") && body?.optBoolean("force") == true) return Op.DELETE
        if (rest == "/git/trees") {
            val t = body?.optJSONArray("tree")
            if (t != null) for (i in 0 until t.length()) {
                val e = t.optJSONObject(i) ?: continue
                if (e.has("sha") && e.isNull("sha")) return Op.DELETE
            }
        }
        return Op.WRITE
    }
}
