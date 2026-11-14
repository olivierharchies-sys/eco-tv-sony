package com.example.gitzippush

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Formulaire des fonctions GitHub essentielles : jeton, quotas, facturation, dépôt, transfert, compilation gratuite. */
class GitHubToolsActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var out: TextView
    private lateinit var progress: ProgressBar
    private lateinit var repoField: EditText
    private lateinit var branchField: EditText
    private lateinit var ownerField: EditText
    private lateinit var newNameField: EditText
    private lateinit var mirrorField: EditText
    private lateinit var installSwitch: Switch
    private val buttons = ArrayList<TextView>()
    @Volatile private var alive = true
    @Volatile private var busy = false
    @Volatile private var cancel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        buildUi()
    }

    override fun onDestroy() {
        alive = false
        cancel = true
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
        val title = Ui.heading(this, "Outils GitHub")
        title.textSize = 20f
        c.addView(Ui.row(this, 0, back, title))
        c.addView(Ui.note(this, "ⓘ " + Help.HINT), Ui.params(this, 8))

        // --- jeton
        val tok = card(c, "Jeton")
        val help = Ui.note(this, GitHubTools.TOKEN_HELP)
        help.visibility = View.GONE
        val toggle = Ui.button(this, "Explications")
        toggle.setOnClickListener {
            help.visibility = if (help.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        tok.addView(Ui.row(this, 8, toggle, act("Vérifier") { api -> GitHubTools.tokenInfo(api) }))
        tok.addView(help, Ui.params(this, 8))
        val create = Ui.button(this, "Créer (classique)")
        create.setOnClickListener { open(GitHubTools.NEW_TOKEN_URL) }
        val fine = Ui.button(this, "Créer (fin)")
        fine.setOnClickListener { open(GitHubTools.NEW_FINE_TOKEN_URL) }
        tok.addView(Ui.row(this, 6, create, fine))

        // --- quotas et facturation
        val bill = card(c, "Quotas et facturation")
        bill.addView(Ui.note(this, "Minutes Actions, limites d'API, consommation et coût du mois."), Ui.params(this, 4))
        bill.addView(
            Ui.row(
                this, 8,
                act("Quotas") { api -> GitHubTools.quotas(api) },
                act("Facturation") { api -> GitHubTools.billing(api) }
            )
        )
        val site = Ui.button(this, "Page facturation")
        site.setOnClickListener { open(GitHubTools.BILLING_URL) }
        val budget = Ui.button(this, "Limite de dépenses")
        budget.setOnClickListener { open(GitHubTools.BUDGETS_URL) }
        bill.addView(Ui.row(this, 6, site, budget))

        // --- dépôt
        val s = Settings(this)
        val dep = card(c, "Dépôt")
        repoField = Ui.combo(this, "pseudo/depot", { Suggest.repos(this) }, { Suggest.remoteRepos(this) })
        repoField.setText(s.repo)
        branchField = Ui.combo(
            this, "Branche (vide = par défaut)",
            { Suggest.branches(this, repoField.text.toString()) }, { Suggest.remoteBranches(this, repoField.text.toString()) }
        )
        branchField.setText(s.branch)
        dep.addView(repoField, Ui.params(this, 8))
        dep.addView(branchField, Ui.params(this, 6))
        dep.addView(
            Ui.row(
                this, 8,
                act("Stockage") { api -> GitHubTools.storage(api, repo()) },
                act("Rétention 1 j") { api -> GitHubTools.setRetention(api, repo(), 1) }
            )
        )
        dep.addView(
            Ui.row(
                this, 6,
                act("Lancer compilation") { api -> GitHubTools.startBuild(api, repo(), branch()) },
                act("Annuler en cours") { api -> GitHubTools.cancelBuilds(api, repo()) }
            )
        )

        // --- visibilité et transfert
        val vis = card(c, "Visibilité et transfert")
        vis.addView(
            Ui.note(this, "Dépôt public = compilations gratuites, mais code et historique visibles de tous. Vérifie qu'aucun secret n'y traîne."),
            Ui.params(this, 4)
        )
        val pub = Ui.button(this, "Rendre public")
        pub.setOnClickListener {
            confirm("Rendre ${repoOrNull() ?: "?"} PUBLIC ?", "Tout le code et l'historique deviennent visibles de tous.") {
                exec { api -> GitHubTools.setPrivate(api, repo(), false) }
            }
        }
        buttons.add(pub)
        vis.addView(Ui.row(this, 8, act("Visibilité ?") { api -> "${repo()} : ${GitHubTools.visibility(api, repo())}" }, pub))
        vis.addView(Ui.row(this, 6, act("Rendre privé") { api -> GitHubTools.setPrivate(api, repo(), true) }, View(this)))
        ownerField = Ui.combo(this, "Nouveau propriétaire (pseudo ou organisation)", { Suggest.owners(this) })
        newNameField = Ui.field(this, "Nouveau nom (facultatif)")
        vis.addView(ownerField, Ui.params(this, 10))
        vis.addView(newNameField, Ui.params(this, 6))
        val tr = Ui.button(this, "Transférer le dépôt")
        tr.setOnClickListener {
            val owner = ownerField.text.toString().trim()
            if (owner.isEmpty()) return@setOnClickListener toast("Indique le nouveau propriétaire.")
            confirm("Transférer ${repoOrNull() ?: "?"} vers $owner ?", "Le dépôt change d'adresse. Les liens mémorisés devront être mis à jour.") {
                exec { api ->
                    val r = GitHubTools.transfer(api, repo(), owner, newNameField.text.toString().trim())
                    val dest = owner + "/" + newNameField.text.toString().trim().ifEmpty { repo().substringAfter('/') }
                    runOnUiThread { repoField.setText(dest) }
                    r
                }
            }
        }
        buttons.add(tr)
        vis.addView(tr, Ui.params(this, 8))

        // --- compilation gratuite
        val free = card(c, "Compiler l'APK sans minutes")
        free.addView(
            Ui.note(
                this,
                "Copie le dépôt (privé) vers un dépôt PUBLIC de compilation, en un commit, puis récupère l'APK. " +
                    "Le dépôt d'origine reste privé ; la copie est publique."
            ),
            Ui.params(this, 4)
        )
        mirrorField = Ui.combo(this, "Dépôt de compilation (vide = <depot>-build)", {
            val r = repoOrNull()
            (if (r != null) listOf("$r-build") else emptyList()) + Suggest.repos(this).filter { it.endsWith("-build") }
        })
        free.addView(mirrorField, Ui.params(this, 8))
        installSwitch = Switch(this)
        installSwitch.text = "Installer l'APK à la fin"
        installSwitch.setTextColor(Ui.TEXT)
        installSwitch.isChecked = true
        Help.attach(installSwitch)
        free.addView(installSwitch, Ui.params(this, 8))
        val go = Ui.button(this, "Copier + compiler + APK", primary = true)
        go.setOnClickListener { startMirror() }
        buttons.add(go)
        free.addView(go, Ui.params(this, 8))

        // --- résultat
        val res = card(c, "Résultat")
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        res.addView(progress, Ui.params(this, 6))
        out = TextView(this)
        out.setTextColor(Ui.TEXT)
        out.textSize = 12f
        out.typeface = Typeface.MONOSPACE
        out.setTextIsSelectable(true)
        res.addView(out, Ui.params(this, 6))
        val copy = Ui.button(this, "Copier")
        copy.setOnClickListener { copy(out.text.toString()) }
        val stop = Ui.button(this, "Annuler")
        stop.setOnClickListener { cancel = true }
        res.addView(Ui.row(this, 8, copy, stop))

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

    /** Bouton qui exécute [work] hors fil UI et affiche son texte. */
    private fun act(label: String, work: (GitHubApi) -> String): TextView {
        val b = Ui.button(this, label)
        b.setOnClickListener { exec(work) }
        buttons.add(b)
        return b
    }

    private fun exec(work: (GitHubApi) -> String) {
        if (busy) return toast("Opération en cours…")
        val token = Settings(this).token() ?: return toast("Aucun jeton : renseigne-le dans Compte.")
        setBusy(true)
        out.text = "…"
        showResult()
        Thread {
            val text = try {
                work(GitHubApi(token))
            } catch (e: PushException) {
                "✗ " + e.message
            } catch (e: Exception) {
                "✗ ${e.javaClass.simpleName} : ${e.message ?: ""}"
            }
            ui {
                out.text = text
                setBusy(false)
                showResult()
            }
        }.start()
    }

    private fun startMirror() {
        if (busy) return toast("Opération en cours…")
        val src = repoOrNull() ?: return toast("Dépôt invalide : pseudo/depot.")
        val token = Settings(this).token() ?: return toast("Aucun jeton : renseigne-le dans Compte.")
        val target = RepoRef.parse(mirrorField.text.toString())?.let { "${it.first}/${it.second}" }
            ?: "${src.substringBefore('/')}/${src.substringAfter('/')}-build"
        if (target.equals(src, true)) return toast("Le dépôt de compilation doit être différent de la source.")
        val install = installSwitch.isChecked
        if (install && !ApkInstaller.canInstall(this)) {
            toast("Autorise d'abord l'installation d'applis pour Envoi GitHub, puis relance.")
            try {
                startActivity(ApkInstaller.permissionIntent(this))
            } catch (e: Exception) {
                // réglage introuvable
            }
            return
        }
        confirm("Copier $src vers $target (public) ?", "Le code sera visible publiquement dans $target. Aucun secret ne doit s'y trouver.") {
            setBusy(true)
            cancel = false
            out.text = ""
            showResult()
            val br = branch()
            Thread {
                val api = GitHubApi(token)
                api.onWait = { sec -> line("Limite d'API : attente de $sec s…") }
                try {
                    val m = GitHubTools.mirror(api, src, br, target) { line(it) }
                    line("Attente de la compilation et de l'APK…")
                    val (o, r) = m.target.split('/', limit = 2).let { it[0] to it[1] }
                    val fetcher = ApkFetcher(api, File(cacheDir, "apk"), { line(it) }, { p -> ui { progress.isIndeterminate = false; progress.progress = p } }, { cancel || !alive })
                    val res = fetcher.fetch(o, r, m.branch, m.sha)
                    line("✓ APK : ${res.apk.name}")
                    if (install) {
                        ApkInstaller.install(applicationContext, res.apk)
                        line("✓ Valide la fenêtre d'installation d'Android.")
                    }
                } catch (e: PushException) {
                    line("✗ " + e.message)
                } catch (e: Exception) {
                    line("✗ ${e.javaClass.simpleName} : ${e.message ?: ""}")
                } finally {
                    ui { setBusy(false) }
                }
            }.start()
        }
    }

    // ------------------------------------------------------------------ outils

    private fun repoOrNull(): String? = RepoRef.parse(repoField.text.toString())?.let { "${it.first}/${it.second}" }
    private fun repo(): String = repoOrNull() ?: throw PushException("Dépôt invalide : écris « pseudo/depot ».")
    private fun branch(): String = branchField.text.toString().trim()

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        progress.isIndeterminate = true
        for (x in buttons) {
            x.isEnabled = !b
            x.alpha = if (b) 0.5f else 1f
        }
    }

    private fun line(s: String) = ui {
        out.append(s + "\n")
        showResult()
    }

    private fun ui(f: () -> Unit) = runOnUiThread { if (alive) f() }

    private fun showResult() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

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

    private fun copy(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GitHub", text))
            toast("Copié.")
        } catch (e: Exception) {
            toast("Copie impossible.")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
