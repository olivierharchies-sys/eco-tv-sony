package com.example.gitzippush

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accès et droits :
 * - règles de l'appli par dépôt (liste blanche, lecture seule / écriture, branches, suppressions, admin, nettoyage) ;
 * - droits de Claude (dialogue de dépannage) : bloqué / lecture seule / lecture-écriture ;
 * - jetons multiples, jeton par dépôt, test et révocation ;
 * - côté GitHub : collaborateurs, clés de déploiement, applis GitHub installées (Claude…), verrou de branche.
 */
class AccessActivity : Activity() {
    private lateinit var store: AccessStore
    private lateinit var scroll: ScrollView
    private lateinit var out: TextView
    private lateinit var progress: ProgressBar
    private lateinit var repoField: EditText
    private lateinit var rulesBox: LinearLayout
    private lateinit var tokensBox: LinearLayout
    private val buttons = ArrayList<TextView>()
    @Volatile private var alive = true
    @Volatile private var busy = false

    // état du formulaire de règle
    private var level = Level.WRITE
    private var claude = Level.WRITE
    private var tokenId = ""
    private lateinit var levelBtn: TextView
    private lateinit var claudeBtn: TextView
    private lateinit var tokenBtn: TextView
    private lateinit var swBranch: Switch
    private lateinit var swDelete: Switch
    private lateinit var swAdmin: Switch
    private lateinit var swCleaner: Switch

    // côté GitHub
    private lateinit var userField: EditText
    private var perm = "push"
    private lateinit var keyTitle: EditText
    private lateinit var keyValue: EditText
    private lateinit var keyId: EditText
    private lateinit var swKeyRo: Switch
    private lateinit var instField: EditText
    private lateinit var lockBranch: EditText
    private var installs: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        store = AccessStore(this)
        buildUi()
        refreshRules()
        refreshTokens()
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi() {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 16)
        c.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 28))

        val back = Ui.button(this, "← Retour")
        back.setOnClickListener { finish() }
        val title = Ui.heading(this, "Accès et droits")
        title.textSize = 20f
        c.addView(Ui.row(this, 0, back, title))

        // --- dépôt
        val dep = card(c, "Dépôt")
        repoField = Ui.combo(this, "pseudo/depot", { Suggest.repos(this) }, { Suggest.remoteRepos(this) })
        repoField.setText(Settings(this).repo)
        dep.addView(repoField, Ui.params(this, 8))

        // --- règles de l'appli
        val rules = card(c, "Règles de l'appli")
        rules.addView(
            Ui.note(this, "Appliquées avant chaque requête GitHub. Sans règle : tout est permis, sauf en liste blanche."),
            Ui.params(this, 4)
        )
        val wl = switch("Liste blanche (dépôts sans règle bloqués)", store.whitelist)
        wl.setOnCheckedChangeListener { _, b -> store.whitelist = b; refreshRules() }
        rules.addView(wl, Ui.params(this, 8))

        levelBtn = Ui.button(this, "")
        levelBtn.setOnClickListener { level = next(level); paint() }
        claudeBtn = Ui.button(this, "")
        claudeBtn.setOnClickListener { claude = next(claude); paint() }
        rules.addView(Ui.row(this, 10, levelBtn, claudeBtn))
        swBranch = switch("Créer des branches", true)
        swDelete = switch("Suppressions (fichiers, branches, compilations, force)", true)
        swAdmin = switch("Administration (visibilité, transfert, suppression du dépôt)", true)
        swCleaner = switch("Nettoyage automatique autorisé", true)
        for (s in listOf(swBranch, swDelete, swAdmin, swCleaner)) rules.addView(s, Ui.params(this, 6))
        tokenBtn = Ui.button(this, "")
        tokenBtn.setOnClickListener { pickToken() }
        rules.addView(tokenBtn, Ui.params(this, 8))
        val load = Ui.button(this, "Charger")
        load.setOnClickListener { loadRule() }
        val save = Ui.button(this, "Enregistrer", primary = true)
        save.setOnClickListener { saveRule() }
        val del = Ui.button(this, "Retirer")
        del.setOnClickListener {
            val r = repoOrNull() ?: return@setOnClickListener toast("Dépôt invalide.")
            store.remove(r); refreshRules(); toast("Règle retirée.")
        }
        rules.addView(Ui.row(this, 8, load, save, del))
        val ro = Ui.button(this, "Tout en lecture seule")
        ro.setOnClickListener { allReadOnly() }
        rules.addView(ro, Ui.params(this, 6))
        rulesBox = LinearLayout(this)
        rulesBox.orientation = LinearLayout.VERTICAL
        rules.addView(rulesBox, Ui.params(this, 8))
        paint()

        // --- jetons
        val tok = card(c, "Jetons")
        tok.addView(
            Ui.note(this, "Jeton par défaut : Compte. Ajoute des jetons fins (un par dépôt, droits limités) puis associe-les dans la règle."),
            Ui.params(this, 4)
        )
        val label = Ui.field(this, "Nom (ex. CleanOut lecture)")
        val value = Ui.field(this, "Jeton ghp_… / github_pat_…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        tok.addView(label, Ui.params(this, 8))
        tok.addView(value, Ui.params(this, 6))
        val add = Ui.button(this, "Ajouter le jeton")
        add.setOnClickListener {
            val t = Settings.cleanToken(value.text.toString())
            if (t.length < 20) return@setOnClickListener toast("Jeton invalide.")
            store.addToken(label.text.toString().trim(), t)
            label.setText(""); value.setText("")
            refreshTokens(); toast("Jeton ajouté (chiffré).")
        }
        val fine = Ui.button(this, "Créer (fin)")
        fine.setOnClickListener { open(GitHubTools.NEW_FINE_TOKEN_URL) }
        tok.addView(Ui.row(this, 8, add, fine))
        tokensBox = LinearLayout(this)
        tokensBox.orientation = LinearLayout.VERTICAL
        tok.addView(tokensBox, Ui.params(this, 8))

        // --- collaborateurs
        val col = card(c, "Collaborateurs du dépôt")
        col.addView(Ui.note(this, "Dépôt personnel : un collaborateur a toujours lecture-écriture. Rôles fins : dépôts d'organisation."), Ui.params(this, 4))
        userField = Ui.field(this, "Pseudo GitHub")
        col.addView(userField, Ui.params(this, 8))
        val permBtn = Ui.button(this, "Rôle : push")
        permBtn.setOnClickListener {
            val all = listOf("pull", "triage", "push", "maintain", "admin")
            perm = all[(all.indexOf(perm) + 1) % all.size]
            permBtn.text = "Rôle : $perm"
        }
        col.addView(Ui.row(this, 8, permBtn, act("Lister") { api -> collaborators(api, repo()) }))
        col.addView(
            Ui.row(
                this, 6,
                act("Inviter / modifier") { api -> addCollaborator(api, repo(), user(), perm) },
                danger("Retirer l'accès") { api -> removeCollaborator(api, repo(), user()) }
            )
        )

        // --- clés de déploiement
        val keys = card(c, "Clés de déploiement (SSH)")
        keys.addView(Ui.note(this, "Accès d'un serveur ou service à CE dépôt seulement, en lecture seule ou lecture-écriture."), Ui.params(this, 4))
        keyTitle = Ui.field(this, "Nom de la clé")
        keyValue = Ui.field(this, "ssh-ed25519 AAAA…")
        swKeyRo = switch("Lecture seule", true)
        keys.addView(keyTitle, Ui.params(this, 8))
        keys.addView(keyValue, Ui.params(this, 6))
        keys.addView(swKeyRo, Ui.params(this, 6))
        keys.addView(Ui.row(this, 8, act("Lister") { api -> deployKeys(api, repo()) }, act("Ajouter") { api -> addKey(api, repo()) }))
        keyId = Ui.field(this, "ID de la clé à retirer", InputType.TYPE_CLASS_NUMBER)
        keys.addView(keyId, Ui.params(this, 8))
        keys.addView(danger("Retirer la clé") { api -> removeKey(api, repo()) }, Ui.params(this, 6))

        // --- applis GitHub
        val apps = card(c, "Applis GitHub (Claude…)")
        apps.addView(
            Ui.note(
                this,
                "Une appli installée (ex. Claude) a les droits fixés par l'appli, sur les dépôts choisis. " +
                    "Ici : ajouter/retirer le dépôt. Pour lecture seule, retire le dépôt ou verrouille la branche."
            ),
            Ui.params(this, 4)
        )
        instField = Ui.combo(this, "Installation (ID)", { installs })
        apps.addView(instField, Ui.params(this, 8))
        apps.addView(Ui.row(this, 8, act("Lister les applis") { api -> installations(api, repo()) }, View(this)))
        apps.addView(
            Ui.row(
                this, 6,
                act("Donner accès") { api -> setInstallRepo(api, repo(), true) },
                danger("Retirer l'accès") { api -> setInstallRepo(api, repo(), false) }
            )
        )
        val page = Ui.button(this, "Page des installations")
        page.setOnClickListener { open("https://github.com/settings/installations") }
        apps.addView(page, Ui.params(this, 6))

        // --- verrou de branche
        val lock = card(c, "Verrou de branche (lecture seule)")
        lock.addView(
            Ui.note(
                this,
                "Bloque les push des collaborateurs, clés et applis (Claude…). Toi (admin) gardes l'accès. " +
                    "Dépôt privé : nécessite GitHub Pro."
            ),
            Ui.params(this, 4)
        )
        lockBranch = Ui.field(this, "Branche (vide = par défaut)")
        lock.addView(lockBranch, Ui.params(this, 8))
        lock.addView(
            Ui.row(
                this, 8,
                act("Verrouiller") { api -> setLock(api, repo(), lockBranch.text.toString().trim(), true) },
                act("Déverrouiller") { api -> setLock(api, repo(), lockBranch.text.toString().trim(), false) }
            )
        )

        // --- résultat
        val res = card(c, "Résultat")
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.isIndeterminate = true
        progress.visibility = View.GONE
        res.addView(progress, Ui.params(this, 6))
        out = TextView(this)
        out.setTextColor(Ui.TEXT)
        out.textSize = 12f
        out.typeface = Typeface.MONOSPACE
        out.setTextIsSelectable(true)
        res.addView(out, Ui.params(this, 6))

        scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        scroll.addView(c)
        setContentView(scroll)
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val k = Ui.card(this)
        k.addView(Ui.heading(this, title))
        parent.addView(k, Ui.params(this, 14))
        return k
    }

    private fun switch(text: String, on: Boolean): Switch {
        val s = Switch(this)
        s.text = text
        s.setTextColor(Ui.TEXT)
        s.isChecked = on
        return s
    }

    private fun next(l: Level) = Level.values()[(l.ordinal + 1) % Level.values().size]

    private fun paint() {
        levelBtn.text = "Appli : ${level.label}"
        claudeBtn.text = "Claude : ${claude.label}"
        tokenBtn.text = "Jeton : " + (store.tokenLabel(tokenId) ?: "par défaut")
        val w = level == Level.WRITE
        for (s in listOf(swBranch, swDelete, swAdmin, swCleaner)) {
            s.isEnabled = w
            s.alpha = if (w) 1f else 0.5f
        }
    }

    // ------------------------------------------------------------------ règles

    private fun loadRule(rule: AccessRule? = null) {
        val r = rule ?: store.rule(repoOrNull() ?: return toast("Dépôt invalide.")) ?: AccessRule(repoOrNull()!!)
        repoField.setText(r.repo)
        level = r.level; claude = r.claude; tokenId = r.tokenId
        swBranch.isChecked = r.branch; swDelete.isChecked = r.delete; swAdmin.isChecked = r.admin; swCleaner.isChecked = r.cleaner
        paint()
        if (rule == null && store.rule(r.repo) == null) toast("Aucune règle : valeurs par défaut.")
    }

    private fun saveRule() {
        val repo = repoOrNull() ?: return toast("Dépôt invalide : pseudo/depot.")
        store.save(
            AccessRule(repo, level, swBranch.isChecked, swDelete.isChecked, swAdmin.isChecked, claude, swCleaner.isChecked, tokenId)
        )
        refreshRules()
        toast("Règle enregistrée.")
    }

    private fun allReadOnly() {
        val names = (store.rules().map { it.repo } + Settings(this).repos().map { it.repo.lowercase() }).distinct()
        if (names.isEmpty()) return toast("Aucun dépôt connu.")
        confirm("Passer ${names.size} dépôt(s) en lecture seule ?", "Appli et Claude : lecture seule. Jetons associés conservés.") {
            for (n in names) {
                val r = store.rule(n) ?: AccessRule(n)
                store.save(r.copy(level = Level.READ, claude = Level.READ))
            }
            refreshRules()
            toast("Lecture seule partout.")
        }
    }

    private fun refreshRules() {
        rulesBox.removeAllViews()
        val list = store.rules()
        if (list.isEmpty()) {
            rulesBox.addView(Ui.note(this, if (store.whitelist) "Aucune règle : tous les dépôts sont bloqués." else "Aucune règle : tout est permis."))
            return
        }
        for (r in list) {
            val t = Ui.note(this, "• " + r.summary(store.tokenLabel(r.tokenId)))
            t.setTextColor(if (r.level == Level.BLOCKED) Ui.ERROR else if (r.level == Level.READ) Ui.WARN else Ui.TEXT)
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))
            t.setOnClickListener { loadRule(r) }
            rulesBox.addView(t)
        }
    }

    private fun pickToken() {
        val list = store.tokens()
        val names = arrayOf("Par défaut (Compte)") + list.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Jeton pour ce dépôt")
            .setItems(names) { _, i ->
                tokenId = if (i == 0) "" else list[i - 1].id
                paint()
            }
            .show()
    }

    // ------------------------------------------------------------------ jetons

    private fun refreshTokens() {
        tokensBox.removeAllViews()
        val def = Ui.note(this, "• Par défaut (Compte)" + if (Settings(this).hasToken()) "" else " — absent")
        val tDef = Ui.button(this, "Tester")
        tDef.setOnClickListener { Settings(this).token()?.let { t -> testToken(t) } ?: toast("Aucun jeton par défaut.") }
        tokensBox.addView(def)
        tokensBox.addView(Ui.row(this, 4, tDef, View(this), View(this)))
        for (e in store.tokens()) {
            val used = store.rules().count { it.tokenId == e.id }
            tokensBox.addView(Ui.note(this, "• ${e.label}" + if (used > 0) " — $used dépôt(s)" else ""), Ui.params(this, 10))
            val test = Ui.button(this, "Tester")
            test.setOnClickListener { store.tokenValue(e.id)?.let { testToken(it) } ?: toast("Jeton illisible.") }
            val revoke = Ui.button(this, "Révoquer")
            revoke.setOnClickListener { revoke(e) }
            val rm = Ui.button(this, "Retirer")
            rm.setOnClickListener {
                confirm("Retirer « ${e.label} » de l'appli ?", "Le jeton reste valide sur GitHub. Les dépôts associés repassent au jeton par défaut.") {
                    store.removeToken(e.id); refreshTokens(); refreshRules(); paint()
                }
            }
            tokensBox.addView(Ui.row(this, 4, test, revoke, rm))
        }
    }

    private fun testToken(token: String) = execWith(token) { api ->
        val r = api.get("/user")
        if (!r.ok) throw GitHubApi.explain(r, "Test du jeton")
        val login = r.json?.optString("login").orEmpty()
        val kind = if (token.startsWith("github_pat_")) "fin (droits par dépôt)" else "classique"
        "✓ $login · jeton $kind\n" +
            "Droits : ${r.scopes?.ifBlank { null } ?: if (token.startsWith("github_pat_")) "définis sur GitHub" else "aucun"}\n" +
            "Expire : ${r.expires ?: "jamais / inconnu"}"
    }

    private fun revoke(e: TokenEntry) {
        val t = store.tokenValue(e.id) ?: return toast("Jeton illisible.")
        confirm("Révoquer « ${e.label} » sur GitHub ?", "Le jeton devient inutilisable partout, définitivement. Il est aussi retiré de l'appli.") {
            execWith(t) { api ->
                val r = api.call("POST", "/credentials/revoke", JSONObject().put("credentials", JSONArray().put(t)))
                if (r.ok) {
                    runOnUiThread { store.removeToken(e.id); refreshTokens(); refreshRules(); paint() }
                    "✓ Révocation demandée (prise en compte sous quelques minutes)."
                } else {
                    runOnUiThread { open("https://github.com/settings/tokens") }
                    "✗ Révocation API impossible (HTTP ${r.code}). Supprime-le sur la page des jetons."
                }
            }
        }
    }

    // ------------------------------------------------------------------ GitHub : collaborateurs

    private fun collaborators(api: GitHubApi, repo: String): String {
        val r = api.get("/repos/$repo/collaborators?per_page=100")
        if (!r.ok) throw GitHubApi.explain(r, "Collaborateurs")
        val arr = JSONArray(r.text)
        val sb = StringBuilder("Collaborateurs de $repo :\n")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            sb.append("• ${o.optString("login")} — ${o.optString("role_name", roleOf(o.optJSONObject("permissions")))}\n")
        }
        val inv = api.get("/repos/$repo/invitations?per_page=100")
        if (inv.ok) {
            val a = JSONArray(inv.text)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                sb.append("• ${o.optJSONObject("invitee")?.optString("login")} — invitation en attente (${o.optString("permissions")}) · ID ${o.optLong("id")}\n")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun roleOf(p: JSONObject?): String = when {
        p == null -> "?"
        p.optBoolean("admin") -> "admin"
        p.optBoolean("maintain") -> "maintain"
        p.optBoolean("push") -> "write"
        p.optBoolean("triage") -> "triage"
        else -> "read"
    }

    private fun addCollaborator(api: GitHubApi, repo: String, user: String, perm: String): String {
        val r = api.call("PUT", "/repos/$repo/collaborators/${Uri.encode(user)}", JSONObject().put("permission", perm))
        return when (r.code) {
            201 -> "✓ Invitation envoyée à $user ($perm)."
            204 -> "✓ $user : droits mis à jour ($perm)."
            else -> throw GitHubApi.explain(r, "Ajout du collaborateur")
        }
    }

    private fun removeCollaborator(api: GitHubApi, repo: String, user: String): String {
        val r = api.call("DELETE", "/repos/$repo/collaborators/${Uri.encode(user)}", null)
        if (r.ok) return "✓ Accès de $user retiré de $repo."
        // invitation en attente ?
        val inv = api.get("/repos/$repo/invitations?per_page=100")
        if (inv.ok) {
            val a = JSONArray(inv.text)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optJSONObject("invitee")?.optString("login").equals(user, true)) {
                    val d = api.call("DELETE", "/repos/$repo/invitations/${o.optLong("id")}", null)
                    if (d.ok) return "✓ Invitation de $user annulée."
                }
            }
        }
        throw GitHubApi.explain(r, "Retrait du collaborateur")
    }

    // ------------------------------------------------------------------ GitHub : clés de déploiement

    private fun deployKeys(api: GitHubApi, repo: String): String {
        val r = api.get("/repos/$repo/keys?per_page=100")
        if (!r.ok) throw GitHubApi.explain(r, "Clés de déploiement")
        val arr = JSONArray(r.text)
        if (arr.length() == 0) return "Aucune clé de déploiement sur $repo."
        val sb = StringBuilder("Clés de $repo :\n")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            sb.append("• ID ${o.optLong("id")} — ${o.optString("title")} — ")
                .append(if (o.optBoolean("read_only", true)) "lecture seule" else "lecture-écriture").append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun addKey(api: GitHubApi, repo: String): String {
        val key = ui { keyValue.text.toString().trim() }
        val title = ui { keyTitle.text.toString().trim() }.ifEmpty { "Envoi GitHub" }
        val ro = ui { swKeyRo.isChecked }
        if (!key.startsWith("ssh-") && !key.startsWith("ecdsa-")) throw PushException("Clé publique SSH attendue (ssh-ed25519 …).")
        val r = api.call("POST", "/repos/$repo/keys", JSONObject().put("title", title).put("key", key).put("read_only", ro))
        if (!r.ok) throw GitHubApi.explain(r, "Ajout de la clé")
        return "✓ Clé « $title » ajoutée (${if (ro) "lecture seule" else "lecture-écriture"}) · ID ${r.json?.optLong("id")}"
    }

    private fun removeKey(api: GitHubApi, repo: String): String {
        val id = ui { keyId.text.toString().trim() }.toLongOrNull() ?: throw PushException("ID de clé manquant (bouton Lister).")
        val r = api.call("DELETE", "/repos/$repo/keys/$id", null)
        if (!r.ok) throw GitHubApi.explain(r, "Retrait de la clé")
        return "✓ Clé $id retirée de $repo."
    }

    // ------------------------------------------------------------------ GitHub : applis installées

    private fun installations(api: GitHubApi, repo: String): String {
        val r = api.get("/user/installations?per_page=100")
        if (!r.ok) {
            runOnUiThread { open("https://github.com/settings/installations") }
            return "✗ Liste illisible avec ce jeton (HTTP ${r.code}). Page des installations ouverte : " +
                "l'ID est dans l'adresse (…/installations/<ID>). Saisis-le puis Donner/Retirer l'accès."
        }
        val arr = r.json?.optJSONArray("installations") ?: JSONArray()
        if (arr.length() == 0) return "Aucune appli GitHub installée."
        val sb = StringBuilder("Applis installées (accès à $repo) :\n")
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optLong("id")
            val slug = o.optString("app_slug")
            val all = o.optString("repository_selection") == "all"
            val has = if (all) true else installHas(api, id, repo)
            val perms = o.optJSONObject("permissions")?.let { p -> listOf("contents", "pull_requests", "workflows").mapNotNull { k -> p.optString(k).ifEmpty { null }?.let { "$k:$it" } } }
            sb.append("• $slug · ID $id · ")
                .append(if (all) "tous les dépôts" else "dépôts choisis")
                .append(" · $repo : ").append(if (has == true) "OUI" else if (has == false) "non" else "?")
                .append(perms?.takeIf { it.isNotEmpty() }?.joinToString(", ", " · ") ?: "")
                .append('\n')
            ids.add("$id $slug")
        }
        runOnUiThread { installs = ids }
        sb.append("Mode « tous les dépôts » : modifier sur la page des installations.")
        return sb.toString()
    }

    private fun installHas(api: GitHubApi, id: Long, repo: String): Boolean? {
        var page = 1
        while (page <= 10) {
            val r = api.get("/user/installations/$id/repositories?per_page=100&page=$page")
            if (!r.ok) return null
            val a = r.json?.optJSONArray("repositories") ?: return null
            for (i in 0 until a.length()) if (a.getJSONObject(i).optString("full_name").equals(repo, true)) return true
            if (a.length() < 100) return false
            page++
        }
        return null
    }

    private fun setInstallRepo(api: GitHubApi, repo: String, add: Boolean): String {
        val inst = ui { instField.text.toString().trim() }.substringBefore(' ').toLongOrNull()
            ?: throw PushException("ID d'installation manquant (Lister les applis).")
        val info = api.get("/repos/$repo")
        if (!info.ok) throw GitHubApi.explain(info, "Lecture du dépôt")
        val rid = info.json?.optLong("id") ?: throw PushException("ID du dépôt illisible.")
        val r = api.call(if (add) "PUT" else "DELETE", "/user/installations/$inst/repositories/$rid", null)
        if (r.ok) return if (add) "✓ $repo ajouté à l'installation $inst." else "✓ $repo retiré de l'installation $inst."
        if (r.code == 403 || r.code == 422) {
            runOnUiThread { open("https://github.com/settings/installations/$inst") }
            return "✗ Refusé (HTTP ${r.code}) : jeton classique « repo » requis, et l'appli doit être en « dépôts choisis ». Page ouverte."
        }
        throw GitHubApi.explain(r, "Accès de l'appli")
    }

    // ------------------------------------------------------------------ GitHub : verrou de branche

    private fun setLock(api: GitHubApi, repo: String, branch: String, lock: Boolean): String {
        val br = branch.ifEmpty {
            val i = api.get("/repos/$repo")
            if (!i.ok) throw GitHubApi.explain(i, "Lecture du dépôt")
            i.json?.optString("default_branch").orEmpty().ifEmpty { "main" }
        }
        val path = "/repos/$repo/branches/${Uri.encode(br, "/")}/protection"
        if (!lock) {
            val r = api.call("DELETE", path, null)
            if (r.ok || r.code == 404) return "✓ $repo · $br déverrouillée."
            throw GitHubApi.explain(r, "Déverrouillage")
        }
        val body = JSONObject()
            .put("required_status_checks", JSONObject.NULL)
            .put("enforce_admins", false)
            .put("required_pull_request_reviews", JSONObject.NULL)
            .put("restrictions", JSONObject.NULL)
            .put("lock_branch", true)
            .put("allow_force_pushes", false)
            .put("allow_deletions", false)
        val r = api.call("PUT", path, body)
        if (r.ok) return "✓ $repo · $br verrouillée : lecture seule pour collaborateurs, clés et applis."
        if (r.code == 403) throw PushException("Verrou refusé (403) : dépôt privé sans GitHub Pro, ou jeton sans droit admin.")
        throw GitHubApi.explain(r, "Verrouillage")
    }

    // ------------------------------------------------------------------ exécution

    private fun act(label: String, work: (GitHubApi) -> String): TextView {
        val b = Ui.button(this, label)
        b.setOnClickListener { exec(work) }
        buttons.add(b)
        return b
    }

    private fun danger(label: String, work: (GitHubApi) -> String): TextView {
        val b = Ui.button(this, label)
        b.setTextColor(Ui.ERROR)
        b.setOnClickListener { confirm("$label ?", "Dépôt : ${repoOrNull() ?: "?"}") { exec(work) } }
        buttons.add(b)
        return b
    }

    private fun exec(work: (GitHubApi) -> String) {
        val token = Settings(this).token() ?: return toast("Aucun jeton : renseigne-le dans Compte.")
        execWith(token, work)
    }

    /** Exécute hors fil UI avec le service ACCESS (non bridé par les règles, jeton propre au dépôt si défini). */
    private fun execWith(token: String, work: (GitHubApi) -> String) {
        if (busy) return toast("Opération en cours…")
        setBusy(true)
        out.text = "…"
        Thread {
            val text = try {
                work(GitHubApi(token).also { it.service = Service.ACCESS })
            } catch (e: PushException) {
                "✗ " + e.message
            } catch (e: Exception) {
                "✗ ${e.javaClass.simpleName} : ${e.message ?: ""}"
            }
            runOnUiThread {
                if (!alive) return@runOnUiThread
                out.text = text
                setBusy(false)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }.start()
    }

    /** Lit une valeur de vue depuis un fil secondaire. */
    private fun <T> ui(f: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return f()
        val lock = java.util.concurrent.CountDownLatch(1)
        var v: T? = null
        runOnUiThread { v = f(); lock.countDown() }
        lock.await()
        @Suppress("UNCHECKED_CAST")
        return v as T
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        for (x in buttons) {
            x.isEnabled = !b
            x.alpha = if (b) 0.5f else 1f
        }
    }

    private fun repoOrNull(): String? = RepoRef.parse(repoField.text.toString())?.let { "${it.first}/${it.second}" }
    private fun repo(): String = ui { repoOrNull() } ?: throw PushException("Dépôt invalide : écris « pseudo/depot ».")
    private fun user(): String = ui { userField.text.toString().trim().removePrefix("@") }.ifEmpty { throw PushException("Pseudo manquant.") }

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Confirmer") { _, _ -> onYes() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Aucun navigateur.")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
