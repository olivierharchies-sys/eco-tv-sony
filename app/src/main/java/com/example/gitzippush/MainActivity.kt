package com.example.gitzippush

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Écran principal en tuiles : Envoyer, APK, Historique, Rapports d'erreur, Nettoyage GitHub, Importer, Explorer, Compte.
 * Chaque tuile ouvre sa section ; « ← Accueil » ou Retour revient aux tuiles.
 */
class MainActivity : Activity() {

    private enum class Kind { NONE, ZIP, TREE, FILES }

    private lateinit var settings: Settings

    // accueil en tuiles et sections
    private lateinit var rootScroll: ScrollView
    private lateinit var home: LinearLayout
    private lateinit var sectionBar: LinearLayout
    private lateinit var sectionTitle: TextView
    private lateinit var tabViews: List<LinearLayout>
    private var current = HOME
    private val tileSubs = HashMap<String, TextView>()

    // section Nettoyage GitHub
    private lateinit var cleanRepoField: EditText
    private lateinit var cleanAllSwitch: Switch
    private lateinit var cleanKeepButton: TextView
    private lateinit var cleanResult: TextView
    private lateinit var btnCleanScan: TextView
    private lateinit var btnCleanRun: TextView
    private var cleanPlans: List<GitHubCleaner.Plan> = emptyList()

    // section Rapports d'erreur
    private lateinit var reportList: LinearLayout

    // onglet Envoyer
    private lateinit var repoField: EditText
    private lateinit var branchField: EditText
    private lateinit var messageField: EditText
    private lateinit var destStatus: TextView
    private lateinit var recapNote: TextView
    private lateinit var privateSwitch: Switch
    private lateinit var btnCreate: TextView
    private lateinit var btnRepos: TextView
    private lateinit var srcInfo: TextView
    private lateinit var btnLatest: TextView
    private lateinit var accessNote: TextView
    private lateinit var btnAccess: TextView
    private lateinit var stripSwitch: Switch
    private lateinit var deleteSwitch: Switch
    private lateinit var cleanSwitch: Switch
    private lateinit var ignoreSwitch: Switch
    private lateinit var workflowSwitch: Switch
    private lateinit var targetField: EditText
    private lateinit var btnTest: TextView
    private lateinit var btnPush: TextView
    private lateinit var btnApk: TextView
    private lateinit var autoApkSwitch: Switch

    // onglet Importer
    private lateinit var impRepoField: EditText
    private lateinit var impBranchField: EditText
    private lateinit var btnImpRepos: TextView
    private lateinit var btnPrepare: TextView
    private lateinit var impResult: LinearLayout
    private lateinit var impInfo: TextView
    private lateinit var btnCopy: TextView

    // onglet Fichiers
    private lateinit var archiveList: LinearLayout
    private lateinit var pendingInfo: TextView
    private lateinit var btnCheckBuilds: TextView
    private lateinit var keepButton: TextView
    private lateinit var btnBrowseSrc: TextView

    // onglet Compte
    private lateinit var tokenField: EditText
    private lateinit var tokenInfo: TextView
    private lateinit var showToken: TextView
    private lateinit var btnLoadRepos: TextView

    // zone d'état commune
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCancel: TextView
    private lateinit var resultRow: LinearLayout
    private lateinit var logView: TextView

    private var srcKind = Kind.NONE
    private var srcUris: List<Uri> = emptyList()
    private var srcName = ""
    private var srcAuto = false
    private var autoKey: String? = null
    private var scanKeys: List<String> = emptyList()
    private var scanVersion = 0
    private var latestFile: File? = null
    private var lastCheck: ZipCheck.Result? = null
    /** Restauration en cours : le dépôt choisi ne doit pas être remplacé par la devinette. */
    private var lockRepo = false

    /** Vrai quand l'utilisateur a validé le dépôt pour la source choisie (réponse « Oui », choix dans la liste, saisie). */
    private var repoConfirmed = false

    /** Dépôt deviné, en attente d'une réponse (la boîte de dialogue s'ouvre dès que l'écran Envoyer est affiché). */
    private var pendingGuess: Guess? = null

    /** Aucun dépôt reconnu pour la source choisie. */
    private var guessMissing = false
    private var guessDialog: AlertDialog? = null
    private lateinit var autoVersionSwitch: Switch

    private var lastDigest: DigestResult? = null
    private var tokenVisible = false
    private var tokenSaved = false
    private var running = false
    private var lastRepoUrl: String? = null

    @Volatile
    private var cancelFlag = false

    @Volatile
    private var task: TaskHandle? = null
    private var pendingAutoSha: String? = null
    private var pendingAutoBranch: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        settings = Settings(this)

        buildUi()

        repoField.setText(settings.repo)
        branchField.setText(settings.branch)
        messageField.setText(settings.message)
        tokenSaved = settings.hasToken()
        refreshTokenInfo()
        autoApkSwitch.isChecked = settings.autoApk
        ignoreSwitch.isChecked = settings.ignoreGenerated
        workflowSwitch.isChecked = settings.autoWorkflow

        // reprise des anciens réglages : le dépôt déjà saisi devient un dépôt mémorisé
        if (settings.repos().isEmpty() && settings.repo.isNotEmpty()) {
            val ref = RepoRef.parse(settings.repo)
            if (ref != null) settings.rememberRepo(ref.first + "/" + ref.second, settings.branch)
        }

        CleanJobService.schedule(this, settings.cleanAuto)
        BuildArchive.listener = {
            refreshArchives()
            refreshReports()
            refreshTiles()
        }
        showTab(HOME)
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 && !settings.notifAsked) {
            settings.notifAsked = true
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLatest()
        refreshArchives()
        refreshReports()
        refreshTiles()
        checkPendingBuilds(silent = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current != HOME) showTab(HOME) else super.onBackPressed()
    }

    override fun onDestroy() {
        BuildArchive.listener = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi() {
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 20)
        content.setPadding(pad, Ui.dp(this, 24), pad, Ui.dp(this, 32))

        val title = TextView(this)
        title.text = "Envoi GitHub"
        title.setTextColor(Ui.TEXT)
        title.textSize = 28f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        content.addView(title)

        home = buildHome()
        content.addView(home, Ui.params(this, 14))
        val hint = Ui.note(this, "ⓘ " + Help.HINT)
        hint.textSize = 12f
        home.addView(hint, Ui.params(this, 10))

        sectionBar = LinearLayout(this)
        sectionBar.orientation = LinearLayout.HORIZONTAL
        sectionBar.gravity = android.view.Gravity.CENTER_VERTICAL
        val back = Ui.button(this, "← Accueil")
        back.setOnClickListener { showTab(HOME) }
        sectionBar.addView(back, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        sectionTitle = Ui.heading(this, "")
        sectionTitle.textSize = 20f
        val tlp = LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
        tlp.leftMargin = Ui.dp(this, 14)
        sectionBar.addView(sectionTitle, tlp)
        content.addView(sectionBar, Ui.params(this, 14))

        val send = buildSendTab()
        val import = buildImportTab()
        val files = buildFilesTab()
        val account = buildAccountTab()
        val clean = buildCleanTab()
        val reports = buildReportsTab()
        tabViews = listOf(send, import, files, account, clean, reports)
        for (t in tabViews) content.addView(t, Ui.params(this, 14))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        progressBar.visibility = View.GONE
        content.addView(progressBar, Ui.params(this, 12))

        btnCancel = Ui.button(this, "Annuler")
        btnCancel.visibility = View.GONE
        btnCancel.setOnClickListener {
            cancelFlag = true
            log("Annulation demandée…")
        }
        content.addView(btnCancel, Ui.params(this, 8))

        val actions = Ui.button(this, "Voir les Actions")
        actions.setOnClickListener { lastRepoUrl?.let { openUrl("$it/actions") } }
        val repoButton = Ui.button(this, "Ouvrir le dépôt")
        repoButton.setOnClickListener { lastRepoUrl?.let { openUrl(it) } }
        resultRow = Ui.row(this, 12, actions, repoButton)
        resultRow.visibility = View.GONE
        content.addView(resultRow)

        logView = TextView(this)
        logView.setTextColor(Ui.TEXT)
        logView.textSize = 13f
        logView.typeface = android.graphics.Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        content.addView(logView, Ui.params(this, 14))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.isFillViewport = true
        scroll.addView(content)
        rootScroll = scroll
        setContentView(scroll)
    }

    /** Affiche une section ([HOME] = les tuiles). */
    private fun showTab(index: Int) {
        current = index
        home.visibility = if (index == HOME) View.VISIBLE else View.GONE
        sectionBar.visibility = if (index == HOME) View.GONE else View.VISIBLE
        sectionTitle.text = SECTIONS.getOrElse(index) { "" }
        for (i in tabViews.indices) tabViews[i].visibility = if (i == index) View.VISIBLE else View.GONE
        if (index == S_IMPORT && impRepoField.text.isEmpty()) impRepoField.setText(repoField.text)
        if (index == S_FILES) refreshArchives()
        if (index == S_CLEAN && cleanRepoField.text.isEmpty()) cleanRepoField.setText(repoField.text)
        if (index == S_REPORTS) refreshReports()
        if (index == HOME) refreshTiles()
        rootScroll.post { rootScroll.scrollTo(0, 0) }
        if (index == S_SEND) rootScroll.post { askGuess() }
    }

    // ------------------------------------------------------------------ accueil en tuiles

    private fun buildHome(): LinearLayout {
        val h = vertical()
        val tiles = listOf(
            tile("send", "🚀", "Envoyer", Ui.ACCENT) { showTab(S_SEND) },
            tile("apk", "📲", "APK", Color.parseColor("#5B9BFF")) { startFetch(null) },
            tile("files", "🗂️", "Historique", Color.parseColor("#B58CFF")) { showTab(S_FILES) },
            tile("reports", "🩺", "Rapports d'erreur", Ui.ERROR) { showTab(S_REPORTS) },
            tile("clean", "🧹", "Nettoyage GitHub", Ui.WARN) { showTab(S_CLEAN) },
            tile("import", "📥", "Importer pour Claude", Color.parseColor("#4FD1C5")) { showTab(S_IMPORT) },
            tile("browse", "🔍", "Explorer un zip", Ui.DIM) {
                startActivity(Intent(this, FilesActivity::class.java))
            },
            tile("account", "⚙️", "Compte", Ui.DIM) { showTab(S_ACCOUNT) },
            tile("tools", "🛠️", "Outils GitHub", Color.parseColor("#F78C6C")) {
                startActivity(Intent(this, GitHubToolsActivity::class.java))
            },
            tile("access", "🔐", "Accès et droits", Color.parseColor("#E879F9")) {
                startActivity(Intent(this, AccessActivity::class.java))
            }
        )
        for (i in tiles.indices step 2) {
            val row = if (i + 1 < tiles.size) Ui.row(this, if (i == 0) 0 else 8, tiles[i], tiles[i + 1])
            else Ui.row(this, 8, tiles[i], View(this))
            h.addView(row)
        }
        return h
    }

    private fun tile(key: String, emoji: String, title: String, color: Int, onClick: () -> Unit): LinearLayout {
        val t = vertical()
        t.background = Ui.rounded(this, Ui.SURFACE, 20)
        val p = Ui.dp(this, 14)
        t.setPadding(p, p, p, p)
        t.minimumHeight = Ui.dp(this, 118)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        Help.attach(t, title)

        val bar = View(this)
        bar.background = Ui.rounded(this, color, 2)
        t.addView(bar, LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 4)))

        val icon = TextView(this)
        icon.text = emoji
        icon.textSize = 26f
        t.addView(icon, Ui.params(this, 8))

        val name = TextView(this)
        name.text = title
        name.setTextColor(Ui.TEXT)
        name.textSize = 15f
        name.setTypeface(name.typeface, android.graphics.Typeface.BOLD)
        t.addView(name, Ui.params(this, 4))

        val sub = Ui.note(this)
        sub.textSize = 12f
        sub.maxLines = 2
        sub.ellipsize = android.text.TextUtils.TruncateAt.END
        t.addView(sub, Ui.params(this, 2))
        tileSubs[key] = sub
        return t
    }

    private fun setSub(key: String, text: String, color: Int = Ui.DIM) {
        val v = tileSubs[key] ?: return
        v.text = text
        v.setTextColor(color)
    }

    /** Met à jour les sous-titres des tuiles. */
    private fun refreshTiles() {
        if (tileSubs.isEmpty() || !::repoField.isInitialized) return
        val repo = repoField.text.toString().trim().substringAfter('/').ifEmpty { "aucun dépôt" }
        setSub("send", if (srcKind == Kind.NONE) "Aucune source · $repo" else "$srcName → $repo")
        setSub("apk", "Récupérer et installer · $repo")
        val acc = AccessStore(this)
        setSub("access", "${acc.rules().size} règle(s)" + (if (acc.whitelist) " · liste blanche" else "") + " · ${acc.tokens().size + 1} jeton(s)")
        val all = BuildArchive.list(this)
        val pending = all.count { it.state == BuildArchive.PENDING }
        val saved = all.count { it.isSaved }
        setSub(
            "files", "$saved version(s) OK" + (if (pending > 0) " · $pending en attente" else ""),
            if (pending > 0) Ui.WARN else Ui.DIM
        )
        val reports = Reports.list(this)
        setSub(
            "reports",
            if (reports.isEmpty()) "Aucun rapport" else "${reports.size} rapport(s) · dernier ${Downloads.ago(reports[0].lastModified())}",
            if (reports.isNotEmpty() && System.currentTimeMillis() - reports[0].lastModified() < 86_400_000L) Ui.ERROR else Ui.DIM
        )
        setSub("clean", "Compilations, artefacts, caches, branches")
        setSub("import", "Préparer un dépôt pour Claude")
        setSub("browse", "Parcourir, chercher, envoyer")
        setSub("account", if (settings.hasToken()) "Jeton enregistré" else "Jeton manquant", if (settings.hasToken()) Ui.DIM else Ui.ERROR)
    }

    private fun vertical(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun buildSendTab(): LinearLayout {
        val tab = vertical()

        // --- 1. Ce qu'on envoie
        val srcCard = Ui.card(this)
        srcCard.addView(Ui.heading(this, "1. Ce qu'on envoie"))

        val srcBox = vertical()
        srcBox.background = Ui.rounded(this, Ui.SURFACE_HI, 12)
        val boxPad = Ui.dp(this, 12)
        srcBox.setPadding(boxPad, boxPad, boxPad, boxPad)
        srcInfo = Ui.note(this, "Rien de choisi. Choisis un zip, un dossier ou des fichiers ci-dessous, ou partage-les vers cette appli.")
        srcBox.addView(srcInfo)
        srcCard.addView(srcBox, Ui.params(this, 10))

        stripSwitch = switchOf("Ignorer le dossier racine")
        stripSwitch.visibility = View.GONE
        srcCard.addView(stripSwitch, Ui.params(this, 10))

        btnBrowseSrc = Ui.button(this, "Voir le contenu du zip")
        btnBrowseSrc.visibility = View.GONE
        btnBrowseSrc.setOnClickListener {
            val u = srcUris.firstOrNull()
            if (srcKind == Kind.ZIP && u != null) {
                // un lien file:// ne peut pas passer dans un Intent : on donne directement le chemin
                if (u.scheme == "file" && u.path != null) FilesActivity.open(this, File(u.path!!), srcName)
                else FilesActivity.open(this, u)
            }
        }
        srcCard.addView(btnBrowseSrc, Ui.params(this, 10))

        srcCard.addView(Ui.note(this, "Choisir une autre source :"), Ui.params(this, 14))
        val bZip = chooser("Zip", "Un zip") { chooseZip() }
        val bFolder = chooser("Dossier", "Un dossier") { chooseFolder() }
        val bFiles = chooser("Fichiers", "Des fichiers") { chooseFiles() }
        srcCard.addView(Ui.row(this, 6, bZip, bFolder, bFiles))

        btnLatest = Ui.button(this, "Dernier zip téléchargé")
        btnLatest.visibility = View.GONE
        btnLatest.setOnClickListener {
            val f = latestFile
            if (f != null) {
                autoKey = f.absolutePath + f.lastModified()
                selectSource(Kind.ZIP, listOf(Uri.fromFile(f)), f.name, true)
            }
        }
        Help.attach(btnLatest, "Dernier zip téléchargé")
        srcCard.addView(btnLatest, Ui.params(this, 8))
        accessNote = Ui.note(
            this,
            "Pour proposer automatiquement le dernier zip du dossier Téléchargements, autorise l'accès aux fichiers (une seule fois)."
        )
        accessNote.visibility = View.GONE
        srcCard.addView(accessNote, Ui.params(this, 8))
        btnAccess = Ui.button(this, "Autoriser l'accès aux fichiers")
        btnAccess.visibility = View.GONE
        btnAccess.setOnClickListener {
            try {
                startActivity(Downloads.accessIntent(this))
            } catch (e: Exception) {
                try {
                    startActivity(Downloads.accessFallbackIntent())
                } catch (e2: Exception) {
                    Toast.makeText(this, "Réglages > Applications > Envoi GitHub > Accès à tous les fichiers", Toast.LENGTH_LONG).show()
                }
            }
        }
        srcCard.addView(btnAccess, Ui.params(this, 8))
        tab.addView(srcCard)

        // --- 2. Où on l'envoie
        val repoCard = Ui.card(this)
        repoCard.addView(Ui.heading(this, "2. Où on l'envoie"))
        destStatus = Ui.note(this)
        destStatus.textSize = 14f
        destStatus.visibility = View.GONE
        repoCard.addView(destStatus, Ui.params(this, 6))
        repoField = Ui.combo(
            this, "pseudo/depot ou adresse du dépôt", { Suggest.repos(this) }, { Suggest.remoteRepos(this) },
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        repoField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // une saisie ou un choix dans la liste (champ actif) vaut confirmation ; un remplissage par le code, non
                if (repoField.hasFocus()) repoConfirmed = true
                updateDestStatus()
                refreshRecap()
            }
        })
        repoCard.addView(repoField, Ui.params(this, 10))

        btnRepos = Ui.button(this, "Mes dépôts")
        btnRepos.setOnClickListener { showRepoDialog { e -> pickRepo(e) } }
        btnTest = Ui.button(this, "Tester l'accès")
        btnTest.setOnClickListener { startTest() }
        Help.attach(btnTest, "Tester la connexion")
        repoCard.addView(Ui.row(this, 8, btnRepos, btnTest))

        branchField = Ui.combo(
            this, "Branche (vide = branche par défaut)",
            { Suggest.branches(this, repoField.text.toString()) }, { Suggest.remoteBranches(this, repoField.text.toString()) }
        )
        branchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshRecap()
            }
        })
        repoCard.addView(branchField, Ui.params(this, 8))

        val createBox = vertical()
        createBox.visibility = View.GONE
        createBox.addView(
            Ui.note(this, "Crée sur GitHub le dépôt écrit dans le champ ci-dessus, s'il n'existe pas encore."),
            Ui.params(this, 8)
        )
        privateSwitch = switchOf("Dépôt privé (pour la création)")
        privateSwitch.isChecked = true
        createBox.addView(privateSwitch, Ui.params(this, 10))
        btnCreate = Ui.button(this, "Créer le dépôt")
        btnCreate.setOnClickListener { startCreate() }
        createBox.addView(btnCreate, Ui.params(this, 8))
        repoCard.addView(foldButton("Créer un nouveau dépôt", createBox), Ui.params(this, 10))
        repoCard.addView(createBox)
        tab.addView(repoCard, Ui.params(this, 14))

        // --- 3. Options
        val optCard = Ui.card(this)
        optCard.addView(Ui.heading(this, "3. Options"))
        deleteSwitch = switchOf("Supprimer du dépôt les fichiers absents de l'envoi")
        deleteSwitch.setOnCheckedChangeListener { _, _ -> refreshRecap() }
        optCard.addView(deleteSwitch, Ui.params(this, 10))
        optCard.addView(
            Ui.note(this, "Désactivé : les fichiers du dépôt qui ne sont pas dans l'envoi sont conservés."),
            Ui.params(this, 2)
        )
        val moreBox = vertical()
        moreBox.visibility = View.GONE
        messageField = Ui.field(this, "Message du commit (facultatif)")
        moreBox.addView(messageField, Ui.params(this, 8))
        targetField = Ui.field(this, "Sous-dossier du dépôt (facultatif)")
        moreBox.addView(targetField, Ui.params(this, 8))
        cleanSwitch = switchOf("Supprimer du dépôt les fichiers inutiles (build, .gradle, .idea, local.properties…)")
        cleanSwitch.isChecked = settings.cleanJunk
        cleanSwitch.setOnCheckedChangeListener { _, checked -> settings.cleanJunk = checked }
        moreBox.addView(cleanSwitch, Ui.params(this, 10))
        ignoreSwitch = switchOf("Ignorer les dossiers générés (build, .gradle, node_modules…)")
        ignoreSwitch.setOnCheckedChangeListener { _, checked ->
            settings.ignoreGenerated = checked
            if (srcKind != Kind.NONE && !running) analyze()
        }
        moreBox.addView(ignoreSwitch, Ui.params(this, 4))
        workflowSwitch = switchOf("Ajouter un workflow de compilation si c'est un projet Android sans workflow")
        workflowSwitch.setOnCheckedChangeListener { _, checked -> settings.autoWorkflow = checked }
        moreBox.addView(workflowSwitch, Ui.params(this, 4))
        autoVersionSwitch = switchOf("Incrémenter versionCode automatiquement (app/build.gradle)")
        autoVersionSwitch.isChecked = settings.autoVersion
        autoVersionSwitch.setOnCheckedChangeListener { _, checked -> settings.autoVersion = checked }
        moreBox.addView(autoVersionSwitch, Ui.params(this, 4))
        optCard.addView(foldButton("Plus d'options", moreBox), Ui.params(this, 12))
        optCard.addView(moreBox)
        tab.addView(optCard, Ui.params(this, 14))

        // --- 4. Envoi
        val goCard = Ui.card(this)
        goCard.addView(Ui.heading(this, "4. Envoyer"))
        recapNote = Ui.note(this, "Choisis d'abord ce qu'il faut envoyer.")
        recapNote.textSize = 14f
        goCard.addView(recapNote, Ui.params(this, 8))
        btnPush = Ui.button(this, "Envoyer sur GitHub", primary = true)
        btnPush.setOnClickListener { startPush(false) }
        goCard.addView(btnPush, Ui.params(this, 12))
        tab.addView(goCard, Ui.params(this, 14))

        // --- APK
        val apkCard = Ui.card(this)
        apkCard.addView(Ui.heading(this, "Après l'envoi : application (APK)"))
        apkCard.addView(
            Ui.note(this, "Attend la fin de la compilation GitHub, télécharge l'APK, l'extrait et lance son installation."),
            Ui.params(this, 6)
        )
        btnApk = Ui.button(this, "Récupérer et installer l'APK")
        btnApk.setOnClickListener { startFetch(null) }
        apkCard.addView(btnApk, Ui.params(this, 10))
        autoApkSwitch = switchOf("Le faire automatiquement après chaque envoi")
        autoApkSwitch.setOnCheckedChangeListener { _, checked -> settings.autoApk = checked }
        apkCard.addView(autoApkSwitch, Ui.params(this, 10))
        tab.addView(apkCard, Ui.params(this, 14))
        return tab
    }

    /** Bouton de choix de la source : une seule ligne, pour que les trois tiennent côte à côte. */
    private fun chooser(label: String, helpKey: String, action: () -> Unit): TextView {
        val b = Ui.button(this, label)
        b.textSize = 14f
        b.setSingleLine(true)
        val p = Ui.dp(this, 4)
        b.setPadding(p, 0, p, 0)
        b.setOnClickListener { action() }
        Help.attach(b, helpKey)
        return b
    }

    private fun buildImportTab(): LinearLayout {
        val tab = vertical()
        val card = Ui.card(this)
        card.addView(Ui.heading(this, "Importer un dépôt pour Claude"))
        card.addView(
            Ui.note(
                this,
                "Télécharge un dépôt GitHub et le met en forme dans un fichier Markdown (arborescence + code) " +
                    "à joindre à une conversation Claude. Un gros dépôt est découpé en plusieurs parties."
            ),
            Ui.params(this, 6)
        )
        impRepoField = Ui.combo(
            this, "pseudo/depot ou adresse du dépôt", { Suggest.repos(this) }, { Suggest.remoteRepos(this) },
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        card.addView(impRepoField, Ui.params(this, 10))
        btnImpRepos = Ui.button(this, "Mes dépôts")
        btnImpRepos.setOnClickListener {
            showRepoDialog { e ->
                impRepoField.setText(e.repo)
                impBranchField.setText(e.branch)
            }
        }
        card.addView(btnImpRepos, Ui.params(this, 8))
        impBranchField = Ui.combo(
            this, "Branche (vide = branche par défaut)",
            { Suggest.branches(this, impRepoField.text.toString()) }, { Suggest.remoteBranches(this, impRepoField.text.toString()) }
        )
        card.addView(impBranchField, Ui.params(this, 8))
        btnPrepare = Ui.button(this, "Préparer pour Claude", primary = true)
        btnPrepare.setOnClickListener { startImport() }
        card.addView(btnPrepare, Ui.params(this, 12))
        tab.addView(card)

        impResult = Ui.card(this)
        impResult.visibility = View.GONE
        impInfo = Ui.note(this)
        impResult.addView(impInfo)
        val share = Ui.button(this, "Envoyer à Claude…", primary = true)
        share.setOnClickListener {
            lastDigest?.let { d ->
                val text = if (d.parts.size == 1 && d.chars <= ClaudeBundle.CLIP_MAX) {
                    try {
                        d.parts[0].readText(Charsets.UTF_8)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                val r = RepoRef.parse(impRepoField.text.toString())?.let { "${it.first}/${it.second}" }
                ClaudeSender.show(this, ClaudePayload(d.parts, text, "dépôt complet", r))
            }
        }
        impResult.addView(share, Ui.params(this, 10))
        btnCopy = Ui.button(this, "Copier le texte")
        btnCopy.setOnClickListener { copyDigest() }
        impResult.addView(btnCopy, Ui.params(this, 8))
        val shareZip = Ui.button(this, "Partager le zip du dépôt")
        shareZip.setOnClickListener {
            lastDigest?.let { shareFiles(listOf(it.zip), "application/zip", "Envoyer le zip") }
        }
        impResult.addView(shareZip, Ui.params(this, 8))
        impResult.addView(
            Ui.note(
                this,
                "« Envoyer à Claude » : nouvelle conversation, conversation existante, projet ou connaissances d'un projet. " +
                    "Le zip sert surtout si la conversation peut exécuter du code."
            ),
            Ui.params(this, 10)
        )
        tab.addView(impResult, Ui.params(this, 14))
        return tab
    }

    private fun buildAccountTab(): LinearLayout {
        val tab = vertical()

        val tokenCard = Ui.card(this)
        tokenCard.addView(Ui.heading(this, "Jeton GitHub"))
        tokenField = Ui.field(
            this, "Colle ton jeton ici (ghp_…)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        tokenField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshTokenInfo()
            }
        })
        tokenCard.addView(tokenField, Ui.params(this, 10))
        tokenInfo = Ui.note(this)
        tokenCard.addView(tokenInfo, Ui.params(this, 8))

        val paste = Ui.button(this, "Coller")
        paste.setOnClickListener { pasteToken() }
        showToken = Ui.button(this, "Afficher")
        showToken.setOnClickListener { toggleTokenVisibility() }
        tokenCard.addView(Ui.row(this, 10, paste, showToken))

        val create = Ui.button(this, "Créer un jeton")
        create.setOnClickListener {
            openUrl(GitHubTools.NEW_TOKEN_URL)
        }
        val forget = Ui.button(this, "Effacer le jeton")
        forget.setOnClickListener {
            settings.clearToken()
            tokenSaved = false
            tokenField.setText("")
            refreshTokenInfo()
        }
        tokenCard.addView(Ui.row(this, 6, create, forget))
        val tokenHelp = Ui.note(this, GitHubTools.TOKEN_HELP)
        tokenHelp.visibility = View.GONE
        val helpBtn = Ui.button(this, "Explications")
        helpBtn.setOnClickListener {
            tokenHelp.visibility = if (tokenHelp.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        val toolsBtn = Ui.button(this, "Vérifier / Outils")
        toolsBtn.setOnClickListener {
            resolveToken()
            refreshTokenInfo()
            startActivity(Intent(this, GitHubToolsActivity::class.java))
        }
        tokenCard.addView(Ui.row(this, 6, helpBtn, toolsBtn))
        tokenCard.addView(tokenHelp, Ui.params(this, 8))
        tab.addView(tokenCard)

        val repoCard = Ui.card(this)
        repoCard.addView(Ui.heading(this, "Mes dépôts"))
        repoCard.addView(
            Ui.note(this, "Les dépôts utilisés sont mémorisés. Tu peux aussi charger la liste de ton compte GitHub."),
            Ui.params(this, 6)
        )
        btnLoadRepos = Ui.button(this, "Charger depuis GitHub")
        btnLoadRepos.setOnClickListener { loadReposFromGithub(null) }
        repoCard.addView(btnLoadRepos, Ui.params(this, 10))
        val manage = Ui.button(this, "Oublier un dépôt…")
        manage.setOnClickListener { showForgetDialog() }
        repoCard.addView(manage, Ui.params(this, 8))
        tab.addView(repoCard, Ui.params(this, 14))

        val claudeCard = Ui.card(this)
        claudeCard.addView(Ui.heading(this, "Destinations Claude"))
        claudeCard.addView(
            Ui.note(
                this,
                "Mémorise les liens de tes conversations et projets Claude (https://claude.ai/chat/… ou /project/…) " +
                    "pour y envoyer des fichiers en deux gestes."
            ),
            Ui.params(this, 6)
        )
        val manageDests = Ui.button(this, "Gérer les destinations")
        manageDests.setOnClickListener { ClaudeSender.manage(this) }
        claudeCard.addView(manageDests, Ui.params(this, 10))
        tab.addView(claudeCard, Ui.params(this, 14))

        val widgetCard = Ui.card(this)
        widgetCard.addView(Ui.heading(this, "Widget de suivi"))
        widgetCard.addView(
            Ui.note(
                this,
                "Ajoute le widget « Envoi GitHub » à ton écran d'accueil (appui long sur l'écran > Widgets) " +
                    "pour suivre l'avancement des envois, imports et récupérations d'APK. " +
                    "Une notification de progression s'affiche aussi pendant les opérations longues."
            ),
            Ui.params(this, 6)
        )
        tab.addView(widgetCard, Ui.params(this, 14))
        return tab
    }

    /** Bouton qui affiche/masque [panel]. */
    private fun foldButton(label: String, panel: View): TextView {
        val b = Ui.button(this, "$label  ▾")
        b.setOnClickListener {
            val open = panel.visibility != View.VISIBLE
            panel.visibility = if (open) View.VISIBLE else View.GONE
            b.text = "$label  " + if (open) "▴" else "▾"
        }
        return b
    }

    private fun switchOf(text: String): Switch {
        val s = Switch(this)
        s.text = text
        s.setTextColor(Ui.TEXT)
        s.textSize = 14f
        Help.attach(s)
        return s
    }

    // ------------------------------------------------------------------ jeton

    private fun refreshTokenInfo() {
        val typed = Settings.cleanToken(tokenField.text.toString())
        if (typed.isEmpty()) {
            if (tokenSaved) {
                tokenInfo.text = "✓ Jeton enregistré (chiffré sur ce téléphone). Laisse vide pour le conserver."
                tokenInfo.setTextColor(Ui.ACCENT)
            } else {
                tokenInfo.text = "Aucun jeton enregistré."
                tokenInfo.setTextColor(Ui.DIM)
            }
            return
        }
        val looksRight = (typed.startsWith("ghp_") && typed.length == 40) || typed.startsWith("github_pat_")
        if (looksRight) {
            tokenInfo.text = "Jeton saisi : ${typed.length} caractères ✓"
            tokenInfo.setTextColor(Ui.ACCENT)
        } else {
            tokenInfo.text = "Jeton saisi : ${typed.length} caractères. Un jeton classique en compte 40 et commence par ghp_."
            tokenInfo.setTextColor(Ui.WARN)
        }
    }

    private fun pasteToken() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }
        if (text.isEmpty()) {
            Toast.makeText(this, "Le presse-papiers est vide.", Toast.LENGTH_SHORT).show()
            return
        }
        tokenField.setText(Settings.cleanToken(text))
    }

    private fun toggleTokenVisibility() {
        tokenVisible = !tokenVisible
        val variation = if (tokenVisible) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        tokenField.inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        tokenField.setSelection(tokenField.text.length)
        showToken.text = if (tokenVisible) "Masquer" else "Afficher"
    }

    /** Jeton saisi (et enregistré) ou, à défaut, jeton déjà enregistré. */
    private fun resolveToken(): String? {
        val typed = Settings.cleanToken(tokenField.text.toString())
        if (typed.isNotEmpty()) {
            try {
                settings.saveToken(typed)
            } catch (e: Exception) {
                log("✗ Impossible d'enregistrer le jeton de façon sécurisée : ${e.message}")
                return null
            }
            tokenSaved = true
            tokenField.setText("")
            return typed
        }
        return settings.token()
    }

    // ------------------------------------------------------------------ dépôts mémorisés

    private fun showRepoDialog(onPick: (RepoEntry) -> Unit) {
        val list = settings.repos()
        val builder = AlertDialog.Builder(this).setTitle("Mes dépôts")
        if (list.isEmpty()) {
            builder.setMessage("Aucun dépôt mémorisé. Un dépôt est mémorisé après un envoi réussi, ou tu peux charger ceux de ton compte.")
        } else {
            val labels = list.map { it.repo + (if (it.branch.isNotEmpty()) "  (${it.branch})" else "") }.toTypedArray()
            builder.setItems(labels) { _, i -> onPick(list[i]) }
        }
        builder.setNeutralButton("Charger depuis GitHub") { _, _ ->
            loadReposFromGithub { showRepoDialog(onPick) }
        }
        builder.setNegativeButton("Fermer", null)
        builder.show()
    }

    private fun showForgetDialog() {
        val list = settings.repos()
        if (list.isEmpty()) {
            Toast.makeText(this, "Aucun dépôt mémorisé.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { it.repo }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Oublier quel dépôt ?")
            .setItems(labels) { _, i ->
                settings.forgetRepo(list[i].repo)
                Toast.makeText(this, "« ${list[i].repo} » oublié (il reste sur GitHub).", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun loadReposFromGithub(after: (() -> Unit)?) {
        if (running) return
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        refreshTokenInfo()
        setRunning(true)
        logView.text = ""
        log("Chargement de la liste des dépôts…")
        Thread {
            try {
                val names = Pusher(GitHubApi(token), { }, { _, _ -> }).listRepos()
                val added = settings.addKnownRepos(names)
                postLog("✓ ${names.size} dépôt(s) trouvé(s), $added ajouté(s) à la liste.")
                runOnUiThread { after?.invoke() }
            } catch (e: PushException) {
                postLog("✗ " + e.message)
            } catch (e: Exception) {
                postLog("✗ Erreur inattendue : ${e.javaClass.simpleName} ${e.message ?: ""}")
            } finally {
                runOnUiThread { setRunning(false) }
            }
        }.start()
    }

    // ------------------------------------------------------------------ source (zip, dossier, fichiers)

    private fun chooseZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
        )
        startActivityForResult(intent, REQUEST_ZIP)
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_TREE)
    }

    private fun chooseFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, REQUEST_FILES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_ZIP -> data.data?.let {
                selectSource(Kind.ZIP, listOf(it), FilesSource.displayName(this, it), false)
            }
            REQUEST_TREE -> data.data?.let {
                selectSource(Kind.TREE, listOf(it), TreeSource.folderName(this, it), false)
            }
            REQUEST_FILES -> {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                } else {
                    data.data?.let { uris.add(it) }
                }
                if (uris.isNotEmpty()) selectFiles(uris)
            }
        }
    }

    private fun selectFiles(uris: List<Uri>) {
        val first = FilesSource.displayName(this, uris[0])
        if (uris.size == 1 && first.lowercase().endsWith(".zip")) {
            selectSource(Kind.ZIP, uris, first, false)
        } else {
            selectSource(Kind.FILES, uris, if (uris.size == 1) first else "${uris.size} fichiers", false)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val act = intent.getStringExtra(Notifier.EXTRA_ACTION)
        if (act != null) {
            intent.removeExtra(Notifier.EXTRA_ACTION)
            handleNotifAction(act, intent)
            return
        }
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { selectFiles(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list != null && list.isNotEmpty()) selectFiles(list)
            }
            Intent.ACTION_VIEW -> intent.data?.let { selectFiles(listOf(it)) }
        }
    }

    /** Actions des notifications de fin de compilation. */
    private fun handleNotifAction(act: String, intent: Intent) {
        when (act) {
            Notifier.ACT_APK -> {
                val repo = intent.getStringExtra(Notifier.EXTRA_REPO).orEmpty()
                if (repo.isNotEmpty()) repoField.setText(repo)
                showTab(S_SEND)
                startFetch(intent.getStringExtra(Notifier.EXTRA_SHA), intent.getStringExtra(Notifier.EXTRA_BRANCH))
            }
            Notifier.ACT_REPORT -> {
                showTab(S_REPORTS)
                val f = File(intent.getStringExtra(Notifier.EXTRA_FILE).orEmpty())
                if (f.isFile) sendReport(f, ErrorReport.Level.SHORT)
            }
            Notifier.ACT_RERUN -> {
                val repo = intent.getStringExtra(Notifier.EXTRA_REPO).orEmpty()
                val run = intent.getLongExtra(Notifier.EXTRA_RUN, 0L)
                if (repo.isNotEmpty() && run > 0) rerun(repo, run)
            }
            Notifier.ACT_REPORTS -> showTab(S_REPORTS)
            Notifier.ACT_FILES -> showTab(S_FILES)
        }
    }

    private fun selectSource(kind: Kind, uris: List<Uri>, name: String, auto: Boolean) {
        lockRepo = false
        // nouvelle source : le dépôt doit être confirmé à nouveau
        repoConfirmed = false
        pendingGuess = null
        guessMissing = false
        guessDialog?.dismiss()
        lastCheck = null
        srcKind = kind
        srcUris = uris
        srcName = name
        srcAuto = auto
        if (!auto) {
            autoKey = autoKey ?: ""
            showTab(S_SEND)
        }
        updateDestStatus()
        refreshRecap()
        analyze()
        refreshTiles()
    }

    private fun makeSource(): FileSource {
        val uris = srcUris
        val base: FileSource = when (srcKind) {
            Kind.ZIP -> {
                val uri = uris[0]
                ZipSource {
                    contentResolver.openInputStream(uri) ?: throw PushException("Impossible d'ouvrir le zip.")
                }
            }
            Kind.TREE -> TreeSource(this, uris[0], ignoreSwitch.isChecked)
            Kind.FILES -> FilesSource(this, uris)
            Kind.NONE -> throw PushException("Choisis d'abord ce qu'il faut envoyer.")
        }
        return if (ignoreSwitch.isChecked && srcKind != Kind.TREE) FilteredSource(base) else base
    }

    /** Analyse la source choisie (nombre de fichiers, dossier racine) puis devine le dépôt. */
    private fun analyze() {
        val version = ++scanVersion
        val name = srcName
        srcInfo.text = "$name : analyse…"
        srcInfo.setTextColor(Ui.DIM)
        stripSwitch.visibility = View.GONE
        stripSwitch.isChecked = false
        btnBrowseSrc.visibility = if (srcKind == Kind.ZIP) View.VISIBLE else View.GONE
        val source = try {
            makeSource()
        } catch (e: PushException) {
            srcInfo.text = e.message
            srcInfo.setTextColor(Ui.ERROR)
            return
        }
        val token = settings.token()

        Thread {
            try {
                val scan = source.scan()
                runOnUiThread {
                    if (version != scanVersion) return@runOnUiThread
                    if (scan.files.isEmpty()) {
                        srcInfo.text = "$name : aucun fichier utilisable."
                        srcInfo.setTextColor(Ui.ERROR)
                        return@runOnUiThread
                    }
                    val kb = scan.files.sumOf { it.size } / 1024
                    val kind = when (srcKind) {
                        Kind.ZIP -> if (srcAuto) "zip, dernier téléchargé" else "zip"
                        Kind.TREE -> "dossier"
                        else -> "fichiers"
                    }
                    val root = scan.root
                    val cutRoot = if (root != null && !ZipTools.isProjectFolder(root)) "$root/" else ""
                    val rel = scan.files.map { it.name.removePrefix(cutRoot) }
                    val folders = rel.groupingBy { if (it.contains('/')) it.substringBefore('/') + "/" else "(racine)" }.eachCount()
                        .entries.sortedByDescending { it.value }
                    val info = StringBuilder()
                    info.append("📦 $name ($kind)\n")
                    info.append("• ${scan.files.size} fichier(s) · $kb Ko")
                    if (scan.skipped > 0) info.append(" · ${scan.skipped} ignoré(s)")
                    info.append('\n')
                    if (cutRoot.isNotEmpty()) info.append("• Dossier englobant : $root/\n")
                    info.append("• Contenu : ").append(folders.take(6).joinToString(" · ") { "${it.key} ${it.value}" })
                    if (folders.size > 6) info.append(" · +${folders.size - 6}")
                    srcInfo.text = info.toString()
                    srcInfo.setTextColor(Ui.ACCENT)
                    if (root != null) {
                        stripSwitch.text = "Ignorer le dossier racine « $root »"
                        stripSwitch.isChecked = srcKind == Kind.ZIP && !ZipTools.isProjectFolder(root)
                        stripSwitch.visibility = View.VISIBLE
                    }
                    scanKeys = listOf(RepoGuess.key(name), RepoGuess.key(root ?: ""))
                    if (srcKind != Kind.FILES) {
                        val cut = if (root != null && !ZipTools.isProjectFolder(root)) "$root/" else ""
                        val check = ZipCheck.check(scan.files.map { it.name.removePrefix(cut) }, workflowSwitch.isChecked)
                        lastCheck = check
                        if (check.android) {
                            srcInfo.append(if (check.complete) "\n• Projet Android complet ✓" else "\n• Projet Android INCOMPLET")
                        }
                        if (check.warnings.isNotEmpty()) {
                            srcInfo.append("\n⚠ " + check.warnings.joinToString("\n⚠ "))
                            srcInfo.setTextColor(Ui.WARN)
                        }
                    }
                }
                guessRepo(scan, name, version, token)
            } catch (e: PushException) {
                runOnUiThread {
                    if (version == scanVersion) {
                        srcInfo.text = "$name : ${e.message}"
                        srcInfo.setTextColor(Ui.ERROR)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (version == scanVersion) {
                        srcInfo.text = "$name : lecture impossible (${e.javaClass.simpleName})"
                        srcInfo.setTextColor(Ui.ERROR)
                    }
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ dépôt deviné

    private fun guessRepo(scan: ScanResult, name: String, version: Int, token: String?) {
        val keys = listOf(RepoGuess.key(name), RepoGuess.key(scan.root ?: ""))
        val known = settings.repos()
        var guess = RepoGuess.byName(keys, known) { settings.learnedRepo(it) }

        if ((guess == null || guess.score < 90) && token != null) {
            try {
                val pusher = Pusher(GitHubApi(token), { }, { _, _ -> })
                val names = LinkedHashSet<String>()
                for (e in known.take(8)) names.add(e.repo)
                if (names.size < 3) names.addAll(pusher.listRepos().take(8))
                val prefix = if (scan.root != null) scan.root + "/" else ""
                val paths = scan.files.map { it.name.removePrefix(prefix) }
                val trees = LinkedHashMap<String, Set<String>>()
                for (n in names.take(8)) {
                    val o = n.substringBefore('/')
                    val r = n.substringAfter('/')
                    pusher.treePaths(o, r)?.let { trees[n] = it }
                }
                val byTree = RepoGuess.byTree(paths, trees)
                if (byTree != null && (guess == null || byTree.score > guess.score)) guess = byTree
            } catch (e: Exception) {
                // la devinette est facultative : on garde le résultat par nom
            }
        }

        val g = guess
        runOnUiThread {
            if (version != scanVersion) return@runOnUiThread
            // restauration, ou dépôt déjà validé pour cette source (simple nouvelle analyse) : rien à demander
            if (lockRepo || repoConfirmed) {
                updateDestStatus()
                refreshRecap()
                return@runOnUiThread
            }
            if (g != null && g.score >= 65) {
                // on ne remplit rien tout de suite : une boîte de dialogue demande confirmation
                pendingGuess = g
                guessMissing = false
                askGuess()
            } else {
                pendingGuess = null
                guessMissing = true
            }
            updateDestStatus()
            refreshRecap()
        }
    }

    /**
     * Demande « Le fichier X est-il bien lié au dépôt Y ? ». S'ouvre dès que l'écran Envoyer est affiché
     * (tout de suite si on y est déjà, sinon à son ouverture).
     */
    private fun askGuess() {
        val g = pendingGuess ?: return
        if (current != S_SEND || running || isFinishing || isDestroyed) return
        if (guessDialog?.isShowing == true) return
        pendingGuess = null
        val branchLine = if (g.branch.isNotEmpty()) "\nBranche : ${g.branch}" else ""
        guessDialog = AlertDialog.Builder(this)
            .setTitle("Quel dépôt ?")
            .setMessage(
                "Le fichier « $srcName » est-il bien lié au dépôt :\n\n${g.repo}$branchLine\n\n" +
                    "Pourquoi ce dépôt : ${g.reason}."
            )
            .setPositiveButton("Oui, c'est lui") { _, _ -> acceptGuess(g) }
            .setNegativeButton("Non, un autre") { _, _ -> showRepoDialog { e -> pickRepo(e) } }
            .setNeutralButton("Plus tard", null)
            .show()
    }

    private fun acceptGuess(g: Guess) {
        val sameRepo = repoField.text.toString().trim().equals(g.repo, ignoreCase = true)
        // un autre dépôt ne doit pas garder la branche de l'ancien
        if (g.branch.isNotEmpty() || !sameRepo) branchField.setText(g.branch)
        repoField.setText(g.repo)
        repoConfirmed = true
        guessMissing = false
        updateDestStatus()
        refreshRecap()
    }

    /** Dépôt choisi dans la liste « Mes dépôts » : c'est une confirmation. */
    private fun pickRepo(e: RepoEntry) {
        repoField.setText(e.repo)
        branchField.setText(e.branch)
        repoConfirmed = true
        guessMissing = false
        updateDestStatus()
        refreshRecap()
    }

    /** Ligne d'état sous « Où on l'envoie » : dit clairement si le dépôt est confirmé ou non. */
    private fun updateDestStatus() {
        if (!::destStatus.isInitialized || !::repoField.isInitialized) return
        val repo = repoField.text.toString().trim()
        val text: String
        val color: Int
        when {
            srcKind == Kind.NONE -> {
                destStatus.visibility = View.GONE
                return
            }
            lockRepo -> {
                text = "✓ Restauration vers ${repo.ifEmpty { "…" }}."
                color = Ui.ACCENT
            }
            repoConfirmed -> {
                text = "✓ Dépôt confirmé pour « $srcName »."
                color = Ui.ACCENT
            }
            guessMissing -> {
                text = "Aucun dépôt reconnu pour « $srcName » : choisis-le avec « Mes dépôts » ou écris-le."
                color = Ui.WARN
            }
            else -> {
                text = "⚠ À confirmer : « $srcName » va-t-il bien dans ${repo.ifEmpty { "ce dépôt" }} ?"
                color = Ui.WARN
            }
        }
        destStatus.text = text
        destStatus.setTextColor(color)
        destStatus.visibility = View.VISIBLE
    }

    /** Récapitulatif juste au-dessus du bouton d'envoi : quoi, vers où, et ce qui peut surprendre. */
    private fun refreshRecap() {
        if (!::recapNote.isInitialized || !::repoField.isInitialized) return
        if (srcKind == Kind.NONE) {
            recapNote.text = "Choisis d'abord ce qu'il faut envoyer."
            recapNote.setTextColor(Ui.DIM)
            return
        }
        val repo = repoField.text.toString().trim().ifEmpty { "(aucun dépôt)" }
        val branch = branchField.text.toString().trim().ifEmpty { "branche par défaut" }
        val sb = StringBuilder("📦 $srcName\n→ $repo · $branch")
        if (deleteSwitch.isChecked && srcKind != Kind.FILES) {
            sb.append("\n⚠ Les fichiers du dépôt absents de l'envoi seront supprimés.")
        }
        if (!repoConfirmed && !lockRepo) sb.append("\n⚠ Dépôt non confirmé.")
        recapNote.text = sb.toString()
        recapNote.setTextColor(if (repoConfirmed || lockRepo) Ui.TEXT else Ui.WARN)
    }

    // ------------------------------------------------------------------ dernier zip téléchargé

    private fun refreshLatest() {
        if (!Downloads.supported()) return
        if (!Downloads.hasAccess()) {
            accessNote.visibility = View.VISIBLE
            btnAccess.visibility = View.VISIBLE
            btnLatest.visibility = View.GONE
            return
        }
        accessNote.visibility = View.GONE
        btnAccess.visibility = View.GONE
        val f = Downloads.latestZip()
        latestFile = f
        if (f == null) {
            btnLatest.visibility = View.GONE
            return
        }
        btnLatest.visibility = View.VISIBLE
        btnLatest.text = "Dernier zip téléchargé : ${f.name} (${Downloads.ago(f.lastModified())})"
        val key = f.absolutePath + f.lastModified()
        if ((srcKind == Kind.NONE || srcAuto) && key != autoKey && !running) {
            autoKey = key
            selectSource(Kind.ZIP, listOf(Uri.fromFile(f)), f.name, true)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun saveFields() {
        settings.repo = repoField.text.toString()
        settings.branch = branchField.text.toString()
        settings.message = messageField.text.toString()
    }

    private fun startTest() {
        if (running) return
        val ref = RepoRef.parse(repoField.text.toString())
        if (ref == null) {
            fail("Dépôt invalide. Écris « pseudo/depot » ou colle l'adresse du dépôt.")
            return
        }
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        saveFields()
        refreshTokenInfo()
        val branch = branchField.text.toString()

        setRunning(true)
        logView.text = ""
        log("Test de la connexion…")
        Thread {
            try {
                val pusher = Pusher(GitHubApi(token), { line -> postLog(line) }, { _, _ -> })
                for (line in pusher.check(ref.first, ref.second, branch)) postLog(line)
                postLog("✓ Connexion OK")
                settings.rememberRepo(ref.first + "/" + ref.second, branch)
            } catch (e: PushException) {
                postLog("✗ " + e.message)
                reportFailure("Test de connexion", ref.first + "/" + ref.second, e, null)
            } catch (e: Exception) {
                postLog("✗ Erreur inattendue : ${e.javaClass.simpleName} ${e.message ?: ""}")
                reportFailure("Test de connexion", ref.first + "/" + ref.second, e, null)
            } finally {
                runOnUiThread { setRunning(false) }
            }
        }.start()
    }

    private fun startPush(force: Boolean) {
        if (running) return
        val partial = lastCheck?.complete != true
        if (!force && deleteSwitch.isChecked && srcKind != Kind.FILES && partial) {
            AlertDialog.Builder(this)
                .setTitle("Zip incomplet ?")
                .setMessage(
                    "« Supprimer du dépôt les fichiers absents » est activé, mais ce zip ne ressemble pas à un projet " +
                        "complet. Tout ce qui n'est pas dans le zip sera supprimé du dépôt. Continuer ?"
                )
                .setPositiveButton("Envoyer quand même") { _, _ -> startPush(true) }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        val ref = RepoRef.parse(repoField.text.toString())
        if (ref == null) {
            fail("Dépôt invalide. Écris « pseudo/depot » ou colle l'adresse du dépôt.")
            return
        }
        if (srcKind == Kind.NONE) {
            fail("Choisis d'abord un zip, un dossier ou des fichiers.")
            return
        }
        if (!repoConfirmed && !lockRepo) {
            val branch = branchField.text.toString().trim()
            AlertDialog.Builder(this)
                .setTitle("Confirmer le dépôt")
                .setMessage(
                    "Envoyer « $srcName » vers :\n\n${ref.first}/${ref.second}" +
                        (if (branch.isNotEmpty()) "\nBranche : $branch" else "") + "\n\nC'est bien le bon dépôt ?"
                )
                .setPositiveButton("Oui, envoyer") { _, _ ->
                    repoConfirmed = true
                    updateDestStatus()
                    refreshRecap()
                    startPush(force)
                }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        val source = try {
            makeSource()
        } catch (e: PushException) {
            fail(e.message ?: "Source invalide.")
            return
        }
        saveFields()
        refreshTokenInfo()

        val branchText = branchField.text.toString()
        val options = PushOptions(
            owner = ref.first,
            repo = ref.second,
            branch = branchText,
            message = messageField.text.toString(),
            stripRoot = stripSwitch.visibility == View.VISIBLE && stripSwitch.isChecked,
            deleteMissing = deleteSwitch.isChecked && srcKind != Kind.FILES,
            targetDir = targetField.text.toString(),
            autoWorkflow = workflowSwitch.isChecked && srcKind != Kind.FILES,
            cleanJunk = cleanSwitch.isChecked,
            autoPlace = srcKind == Kind.FILES && targetField.text.toString().isBlank()
        )
        val fullName = ref.first + "/" + ref.second
        val repoUrl = "https://github.com/$fullName"
        val keys = scanKeys
        val copyZipUri = if (srcKind == Kind.ZIP) srcUris.firstOrNull() else null
        val bump = autoVersionSwitch.isChecked && srcKind != Kind.FILES
        val copyName = srcName

        setRunning(true)
        logView.text = ""
        resultRow.visibility = View.GONE
        task = TaskHandle(this, "Envoi vers $fullName")

        Thread {
            var ok = false
            var summaryLine = ""
            try {
                val api = GitHubApi(token)
                api.onWait = { seconds -> postLog("Limite de débit GitHub : attente de $seconds s…") }
                val pusher = Pusher(api, { line -> postLog(line) }, { done, total ->
                    val percent = if (total == 0) 0 else done * 100 / total
                    task?.progress(percent, "$done / $total fichier(s)")
                    runOnUiThread { progressBar.progress = percent }
                })
                var src = source
                if (bump) {
                    try {
                        VersionBump.prepare(
                            api, source, ref.first, ref.second, branchText, options.stripRoot, options.targetDir
                        ) { postLog(it) }?.let { src = it }
                    } catch (e: Exception) {
                        postLog("versionCode non modifié : ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                val result = pusher.push(src, options)
                ok = true
                settings.rememberRepo(fullName, branchText)
                settings.learnZip(keys, fullName, branchText)
                val sha = result.commitSha
                if (sha != null) {
                    keepCopy(src, if (src === source) copyZipUri else null, copyZipUri, copyName, fullName, result.branch, sha, token)
                    if (copyZipUri != null) {
                        val moved = try {
                            SourceZip.moveToRepo(applicationContext, copyZipUri.toString(), copyName, -1, fullName)
                        } catch (e: Exception) {
                            null
                        }
                        if (moved != null) {
                            BuildArchive.setSource(applicationContext, fullName, sha, moved.toString())
                            postLog("Zip rangé : Téléchargements/${PublicFiles.ROOT}/${PublicFiles.repoDir(fullName)}/Zips.")
                            runOnUiThread { if (srcUris.firstOrNull() == copyZipUri) srcUris = listOf(moved) }
                        } else if (!Downloads.hasAccess()) {
                            postLog("Zip non rangé : autorise l'accès aux fichiers (Envoyer > Autoriser l'accès).")
                        }
                    }
                }

                runOnUiThread {
                    lastRepoUrl = repoUrl
                    resultRow.visibility = View.VISIBLE
                    if (result.commitSha == null) {
                        summaryLine = "Rien à envoyer : déjà à jour"
                        log("✓ Rien à envoyer : le dépôt est déjà à jour.")
                    } else {
                        val summary = StringBuilder("✓ Envoyé sur « ${result.branch} » : ${result.uploaded} fichier(s)")
                        if (result.deleted > 0) summary.append(", ${result.deleted} supprimé(s)")
                        summary.append(" (commit ${result.commitSha.take(7)})")
                        summaryLine = "Envoyé : ${result.uploaded} fichier(s)"
                        log(summary.toString())
                        if (result.workflowAdded) log("Un workflow de compilation Android a été ajouté au dépôt.")
                        log("La compilation démarre toute seule : page Actions du dépôt.")
                        if (autoApkSwitch.isChecked) {
                            pendingAutoSha = result.commitSha
                            pendingAutoBranch = result.branch
                        }
                    }
                }
            } catch (e: PushException) {
                postLog("✗ " + e.message)
                summaryLine = e.message ?: "Erreur"
                reportFailure("Envoi sur GitHub (branche « $branchText »)", fullName, e, null)
            } catch (e: Exception) {
                postLog("✗ Erreur inattendue : ${e.javaClass.simpleName} ${e.message ?: ""}")
                summaryLine = "Erreur inattendue"
                reportFailure("Envoi sur GitHub (branche « $branchText »)", fullName, e, null)
            } finally {
                val t = task
                task = null
                if (t != null) {
                    // laisse le temps au résumé (affiché dans le fil UI) d'être calculé
                    runOnUiThread {
                        if (ok) t.done(summaryLine) else t.fail(summaryLine)
                        setRunning(false)
                        val sha = pendingAutoSha
                        val branch = pendingAutoBranch
                        pendingAutoSha = null
                        pendingAutoBranch = null
                        if (sha != null) startFetch(sha, branch)
                    }
                }
            }
        }.start()
    }

    private fun startCreate() {
        if (running) return
        val ref = RepoRef.parse(repoField.text.toString())
        if (ref == null) {
            fail("Dépôt invalide. Écris « pseudo/depot » (le dépôt à créer).")
            return
        }
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        saveFields()
        refreshTokenInfo()
        val isPrivate = privateSwitch.isChecked

        setRunning(true)
        logView.text = ""
        resultRow.visibility = View.GONE
        log("Création du dépôt ${ref.first}/${ref.second}…")
        Thread {
            try {
                val pusher = Pusher(GitHubApi(token), { line -> postLog(line) }, { _, _ -> })
                val url = pusher.createRepo(ref.first, ref.second, isPrivate)
                settings.rememberRepo(ref.first + "/" + ref.second, "")
                runOnUiThread {
                    lastRepoUrl = url
                    resultRow.visibility = View.VISIBLE
                    log("✓ Dépôt créé (" + (if (isPrivate) "privé" else "public") + ") : $url")
                    log("Choisis ce qu'il faut envoyer, puis appuie sur « Envoyer sur GitHub ».")
                }
            } catch (e: PushException) {
                postLog("✗ " + e.message)
                reportFailure("Création du dépôt", ref.first + "/" + ref.second, e, null)
            } catch (e: Exception) {
                postLog("✗ Erreur inattendue : ${e.javaClass.simpleName} ${e.message ?: ""}")
                reportFailure("Création du dépôt", ref.first + "/" + ref.second, e, null)
            } finally {
                runOnUiThread { setRunning(false) }
            }
        }.start()
    }

    /**
     * Récupère l'APK de la dernière compilation (ou de celle du commit [expectedSha]), l'extrait
     * et ouvre l'installation d'Android.
     */
    private fun startFetch(expectedSha: String?, branchOverride: String? = null) {
        if (running) return
        val ref = RepoRef.parse(repoField.text.toString())
        if (ref == null) {
            fail("Dépôt invalide. Écris « pseudo/depot » ou colle l'adresse du dépôt.")
            return
        }
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        saveFields()
        refreshTokenInfo()

        if (!ApkInstaller.canInstall(this)) {
            fail(
                "Android doit d'abord t'autoriser à installer des applis depuis « Envoi GitHub ». " +
                    "Active l'autorisation sur l'écran qui s'ouvre, reviens ici, puis relance."
            )
            try {
                startActivity(ApkInstaller.permissionIntent(this))
            } catch (e: Exception) {
                log("Ouvre Réglages > Applications > Envoi GitHub > Installer des applis inconnues.")
            }
            return
        }

        val branch = branchOverride ?: branchField.text.toString()
        setRunning(true, cancellable = true)
        if (expectedSha == null) logView.text = ""
        log("Récupération de l'APK…")
        val handle = TaskHandle(this, "APK de ${ref.first}/${ref.second}")
        task = handle

        Thread {
            var ok = false
            var detail = ""
            val api = GitHubApi(token)
            try {
                api.onWait = { seconds -> postLog("Limite de débit GitHub : attente de $seconds s…") }
                val fetcher = ApkFetcher(
                    api,
                    File(cacheDir, "apk"),
                    { line -> postLog(line) },
                    { percent ->
                        handle.progress(percent, null)
                        runOnUiThread { progressBar.progress = percent }
                    },
                    { cancelFlag }
                )
                val result = fetcher.fetch(ref.first, ref.second, branch, expectedSha)
                if (result.sha.isNotEmpty()) {
                    val n = BuildArchive.onBuildSuccess(
                        applicationContext, ref.first + "/" + ref.second, result.sha, result.runUrl
                    ) { postLog(it) }
                    if (n > 0) postLog("✓ Compilation réussie : copie du zip sauvegardée (tuile Historique).")
                }
                val installed = ApkInstaller.alreadyInstalled(applicationContext, result.apk)
                if (installed != null) {
                    postLog("✓ Déjà à jour : version $installed installée. Rien à installer.")
                    ok = true
                    detail = "Déjà à jour ($installed)"
                } else {
                    postLog("APK prêt : ${result.apk.name}. Ouverture de l'installation…")
                    ApkInstaller.install(applicationContext, result.apk)
                    postLog("✓ Valide la fenêtre d'installation d'Android pour terminer.")
                    ok = true
                    detail = "APK prêt : installation proposée"
                }
            } catch (e: PushException) {
                postLog("✗ " + e.message)
                detail = e.message ?: "Erreur"
                reportFailure("Récupération de l'APK", ref.first + "/" + ref.second, e, api)
            } catch (e: Exception) {
                postLog("✗ Erreur inattendue : ${e.javaClass.simpleName} ${e.message ?: ""}")
                detail = "Erreur inattendue"
                reportFailure("Récupération de l'APK", ref.first + "/" + ref.second, e, api)
            } finally {
                task = null
                runOnUiThread {
                    if (ok) handle.done(detail) else handle.fail(detail)
                    setRunning(false)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ import pour Claude

    private fun startImport() {
        if (running) return
        val ref = RepoRef.parse(impRepoField.text.toString())
        if (ref == null) {
            fail("Dépôt invalide. Écris « pseudo/depot » ou colle l'adresse du dépôt.")
            return
        }
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        refreshTokenInfo()
        val branch = impBranchField.text.toString()
        val fullName = ref.first + "/" + ref.second

        setRunning(true, cancellable = true)
        logView.text = ""
        resultRow.visibility = View.GONE
        impResult.visibility = View.GONE
        val handle = TaskHandle(this, "Import de $fullName")
        task = handle

        Thread {
            var ok = false
            var detail = ""
            try {
                val api = GitHubApi(token)
                api.onWait = { seconds -> postLog("Limite de débit GitHub : attente de $seconds s…") }
                val digest = RepoDigest(
                    api,
                    { line -> postLog(line) },
                    { percent ->
                        handle.progress(percent, null)
                        runOnUiThread { progressBar.progress = percent }
                    },
                    { cancelFlag }
                ).build(ref.first, ref.second, branch, ShareProvider.dir(this))
                settings.addKnownRepos(listOf(fullName))
                ok = true
                detail = "${digest.parts.size} fichier(s) prêt(s)"
                runOnUiThread { showDigest(digest, fullName) }
            } catch (e: PushException) {
                postLog("✗ " + e.message)
                detail = e.message ?: "Erreur"
                reportFailure("Import pour Claude", fullName, e, null)
            } catch (e: Exception) {
                postLog("✗ Erreur inattendue : ${e.javaClass.simpleName} ${e.message ?: ""}")
                detail = "Erreur inattendue"
                reportFailure("Import pour Claude", fullName, e, null)
            } finally {
                task = null
                runOnUiThread {
                    if (ok) handle.done(detail) else handle.fail(detail)
                    setRunning(false)
                }
            }
        }.start()
    }

    private fun showDigest(d: DigestResult, name: String) {
        lastDigest = d
        val tokens = d.chars / 4
        val sb = StringBuilder()
        sb.append("✓ $name prêt pour Claude\n")
        sb.append("${d.included} fichier(s) inclus sur ${d.total}")
        if (d.omitted > 0) sb.append(" (${d.omitted} seulement listés : binaires, trop gros ou générés)")
        sb.append("\n${d.parts.size} fichier(s) Markdown, environ ${tokens / 1000} k jetons.")
        if (d.parts.size > 1) sb.append("\nJoins toutes les parties dans le même message.")
        if (tokens > 150_000) sb.append("\nAttention : très volumineux, il peut dépasser la capacité d'une conversation.")
        impInfo.text = sb.toString()
        impInfo.setTextColor(Ui.ACCENT)
        btnCopy.visibility = if (d.parts.size == 1 && d.chars <= 150_000) View.VISIBLE else View.GONE
        impResult.visibility = View.VISIBLE
        log("✓ Import terminé.")
    }

    private fun shareFiles(files: List<File>, mime: String, title: String) {
        if (files.isEmpty()) return
        val uris = ArrayList<Uri>()
        for (f in files) uris.add(ShareProvider.uriFor(f))
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newUri(contentResolver, "fichiers", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        try {
            startActivity(Intent.createChooser(intent, title))
        } catch (e: Exception) {
            Toast.makeText(this, "Partage impossible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyDigest() {
        val d = lastDigest ?: return
        try {
            val text = d.parts[0].readText(Charsets.UTF_8)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Dépôt pour Claude", text))
            Toast.makeText(this, "Texte copié : colle-le dans la conversation.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copie impossible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ état

    // ------------------------------------------------------------------ sauvegardes des compilations réussies

    /** Met de côté une copie de ce qui vient d'être envoyé ; elle est gardée si la compilation réussit. */
    private fun keepCopy(
        source: FileSource, zipUri: Uri?, origUri: Uri?, name: String, repo: String, branch: String, sha: String, token: String
    ) {
        try {
            val uri = zipUri
            val writer = if (uri != null) {
                BuildArchive.copyZip { contentResolver.openInputStream(uri) ?: throw PushException("zip illisible") }
            } else {
                BuildArchive.zipOf(source)
            }
            val entry = BuildArchive.createPending(
                applicationContext, repo, branch, sha, name, writer, origUri?.toString().orEmpty()
            )
            postLog("Copie du zip mise de côté : elle sera sauvegardée si la compilation réussit.")
            BuildWatcher.watch(applicationContext, token, entry) { line -> runOnUiThread { log(line) } }
        } catch (e: Exception) {
            postLog("Copie de sauvegarde impossible : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Vérifie sur GitHub les compilations des copies en attente (sauf celles déjà surveillées). */
    private fun checkPendingBuilds(silent: Boolean) {
        val pending = BuildArchive.pending(this).filter { !BuildWatcher.isWatching(it.sha) }
        if (pending.isEmpty()) {
            if (!silent) Toast.makeText(this, "Aucune compilation en attente.", Toast.LENGTH_SHORT).show()
            return
        }
        val token = settings.token()
        if (token == null) {
            if (!silent) fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        if (!silent) {
            logView.text = ""
            log("Vérification de ${pending.size} compilation(s)…")
        }
        Thread {
            val api = GitHubApi(token)
            for (e in pending) {
                try {
                    val c = BuildArchive.check(api, e)
                    val line = BuildArchive.apply(applicationContext, e, c)
                    if (!silent || !line.startsWith("…")) postLog(line)
                    BuildArchive.notifyResult(applicationContext, api, e, c) { postLog(it) }
                } catch (ex: Exception) {
                    if (!silent) postLog("✗ ${e.repo} : ${ex.message ?: ex.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun buildFilesTab(): LinearLayout {
        val tab = vertical()
        val card = Ui.card(this)
        card.addView(Ui.heading(this, "Versions compilées avec succès"))
        card.addView(
            Ui.note(
                this,
                "À chaque envoi, une copie du zip est mise de côté. Si la compilation GitHub de ce commit réussit, " +
                    "elle est gardée ici (et copiée dans Documents/Github/<dépôt>, 3 zips au plus) ; sinon elle est supprimée. " +
                    "Appuie sur une sauvegarde pour parcourir ses fichiers, chercher et envoyer une sélection à Claude."
            ),
            Ui.params(this, 6)
        )
        pendingInfo = Ui.note(this)
        card.addView(pendingInfo, Ui.params(this, 8))
        btnCheckBuilds = Ui.button(this, "Vérifier les compilations en attente")
        btnCheckBuilds.setOnClickListener { checkPendingBuilds(silent = false) }
        card.addView(btnCheckBuilds, Ui.params(this, 8))
        archiveList = vertical()
        card.addView(archiveList, Ui.params(this, 10))
        tab.addView(card)

        val other = Ui.card(this)
        other.addView(Ui.heading(this, "Autres zips"))
        val openZip = Ui.button(this, "Parcourir un zip du téléphone…")
        openZip.setOnClickListener { startActivity(android.content.Intent(this, FilesActivity::class.java)) }
        other.addView(openZip, Ui.params(this, 10))
        tab.addView(other, Ui.params(this, 14))

        val opts = Ui.card(this)
        opts.addView(Ui.heading(this, "Réglages des sauvegardes"))
        val pub = switchOf("Copier aussi chaque sauvegarde dans Téléchargements/Envoi GitHub/<dépôt>/Compilations réussies")
        pub.isChecked = settings.archivePublic
        pub.setOnCheckedChangeListener { _, c -> settings.archivePublic = c }
        opts.addView(pub, Ui.params(this, 10))
        val docs = switchOf(
            "Copier chaque version compilée avec succès dans Documents/Github/<dépôt> (les ${DocsArchive.KEEP} plus récentes gardées)"
        )
        docs.isChecked = settings.archiveDocs
        docs.setOnCheckedChangeListener { _, c -> settings.archiveDocs = c }
        opts.addView(docs, Ui.params(this, 10))
        val delZip = switchOf("Supprimer du téléphone le zip d'origine si sa compilation échoue")
        delZip.isChecked = settings.deleteFailedZip
        delZip.setOnCheckedChangeListener { _, c -> settings.deleteFailedZip = c }
        opts.addView(delZip, Ui.params(this, 10))
        keepButton = Ui.button(this, "")
        keepButton.setOnClickListener { chooseKeep() }
        opts.addView(keepButton, Ui.params(this, 10))
        refreshKeepLabel()
        tab.addView(opts, Ui.params(this, 14))
        return tab
    }

    private fun refreshKeepLabel() {
        val k = settings.archiveKeep
        keepButton.text = if (k <= 0) "Garder : toutes les sauvegardes" else "Garder : les $k plus récentes"
    }

    private fun chooseKeep() {
        val values = intArrayOf(10, 20, 30, 50, 100, 0)
        val labels = values.map { if (it == 0) "Toutes" else "$it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Nombre de sauvegardes gardées")
            .setItems(labels) { _, i ->
                settings.archiveKeep = values[i]
                BuildArchive.prune(this, values[i])
                refreshKeepLabel()
                refreshArchives()
            }
            .show()
    }

    private fun refreshArchives() {
        if (!::archiveList.isInitialized) return
        val all = BuildArchive.list(this)
        val pending = all.filter { it.state == BuildArchive.PENDING }
        val saved = all.filter { it.isSaved }
        pendingInfo.text = if (pending.isEmpty()) {
            "Aucune compilation en attente."
        } else {
            "En attente de compilation : " + pending.joinToString(", ") {
                "${it.repo.substringAfter('/')} ${it.sha.take(7)} (${Downloads.ago(it.createdAt)})"
            }
        }
        pendingInfo.setTextColor(if (pending.isEmpty()) Ui.DIM else Ui.WARN)
        archiveList.removeAllViews()
        if (saved.isEmpty()) {
            archiveList.addView(Ui.note(this, "Aucune sauvegarde pour l'instant."))
            return
        }
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
        for (e in saved) {
            val row = Ui.card(this)
            row.background = Ui.rounded(this, Ui.SURFACE_HI, 14)
            val t = TextView(this)
            t.setTextColor(Ui.TEXT)
            t.textSize = 15f
            t.text = "${e.repo}  ·  ${fmt.format(java.util.Date(e.createdAt))}"
            row.addView(t)
            val sub = Ui.note(
                this,
                "${e.source} · commit ${e.sha.take(7)}" + (if (e.branch.isNotEmpty()) " · ${e.branch}" else "") +
                    " · ${FilesActivity.formatSize(e.size)}" +
                    (if (e.publicPath.isNotEmpty()) "\nCopie : ${e.publicPath}" else "")
            )
            row.addView(sub)
            val browse = Ui.button(this, "Parcourir", primary = true)
            browse.setOnClickListener { FilesActivity.open(this, BuildArchive.fileOf(this, e), e.fileName) }
            val more = Ui.button(this, "Plus…")
            more.setOnClickListener { archiveMenu(e) }
            row.addView(Ui.row(this, 8, browse, more))
            archiveList.addView(row, Ui.params(this, 8))
        }
    }

    /** Prépare l'envoi d'une version de l'historique vers son dépôt (restauration). */
    private fun restore(e: ArchiveEntry) {
        val f = BuildArchive.fileOf(this, e)
        if (!f.isFile || running) return
        AlertDialog.Builder(this)
            .setTitle("Restaurer ${e.repo}")
            .setMessage(
                "Version du ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE).format(java.util.Date(e.createdAt))} " +
                    "(commit ${e.sha.take(7)}). Supprimer aussi du dépôt les fichiers ajoutés depuis ?"
            )
            .setPositiveButton("Oui, restauration exacte") { _, _ -> prepareRestore(e, f, true) }
            .setNeutralButton("Non, seulement remplacer") { _, _ -> prepareRestore(e, f, false) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun prepareRestore(e: ArchiveEntry, f: File, exact: Boolean) {
        selectSource(Kind.ZIP, listOf(Uri.fromFile(f)), e.fileName, false)
        lockRepo = true
        repoField.setText(e.repo)
        branchField.setText(e.branch)
        repoConfirmed = true
        updateDestStatus()
        refreshRecap()
        messageField.setText("Restauration de la version ${e.sha.take(7)}")
        deleteSwitch.isChecked = exact
        autoVersionSwitch.isChecked = true
        log("Restauration prête : vérifie puis appuie sur « Envoyer sur GitHub ».")
    }

    private fun archiveMenu(e: ArchiveEntry) {
        val items = arrayOf(
            "Partager le zip", "Copier dans Téléchargements", "Voir la compilation sur GitHub", "Supprimer cette sauvegarde",
            "Renvoyer cette version sur GitHub"
        )
        AlertDialog.Builder(this)
            .setTitle(e.fileName)
            .setItems(items) { _, i ->
                when (i) {
                    4 -> restore(e)
                    0 -> ClaudeSender.shareTo(this, listOf(BuildArchive.fileOf(this, e)), null, "application/zip")
                    1 -> Thread {
                        val path = BuildArchive.exportPublic(applicationContext, e)
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                if (path != null) "Copié : $path" else "Copie impossible sur ce téléphone (autorise l'accès aux fichiers).",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }.start()
                    2 -> openUrl(e.runUrl.ifEmpty { "https://github.com/${e.repo}/actions" })
                    3 -> AlertDialog.Builder(this)
                        .setMessage("Supprimer la sauvegarde ${e.fileName} ? (la copie éventuelle dans Téléchargements reste)")
                        .setPositiveButton("Supprimer") { _, _ -> BuildArchive.remove(this, e.id) }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    // ------------------------------------------------------------------ nettoyage GitHub

    private fun buildCleanTab(): LinearLayout {
        val tab = vertical()

        val repoCard = Ui.card(this)
        repoCard.addView(Ui.heading(this, "Dépôt"))
        cleanRepoField = Ui.combo(this, "pseudo/depot", { Suggest.repos(this) }, { Suggest.remoteRepos(this) })
        repoCard.addView(cleanRepoField, Ui.params(this, 10))
        val pick = Ui.button(this, "Mes dépôts")
        pick.setOnClickListener { showRepoDialog { cleanRepoField.setText(it.repo) } }
        repoCard.addView(pick, Ui.params(this, 8))
        cleanAllSwitch = switchOf("Tous les dépôts mémorisés")
        cleanAllSwitch.isChecked = settings.cleanAllRepos
        cleanAllSwitch.setOnCheckedChangeListener { _, c ->
            settings.cleanAllRepos = c
            cleanRepoField.isEnabled = !c
            cleanRepoField.alpha = if (c) 0.5f else 1f
        }
        cleanRepoField.isEnabled = !cleanAllSwitch.isChecked
        cleanRepoField.alpha = if (cleanAllSwitch.isChecked) 0.5f else 1f
        repoCard.addView(cleanAllSwitch, Ui.params(this, 10))
        tab.addView(repoCard)

        val what = Ui.card(this)
        what.addView(Ui.heading(this, "À supprimer"))
        val failed = switchOf("Compilations échouées ou annulées")
        failed.isChecked = settings.cleanFailedRuns
        failed.setOnCheckedChangeListener { _, c -> settings.cleanFailedRuns = c }
        what.addView(failed, Ui.params(this, 10))
        val old = switchOf("Anciennes compilations")
        old.isChecked = settings.cleanOldRuns
        old.setOnCheckedChangeListener { _, c -> settings.cleanOldRuns = c }
        what.addView(old, Ui.params(this, 10))
        cleanKeepButton = Ui.button(this, "")
        cleanKeepButton.setOnClickListener { chooseCleanKeep() }
        what.addView(cleanKeepButton, Ui.params(this, 8))
        refreshCleanKeep()
        val arts = switchOf("Artefacts (APK stockés par GitHub)")
        arts.isChecked = settings.cleanArtifacts
        arts.setOnCheckedChangeListener { _, c -> settings.cleanArtifacts = c }
        what.addView(arts, Ui.params(this, 10))
        val caches = switchOf("Caches Actions (Gradle)")
        caches.isChecked = settings.cleanCaches
        caches.setOnCheckedChangeListener { _, c -> settings.cleanCaches = c }
        what.addView(caches, Ui.params(this, 10))
        val branches = switchOf("Branches déjà fusionnées (hors branche par défaut et branches protégées)")
        branches.isChecked = settings.cleanBranches
        branches.setOnCheckedChangeListener { _, c -> settings.cleanBranches = c }
        what.addView(branches, Ui.params(this, 10))
        what.addView(
            Ui.note(this, "Le code du dépôt n'est jamais modifié. Analyse d'abord, puis confirme."),
            Ui.params(this, 10)
        )
        tab.addView(what, Ui.params(this, 14))

        val auto = switchOf("Nettoyer automatiquement chaque semaine (ces réglages)")
        auto.isChecked = settings.cleanAuto
        auto.setOnCheckedChangeListener { _, c ->
            settings.cleanAuto = c
            CleanJobService.schedule(this, c)
        }
        what.addView(auto, Ui.params(this, 10))

        btnCleanScan = Ui.button(this, "Analyser", primary = true)
        btnCleanScan.setOnClickListener { startCleanScan() }
        tab.addView(btnCleanScan, Ui.params(this, 14))
        cleanResult = Ui.note(this)
        tab.addView(cleanResult, Ui.params(this, 10))
        btnCleanRun = Ui.button(this, "Nettoyer")
        btnCleanRun.background = Ui.rounded(this, Ui.ERROR, 14)
        btnCleanRun.setTextColor(Ui.BG)
        btnCleanRun.visibility = View.GONE
        btnCleanRun.setOnClickListener { confirmClean() }
        tab.addView(btnCleanRun, Ui.params(this, 10))

        val danger = Ui.card(this)
        danger.addView(Ui.heading(this, "Supprimer un dépôt"))
        danger.addView(
            Ui.note(this, "Définitif. Le jeton doit avoir le droit « delete_repo ». Il faut retaper le nom du dépôt."),
            Ui.params(this, 6)
        )
        val del = Ui.button(this, "Supprimer un dépôt…")
        del.setTextColor(Ui.ERROR)
        del.setOnClickListener { confirmDeleteRepo() }
        danger.addView(del, Ui.params(this, 10))
        tab.addView(danger, Ui.params(this, 24))
        return tab
    }

    private fun confirmDeleteRepo() {
        if (running) return
        val input = Ui.field(this, "pseudo/depot")
        val box = vertical()
        val p = Ui.dp(this, 20)
        box.setPadding(p, Ui.dp(this, 8), p, 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Supprimer un dépôt GitHub")
            .setMessage("Retape le nom exact du dépôt à supprimer (pseudo/depot). Action définitive.")
            .setView(box)
            .setPositiveButton("Supprimer") { _, _ ->
                val ref = RepoRef.parse(input.text.toString())
                if (ref == null) fail("Nom de dépôt invalide.") else deleteRepo(ref.first + "/" + ref.second)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun deleteRepo(repo: String) {
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        setRunning(true)
        logView.text = ""
        log("Suppression de $repo…")
        Thread {
            try {
                val r = GitHubApi(token).call("DELETE", "/repos/$repo", null)
                when {
                    r.ok -> {
                        settings.forgetRepo(repo)
                        postLog("✓ Dépôt $repo supprimé.")
                    }
                    r.code == 403 -> postLog("✗ Refusé (403) : le jeton doit avoir le droit « delete_repo ».")
                    r.code == 404 -> postLog("✗ Dépôt introuvable (404).")
                    else -> postLog("✗ " + GitHubApi.explain(r, "Suppression").message)
                }
            } catch (e: Exception) {
                postLog("✗ " + (e.message ?: e.javaClass.simpleName))
            } finally {
                runOnUiThread { setRunning(false) }
            }
        }.start()
    }

    private fun refreshCleanKeep() {
        cleanKeepButton.text = "Garder les ${settings.cleanKeepRuns} compilations les plus récentes"
    }

    private fun chooseCleanKeep() {
        val values = intArrayOf(1, 3, 5, 10, 20, 50)
        AlertDialog.Builder(this)
            .setTitle("Compilations gardées par dépôt")
            .setItems(values.map { "$it" }.toTypedArray()) { _, i ->
                settings.cleanKeepRuns = values[i]
                refreshCleanKeep()
            }
            .show()
    }

    private fun cleanOptions() = GitHubCleaner.Options(
        failedRuns = settings.cleanFailedRuns,
        oldRuns = settings.cleanOldRuns,
        keepRuns = settings.cleanKeepRuns,
        artifacts = settings.cleanArtifacts,
        caches = settings.cleanCaches,
        branches = settings.cleanBranches
    )

    private fun cleanTargets(): List<String>? {
        if (cleanAllSwitch.isChecked) {
            val list = settings.repos().map { it.repo }
            if (list.isEmpty()) fail("Aucun dépôt mémorisé.")
            return list.ifEmpty { null }
        }
        val ref = RepoRef.parse(cleanRepoField.text.toString())
        if (ref == null) {
            fail("Dépôt invalide. Écris « pseudo/depot ».")
            return null
        }
        return listOf(ref.first + "/" + ref.second)
    }

    private fun startCleanScan() {
        if (running) return
        val repos = cleanTargets() ?: return
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        val o = cleanOptions()
        cleanPlans = emptyList()
        btnCleanRun.visibility = View.GONE
        cleanResult.text = "Analyse…"
        setRunning(true, cancellable = true)
        logView.text = ""
        Thread {
            val plans = ArrayList<GitHubCleaner.Plan>()
            val api = GitHubApi(token)
            api.onWait = { sec -> postLog("Limite de débit GitHub : attente de $sec s…") }
            for (repo in repos) {
                if (cancelFlag) break
                try {
                    val p = GitHubCleaner.plan(api, repo, o) { cancelFlag }
                    plans.add(p)
                    postLog(p.summary())
                } catch (e: Exception) {
                    postLog("✗ $repo : ${e.message ?: e.javaClass.simpleName}")
                }
            }
            runOnUiThread {
                setRunning(false)
                cleanPlans = plans.filter { it.total > 0 }
                val total = cleanPlans.sumOf { it.total }
                cleanResult.text = if (total == 0) "Rien à nettoyer." else
                    cleanPlans.joinToString("\n") { it.summary() }
                cleanResult.setTextColor(if (total == 0) Ui.DIM else Ui.TEXT)
                btnCleanRun.visibility = if (total > 0) View.VISIBLE else View.GONE
                btnCleanRun.text = "Nettoyer ($total élément(s))"
            }
        }.start()
    }

    private fun confirmClean() {
        val plans = cleanPlans
        if (plans.isEmpty() || running) return
        AlertDialog.Builder(this)
            .setTitle("Supprimer sur GitHub ?")
            .setMessage(plans.joinToString("\n\n") { it.summary() } + "\n\nAction définitive.")
            .setPositiveButton("Supprimer") { _, _ -> startCleanRun(plans) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun startCleanRun(plans: List<GitHubCleaner.Plan>) {
        val token = resolveToken() ?: return
        setRunning(true, cancellable = true)
        btnCleanRun.visibility = View.GONE
        val handle = TaskHandle(this, "Nettoyage GitHub")
        Thread {
            var ok = 0
            var errors = 0
            val api = GitHubApi(token)
            try {
                for (p in plans) {
                    postLog("Nettoyage de ${p.repo}…")
                    val (o, e) = GitHubCleaner.execute(api, p, { done, total ->
                        val percent = done * 100 / total.coerceAtLeast(1)
                        handle.progress(percent, "${p.repo} : $done / $total")
                        runOnUiThread { progressBar.progress = percent }
                    }) { cancelFlag }
                    ok += o
                    errors += e
                }
                postLog("✓ Nettoyage terminé : $ok supprimé(s)" + (if (errors > 0) ", $errors erreur(s)" else ""))
            } catch (e: Exception) {
                postLog("✗ " + (e.message ?: e.javaClass.simpleName))
            } finally {
                val line = "$ok supprimé(s)" + (if (errors > 0) ", $errors erreur(s)" else "")
                runOnUiThread {
                    if (errors == 0 && !cancelFlag) handle.done(line) else handle.fail(line)
                    setRunning(false)
                    cleanPlans = emptyList()
                    cleanResult.text = "Terminé : $line."
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ rapports d'erreur

    private fun buildReportsTab(): LinearLayout {
        val tab = vertical()
        val card = Ui.card(this)
        card.addView(
            Ui.note(
                this,
                "Créés à chaque échec (compilation ou envoi). Contenu : étapes, erreurs, fichiers cités, " +
                    "modifications depuis la dernière compilation réussie, configuration Gradle, arborescence, " +
                    "journal complet. Rien n'est écrit dans Téléchargements : tout passe par le presse-papiers.\n" +
                    "« Copier » = rapport COURT (≈ 3 000 à 5 000 tokens), copié aussi automatiquement à chaque échec. " +
                    "« Dialogue » : Claude demande lui-même le reste, au plus juste."
            )
        )
        reportList = vertical()
        card.addView(reportList, Ui.params(this, 10))
        tab.addView(card)
        val clear = Ui.button(this, "Supprimer tous les rapports")
        clear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Supprimer tous les rapports ? ")
                .setPositiveButton("Supprimer") { _, _ -> Reports.clear(this) }
                .setNegativeButton("Annuler", null)
                .show()
        }
        tab.addView(clear, Ui.params(this, 14))
        return tab
    }

    private fun refreshReports() {
        if (!::reportList.isInitialized) return
        reportList.removeAllViews()
        val list = Reports.list(this)
        if (list.isEmpty()) {
            reportList.addView(Ui.note(this, "Aucun rapport."))
            return
        }
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
        for (f in list) {
            val row = Ui.card(this)
            row.background = Ui.rounded(this, Ui.SURFACE_HI, 14)
            val t = TextView(this)
            t.setTextColor(Ui.TEXT)
            t.textSize = 14f
            t.text = f.name.removePrefix("erreur_").removeSuffix(".md")
            row.addView(t)
            row.addView(
                Ui.note(
                    this,
                    fmt.format(java.util.Date(f.lastModified())) + " · complet ≈ " +
                        ErrorReport.tokens(f.length().toInt()) + " tokens"
                )
            )
            val send = Ui.button(this, "Copier", primary = true)
            send.setOnClickListener { sendReport(f, ErrorReport.Level.SHORT) }
            val chat = Ui.button(this, "Dialogue")
            chat.setOnClickListener { ClaudeChatActivity.open(this, f) }
            val more = Ui.button(this, "Plus…")
            more.setOnClickListener { reportMenu(f) }
            row.addView(Ui.row(this, 8, send, chat, more))
            reportList.addView(row, Ui.params(this, 8))
        }
    }

    private fun reportMenu(f: File) {
        val runLink = Regex("github\\.com/([^/\\s]+/[^/\\s]+)/actions/runs/(\\d+)")
            .find(try { f.readText().take(4000) } catch (e: Exception) { "" })
        val items = ArrayList<String>()
        items.add("Copier : moyen")
        items.add("Copier : complet")
        items.add("Autres destinations Claude… (court)")
        items.add("Partager le complet…")
        if (runLink != null) {
            items.add("Voir la compilation sur GitHub")
            items.add("Relancer les jobs en échec")
        }
        items.add("Supprimer")
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Copier : moyen" -> sendReport(f, ErrorReport.Level.MEDIUM)
                    "Copier : complet" -> sendReport(f, ErrorReport.Level.FULL)
                    "Autres destinations Claude… (court)" -> {
                        val short = ErrorReport.levelFile(this, f, ErrorReport.Level.SHORT)
                        ClaudeSender.show(this, ClaudePayload(listOf(short), short.readText(), "rapport d'erreur (court)"))
                    }
                    "Partager le complet…" -> ClaudeSender.shareTo(this, listOf(f), null)
                    "Voir la compilation sur GitHub" -> openUrl("https://" + runLink!!.value)
                    "Relancer les jobs en échec" -> rerun(runLink!!.groupValues[1], runLink.groupValues[2].toLong())
                    "Supprimer" -> Reports.remove(this, f)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Copie le rapport au niveau voulu dans le presse-papiers (fichier partagé seulement s'il est trop long). */
    private fun sendReport(f: File, level: ErrorReport.Level) {
        try {
            val text = if (level == ErrorReport.Level.FULL) f.readText() else ErrorReport.render(f.readText(), level, f.name)
            val tokens = ErrorReport.tokens(text.length)
            if (text.length > ClaudeBundle.CLIP_MAX) {
                val out = ErrorReport.levelFile(this, f, level)
                Toast.makeText(this, "Trop long pour le presse-papiers (≈ $tokens tokens) : partage du fichier.", Toast.LENGTH_LONG).show()
                ClaudeSender.shareTo(this, listOf(out), null)
                return
            }
            ErrorReport.copy(this, text)
            Toast.makeText(this, "Rapport ${level.label} copié (≈ $tokens tokens).", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Rapport illisible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun rerun(repo: String, runId: Long) {
        val token = resolveToken()
        if (token == null) {
            fail("Aucun jeton : colle-le dans la tuile Compte.")
            return
        }
        Thread {
            val ok = try {
                ErrorReport.rerunFailed(GitHubApi(token), repo, runId)
            } catch (e: Exception) {
                false
            }
            postLog(if (ok) "✓ Compilation relancée sur $repo." else "✗ Relance impossible (droit « workflow » du jeton ?).")
        }.start()
    }

    private fun setRunning(value: Boolean, cancellable: Boolean = false) {
        running = value
        if (value) cancelFlag = false
        for (b in listOf(btnPush, btnTest, btnCreate, btnApk, btnPrepare, btnLoadRepos, btnCleanScan, btnCleanRun)) {
            b.isEnabled = !value
            b.alpha = if (value) 0.5f else 1f
        }
        btnCancel.visibility = if (value && cancellable) View.VISIBLE else View.GONE
        progressBar.visibility = if (value) View.VISIBLE else View.GONE
        progressBar.progress = 0
        progressBar.isIndeterminate = false
        if (!value) refreshLatest()
    }

    /**
     * Prépare un rapport d'erreur pour Claude (journaux GitHub si la compilation a échoué)
     * et le copie dans le presse-papiers. À appeler hors du fil UI.
     */
    private fun reportFailure(action: String, repo: String?, e: Throwable, api: GitHubApi?) {
        if (cancelFlag || e.message == "Annulé.") return
        val appLog = try {
            logView.text.toString()
        } catch (x: Exception) {
            ""
        }
        var report: String? = null
        if (e is BuildFailedException && repo != null) {
            val sha = e.run.optString("head_sha")
            if (sha.isNotEmpty()) {
                val gone = BuildArchive.onBuildFailed(applicationContext, repo, sha, deleteSource = true)
                if (gone > 0) postLog("Zip d'origine supprimé du téléphone (compilation échouée).")
            }
        }
        if (e is BuildFailedException && api != null && repo != null) {
            postLog("Récupération des journaux de compilation sur GitHub…")
            try {
                report = ErrorReport.forRun(api, repo, e.run)
                if (report == null) {
                    postLog("Rapport de cette compilation déjà copié.")
                    return
                }
            } catch (x: Exception) {
                postLog("Journaux illisibles (${x.javaClass.simpleName}) : rapport simplifié.")
            }
        }
        val text = report ?: ErrorReport.forError(applicationContext, action, repo, e, appLog)
        val label = (repo?.substringAfter('/') ?: "appli") +
            ((e as? BuildFailedException)?.run?.optString("head_sha")?.take(7)?.let { "_$it" } ?: "")
        val f = ErrorReport.deliver(applicationContext, label, text)
        postLog("📋 Rapport COURT copié : colle-le dans Claude (moyen/complet : tuile Rapports" + (if (f == null) ", non enregistré" else "") + ").")
    }

    private fun fail(message: String) {
        logView.text = ""
        log("✗ $message")
    }

    private fun log(line: String) {
        logView.append(line + "\n")
    }

    /** Peut être appelé depuis n'importe quel thread. */
    private fun postLog(line: String) {
        runOnUiThread { log(line) }
        task?.progress(null, line.trim().take(90))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Aucun navigateur trouvé.", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val HOME = -1
        const val S_SEND = 0
        const val S_IMPORT = 1
        const val S_FILES = 2
        const val S_ACCOUNT = 3
        const val S_CLEAN = 4
        const val S_REPORTS = 5
        val SECTIONS = listOf("Envoyer", "Importer pour Claude", "Historique", "Compte", "Nettoyage GitHub", "Rapports d'erreur")
        const val REQUEST_ZIP = 4201
        const val REQUEST_TREE = 4202
        const val REQUEST_FILES = 4203
    }
}
