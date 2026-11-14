package com.example.gitzippush

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Fonctions GitHub courantes : jeton, quotas, facturation, stockage Actions, visibilité, transfert,
 * lancement/annulation de compilation, copie vers un dépôt public pour compiler sans consommer de minutes.
 * Toutes les fonctions sont bloquantes : à appeler hors du fil UI. Elles renvoient un texte lisible.
 */
object GitHubTools {

    const val TOKEN_HELP =
        "JETON CLASSIQUE (recommandé, commence par ghp_)\n" +
            "1. github.com > photo de profil > Settings > Developer settings > Personal access tokens > " +
            "Tokens (classic) > Generate new token (classic). Le bouton « Créer un jeton » pré-coche tout.\n" +
            "2. Droits à cocher :\n" +
            "   • repo : lire et écrire tes dépôts (obligatoire)\n" +
            "   • workflow : envoyer .github/workflows (compilation APK)\n" +
            "   • delete_repo : supprimer un dépôt (Nettoyage)\n" +
            "   • user : quotas et facturation (lecture)\n" +
            "3. Expiration : 90 jours conseillé. L'appli affiche la date d'expiration.\n" +
            "4. Le jeton ne s'affiche qu'une fois : copie-le, puis Compte > Coller.\n\n" +
            "JETON FIN (github_pat_)\n" +
            "Choisis les dépôts, puis les permissions : Contents, Workflows, Actions, Administration " +
            "(lecture-écriture) ; compte : Plan (lecture) pour la facturation. Transfert et dépôts de compilation " +
            "créés plus tard : préfère le jeton classique.\n\n" +
            "SÉCURITÉ\n" +
            "Le jeton est chiffré sur ce téléphone (Android Keystore). Ne le colle jamais dans Claude ni ailleurs. " +
            "En cas de doute : révoque-le sur GitHub et crées-en un autre."

    const val NEW_TOKEN_URL =
        "https://github.com/settings/tokens/new?scopes=repo,workflow,delete_repo,user&description=Envoi%20GitHub"
    const val NEW_FINE_TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
    const val BILLING_URL = "https://github.com/settings/billing"
    const val BUDGETS_URL = "https://github.com/settings/billing/budgets"

    private val NEEDED = linkedMapOf(
        "repo" to "envoi et lecture des dépôts",
        "workflow" to "envoi des workflows (APK)",
        "delete_repo" to "suppression de dépôt",
        "user" to "quotas et facturation"
    )

    /** Minutes Actions incluses par mois (dépôts privés ; les dépôts publics sont gratuits). */
    private fun includedMinutes(plan: String) = when (plan.lowercase()) {
        "free" -> 2000
        "pro" -> 3000
        else -> 0
    }

    private fun login(api: GitHubApi): Pair<String, JSONObject> {
        val r = api.get("/user")
        val o = r.json
        if (!r.ok || o == null) throw GitHubApi.explain(r, "Connexion")
        return o.optString("login") to o
    }

    // ------------------------------------------------------------------ jeton

    fun tokenInfo(api: GitHubApi): String {
        val r = api.get("/user")
        val o = r.json
        if (!r.ok || o == null) throw GitHubApi.explain(r, "Vérification du jeton")
        val sb = StringBuilder("✓ Jeton valide : ${o.optString("login")}")
        o.optJSONObject("plan")?.optString("name")?.takeIf { it.isNotEmpty() }?.let { sb.append(" · offre $it") }
        sb.append('\n')
        sb.append("Expiration : ").append(r.expires?.takeIf { it.isNotBlank() } ?: "aucune (ou jeton fin)").append('\n')
        val scopes = r.scopes
        if (scopes == null) {
            sb.append("Jeton fin : droits non lisibles ici. En cas de 403, vérifie ses permissions.")
        } else {
            val have = scopes.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            sb.append("Droits : ").append(if (have.isEmpty()) "aucun" else have.joinToString(", ")).append('\n')
            val missing = NEEDED.filterKeys { k -> k !in have && !(k == "user" && "read:user" in have) }
            if (missing.isEmpty()) sb.append("✓ Tous les droits utiles sont présents.")
            else for ((k, v) in missing) sb.append("⚠ Manque « $k » : $v impossible.\n")
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------ quotas et facturation

    fun quotas(api: GitHubApi): String {
        val sb = StringBuilder()
        val rl = api.get("/rate_limit")
        val res = rl.json?.optJSONObject("resources")
        if (rl.ok && res != null) {
            sb.append("Limites d'API (par heure) :\n")
            for (k in listOf("core", "search", "graphql")) {
                val x = res.optJSONObject(k) ?: continue
                val reset = (x.optLong("reset") * 1000 - System.currentTimeMillis()) / 60000
                sb.append("  • $k : ${x.optInt("remaining")} / ${x.optInt("limit")} restantes (remise à zéro dans ${reset.coerceAtLeast(0)} min)\n")
            }
        } else {
            sb.append("Limites d'API illisibles (HTTP ${rl.code}).\n")
        }
        sb.append('\n').append(actionsMinutes(api))
        return sb.toString().trimEnd()
    }

    /** Minutes Actions du mois : nouvelle API de facturation, sinon l'ancienne. */
    private fun actionsMinutes(api: GitHubApi): String {
        val (user, o) = login(api)
        val plan = o.optJSONObject("plan")?.optString("name").orEmpty()
        val included = includedMinutes(plan)
        val usage = monthUsage(api, user)
        if (usage != null) {
            var minutes = 0.0
            var net = 0.0
            for (u in usage) {
                if (u.optString("product").equals("actions", true) && u.optString("unitType").contains("minute", true)) {
                    minutes += u.optDouble("quantity", 0.0)
                    net += u.optDouble("netAmount", 0.0)
                }
            }
            return "Actions ce mois (dépôts privés) : ${minutes.toInt()} min" +
                (if (included > 0) " / $included incluses (${(minutes * 100 / included).toInt()} %)" else "") +
                (if (net > 0.0) "\n⚠ Dépassement facturé : ${money(net)}" else "") +
                "\nLes compilations des dépôts PUBLICS ne consomment pas de minutes."
        }
        val old = api.get("/users/$user/settings/billing/actions")
        val j = old.json
        if (old.ok && j != null) {
            return "Actions ce mois : ${j.optInt("total_minutes_used")} min / ${j.optInt("included_minutes")} incluses" +
                (if (j.optInt("total_paid_minutes_used") > 0) "\n⚠ Minutes payantes : ${j.optInt("total_paid_minutes_used")}" else "")
        }
        return "Minutes Actions illisibles (HTTP ${old.code}) : il faut le droit « user » (jeton classique) " +
            "ou « Plan » en lecture (jeton fin)."
    }

    private fun monthUsage(api: GitHubApi, user: String): List<JSONObject>? {
        val c = Calendar.getInstance()
        val r = api.get("/users/$user/settings/billing/usage?year=${c.get(Calendar.YEAR)}&month=${c.get(Calendar.MONTH) + 1}")
        val arr = r.json?.optJSONArray("usageItems") ?: return null
        if (!r.ok) return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun billing(api: GitHubApi): String {
        val (user, o) = login(api)
        val plan = o.optJSONObject("plan")
        val sb = StringBuilder("Compte $user · offre ${plan?.optString("name") ?: "?"}\n")
        val usage = monthUsage(api, user)
            ?: return sb.append("Détail de facturation illisible : droit « user » (classique) ou « Plan » (fin) requis.\n")
                .append("Consulte : $BILLING_URL").toString()
        if (usage.isEmpty()) return sb.append("Aucune consommation ce mois.").toString()
        class Sum(var qty: Double = 0.0, var gross: Double = 0.0, var net: Double = 0.0, var unit: String = "")
        val byProduct = java.util.TreeMap<String, Sum>()
        val byRepo = HashMap<String, Double>()
        for (u in usage) {
            val key = u.optString("product") + " · " + u.optString("sku")
            val s = byProduct.getOrPut(key) { Sum() }
            s.qty += u.optDouble("quantity", 0.0)
            s.gross += u.optDouble("grossAmount", 0.0)
            s.net += u.optDouble("netAmount", 0.0)
            s.unit = u.optString("unitType")
            val repo = u.optString("repositoryName")
            if (repo.isNotEmpty() && u.optString("product").equals("actions", true)) {
                byRepo[repo] = (byRepo[repo] ?: 0.0) + u.optDouble("quantity", 0.0)
            }
        }
        sb.append("Consommation du mois :\n")
        var total = 0.0
        for ((k, s) in byProduct) {
            sb.append("  • $k : ${fmt(s.qty)} ${s.unit} · valeur ${money(s.gross)} · à payer ${money(s.net)}\n")
            total += s.net
        }
        sb.append("Total à payer : ${money(total)}").append(if (total == 0.0) " ✓ (couvert par l'offre)" else " ⚠").append('\n')
        if (byRepo.isNotEmpty()) {
            sb.append("Minutes Actions par dépôt :\n")
            for ((r, q) in byRepo.entries.sortedByDescending { it.value }.take(10)) sb.append("  • $r : ${fmt(q)} min\n")
        }
        sb.append("Limite de dépenses (budgets) : $BUDGETS_URL")
        return sb.toString()
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else String.format("%.2f", d)
    private fun money(d: Double) = String.format("%.2f $", d)

    // ------------------------------------------------------------------ dépôt : stockage, rétention, compilations

    fun storage(api: GitHubApi, repo: String): String {
        val sb = StringBuilder("Stockage Actions de $repo :\n")
        val a = api.get("/repos/$repo/actions/artifacts?per_page=100")
        val arts = a.json?.optJSONArray("artifacts")
        if (a.ok && arts != null) {
            var bytes = 0L
            var live = 0
            for (k in 0 until arts.length()) {
                val x = arts.getJSONObject(k)
                if (!x.optBoolean("expired")) {
                    bytes += x.optLong("size_in_bytes")
                    live++
                }
            }
            sb.append("  • Artefacts : $live actifs, ${FilesActivity.formatSize(bytes)}")
            if ((a.json?.optInt("total_count") ?: 0) > 100) sb.append(" (100 premiers)")
            sb.append('\n')
        } else {
            sb.append("  • Artefacts illisibles (HTTP ${a.code})\n")
        }
        val c = api.get("/repos/$repo/actions/cache/usage")
        c.json?.takeIf { c.ok }?.let {
            sb.append("  • Caches : ${it.optInt("active_caches_count")}, ${FilesActivity.formatSize(it.optLong("active_caches_size_in_bytes"))}\n")
        }
        val r = api.get("/repos/$repo/actions/permissions/artifact-and-log-retention")
        r.json?.takeIf { r.ok }?.let { sb.append("  • Rétention artefacts et journaux : ${it.optInt("days")} jour(s)\n") }
        sb.append("Stockage gratuit du compte : 500 Mo (offre Free). Purge : tuile Nettoyage GitHub.")
        return sb.toString()
    }

    fun setRetention(api: GitHubApi, repo: String, days: Int): String {
        val r = api.call("PUT", "/repos/$repo/actions/permissions/artifact-and-log-retention", JSONObject().put("days", days))
        if (!r.ok) throw GitHubApi.explain(r, "Rétention")
        return "✓ Rétention de $repo : $days jour(s). Les nouveaux artefacts expireront plus vite."
    }

    /** Lance une compilation (workflow_dispatch), sinon relance la dernière. */
    fun startBuild(api: GitHubApi, repo: String, branch: String): String {
        val br = branch.ifBlank { defaultBranch(api, repo) }
        val w = api.get("/repos/$repo/actions/workflows")
        val list = w.json?.optJSONArray("workflows")
        if (!w.ok || list == null) throw GitHubApi.explain(w, "Lecture des workflows")
        if (list.length() == 0) return "Aucun workflow dans $repo : envoie d'abord un zip (le workflow APK est ajouté automatiquement)."
        for (k in 0 until list.length()) {
            val wf = list.getJSONObject(k)
            if (wf.optString("state") != "active") continue
            val d = api.call("POST", "/repos/$repo/actions/workflows/${wf.optLong("id")}/dispatches", JSONObject().put("ref", br))
            if (d.ok) return "✓ Compilation lancée : « ${wf.optString("name")} » sur $br. Récupère l'APK avec la tuile APK."
        }
        val runs = api.get("/repos/$repo/actions/runs?per_page=1&branch=${Uri.encode(br)}")
        val last = runs.json?.optJSONArray("workflow_runs")?.optJSONObject(0)
            ?: return "Lancement impossible : workflow sans « workflow_dispatch » et aucune compilation à relancer."
        val rr = api.call("POST", "/repos/$repo/actions/runs/${last.optLong("id")}/rerun", null)
        if (!rr.ok) throw GitHubApi.explain(rr, "Relance")
        return "✓ Dernière compilation relancée (${last.optString("head_sha").take(7)})."
    }

    fun cancelBuilds(api: GitHubApi, repo: String): String {
        var n = 0
        for (st in listOf("in_progress", "queued", "waiting")) {
            val r = api.get("/repos/$repo/actions/runs?status=$st&per_page=50")
            val arr = r.json?.optJSONArray("workflow_runs") ?: continue
            for (k in 0 until arr.length()) {
                if (api.call("POST", "/repos/$repo/actions/runs/${arr.getJSONObject(k).optLong("id")}/cancel", null).ok) n++
            }
        }
        return if (n == 0) "Aucune compilation en cours." else "✓ $n compilation(s) annulée(s)."
    }

    // ------------------------------------------------------------------ visibilité et transfert

    fun visibility(api: GitHubApi, repo: String): String {
        val r = api.get("/repos/$repo")
        val o = r.json
        if (!r.ok || o == null) throw GitHubApi.explain(r, "Lecture du dépôt")
        return if (o.optBoolean("private")) "privé" else "public"
    }

    fun setPrivate(api: GitHubApi, repo: String, private: Boolean): String {
        val r = api.call("PATCH", "/repos/$repo", JSONObject().put("private", private))
        if (!r.ok) throw GitHubApi.explain(r, "Changement de visibilité")
        return if (private) "✓ $repo est maintenant privé." else
            "✓ $repo est maintenant PUBLIC : code et historique visibles de tous ; compilations gratuites."
    }

    fun transfer(api: GitHubApi, repo: String, newOwner: String, newName: String?): String {
        val body = JSONObject().put("new_owner", newOwner)
        if (!newName.isNullOrBlank()) body.put("new_name", newName)
        val r = api.call("POST", "/repos/$repo/transfer", body)
        if (!r.ok) throw GitHubApi.explain(r, "Transfert")
        val dest = "$newOwner/" + (newName?.takeIf { it.isNotBlank() } ?: repo.substringAfter('/'))
        return "✓ Transfert demandé : $repo → $dest. Vers un autre utilisateur, il doit accepter l'invitation reçue par e-mail."
    }

    // ------------------------------------------------------------------ copie vers un dépôt public de compilation

    class MirrorResult(val target: String, val branch: String, val sha: String, val created: Boolean)

    /**
     * Copie exacte de [src]@[branch] dans [target] (créé public si absent), en un commit.
     * La compilation part alors sur le dépôt public, sans consommer de minutes.
     */
    fun mirror(api: GitHubApi, src: String, branch: String, target: String, log: (String) -> Unit): MirrorResult {
        val srcBranch = branch.ifBlank { defaultBranch(api, src) }
        val srcHead = headOf(api, src, srcBranch) ?: throw PushException("Branche « $srcBranch » introuvable dans $src.")
        val t = api.get("/repos/$src/git/trees/$srcHead?recursive=1")
        val items = t.json?.optJSONArray("tree") ?: throw GitHubApi.explain(t, "Lecture de l'arborescence")
        if (t.json?.optBoolean("truncated") == true) throw PushException("Dépôt trop gros pour la copie via l'API.")
        val blobs = ArrayList<JSONObject>()
        for (k in 0 until items.length()) items.getJSONObject(k).takeIf { it.optString("type") == "blob" }?.let { blobs.add(it) }
        log("Source : $src@${srcHead.take(7)} (${blobs.size} fichiers).")

        var created = false
        val existing = api.get("/repos/$target")
        if (existing.code == 404) {
            val (user, _) = login(api)
            val owner = target.substringBefore('/')
            val body = JSONObject().put("name", target.substringAfter('/')).put("private", false).put("auto_init", true)
                .put("description", "Copie publique de compilation (Envoi GitHub)")
            val path = if (owner.equals(user, true)) "/user/repos" else "/orgs/$owner/repos"
            val c = api.call("POST", path, body)
            if (!c.ok) throw GitHubApi.explain(c, "Création du dépôt de compilation")
            created = true
            log("Dépôt public $target créé.")
        } else if (!existing.ok) {
            throw GitHubApi.explain(existing, "Lecture du dépôt de compilation")
        } else if (existing.json?.optBoolean("private") == true) {
            log("⚠ $target est privé : la compilation consommera des minutes.")
        }
        val tBranch = defaultBranch(api, target)
        var tHead: String? = null
        for (i in 0 until 8) {
            tHead = headOf(api, target, tBranch)
            if (tHead != null) break
            Thread.sleep(2000)
        }
        if (tHead == null) throw PushException("Dépôt $target vide ou non initialisé.")

        // blobs déjà présents dans la cible (même contenu = même SHA)
        val known = HashSet<String>()
        val tt = api.get("/repos/$target/git/trees/$tHead?recursive=1").json?.optJSONArray("tree")
        if (tt != null) for (k in 0 until tt.length()) known.add(tt.getJSONObject(k).optString("sha"))

        val entries = JSONArray()
        var sent = 0
        for ((i, b) in blobs.withIndex()) {
            val sha = b.optString("sha")
            if (sha !in known) {
                val g = api.get("/repos/$src/git/blobs/$sha")
                val content = g.json?.optString("content")
                if (!g.ok || content == null) throw GitHubApi.explain(g, "Lecture de ${b.optString("path")}")
                val p = api.call(
                    "POST", "/repos/$target/git/blobs",
                    JSONObject().put("content", content.replace("\n", "")).put("encoding", "base64")
                )
                if (!p.ok) throw GitHubApi.explain(p, "Copie de ${b.optString("path")}")
                known.add(sha)
                sent++
            }
            entries.put(JSONObject().put("path", b.optString("path")).put("mode", b.optString("mode")).put("type", "blob").put("sha", sha))
            if ((i + 1) % 25 == 0) log("… ${i + 1}/${blobs.size} fichiers")
        }
        log("$sent fichier(s) copiés, ${blobs.size - sent} déjà présents.")
        val tr = api.call("POST", "/repos/$target/git/trees", JSONObject().put("tree", entries))
        val tree = tr.json?.optString("sha")?.takeIf { tr.ok } ?: throw GitHubApi.explain(tr, "Création de l'arbre")
        val cm = api.call(
            "POST", "/repos/$target/git/commits",
            JSONObject().put("message", "Copie de $src@${srcHead.take(7)} pour compilation").put("tree", tree)
                .put("parents", JSONArray().put(tHead))
        )
        val sha = cm.json?.optString("sha")?.takeIf { cm.ok } ?: throw GitHubApi.explain(cm, "Création du commit")
        val up = api.call("PATCH", "/repos/$target/git/refs/heads/${Uri.encode(tBranch, "/")}", JSONObject().put("sha", sha).put("force", true))
        if (!up.ok) throw GitHubApi.explain(up, "Mise à jour de la branche")
        log("✓ Commit ${sha.take(7)} poussé sur $target ($tBranch) : compilation lancée.")
        return MirrorResult(target, tBranch, sha, created)
    }

    // ------------------------------------------------------------------ outils

    fun defaultBranch(api: GitHubApi, repo: String): String {
        val r = api.get("/repos/$repo")
        if (!r.ok) throw GitHubApi.explain(r, "Lecture de $repo")
        return r.json?.optString("default_branch")?.ifEmpty { null } ?: "main"
    }

    private fun headOf(api: GitHubApi, repo: String, branch: String): String? {
        val r = api.get("/repos/$repo/git/ref/heads/${Uri.encode(branch, "/")}")
        return if (r.ok) r.json?.optJSONObject("object")?.optString("sha") else null
    }
}
