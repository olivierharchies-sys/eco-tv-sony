package com.example.gitzippush

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Consultation des fichiers d'un zip (sauvegarde de compilation, zip choisi…) :
 * arborescence, recherche par nom ou dans le contenu, aperçu du texte, sélection de fichiers ou d'un extrait
 * et envoi à Claude.
 */
class FilesActivity : Activity() {

    private sealed class Row {
        object Up : Row()
        class Folder(val name: String, val path: String, val count: Int) : Row()
        class FileRow(val item: ZipItem, val sub: String?) : Row()
    }

    private var index: ZipIndex? = null
    private var title = ""
    private var baseDir = ""
    private var dir = ""
    private var contentMode = false
    private var contentHits: List<ContentHit>? = null
    private val selected = LinkedHashSet<String>()
    private val rows = ArrayList<Row>()

    private var searchVersion = 0

    @Volatile
    private var stopSearch = false

    // aperçu
    private var viewing: ZipItem? = null
    private var viewText: String = ""
    private var matchPos = -1

    private lateinit var titleView: TextView
    private lateinit var searchField: android.widget.EditText
    private lateinit var btnNames: TextView
    private lateinit var btnContent: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var crumb: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: RowAdapter
    private lateinit var listBar: LinearLayout
    private lateinit var btnSend: TextView
    private lateinit var viewerBox: LinearLayout
    private lateinit var viewerScroll: ScrollView
    private lateinit var viewerText: TextView
    private lateinit var viewerInfo: TextView
    private lateinit var btnPick: TextView
    private lateinit var btnNext: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        buildUi()

        val path = intent.getStringExtra(EXTRA_PATH)
        val t = intent.getStringExtra(EXTRA_TITLE)
        when {
            path != null -> load(File(path), t ?: File(path).name)
            intent.data != null -> loadUri(intent.data!!)
            else -> pickZip()
        }
    }

    override fun onDestroy() {
        stopSearch = true
        try {
            index?.close()
        } catch (e: Exception) {
            // rien
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        val pad = Ui.dp(this, 14)
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 10))

        val back = Ui.button(this, "←")
        back.setOnClickListener { goBack() }
        titleView = TextView(this)
        titleView.setTextColor(Ui.TEXT)
        titleView.textSize = 18f
        titleView.setTypeface(titleView.typeface, Typeface.BOLD)
        titleView.maxLines = 2
        titleView.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0)
        val menu = Ui.button(this, "⋯")
        menu.setOnClickListener { showMenu() }
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.WRAP))
        header.addView(titleView, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        header.addView(menu, LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.WRAP))
        root.addView(header)

        searchField = Ui.field(this, "Rechercher (nom, *.kt, ou texte)…")
        searchField.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // la recherche par nom est instantanée ; celle dans le contenu attend « Rechercher »
                if (!contentMode) runSearch() else if (s.isNullOrEmpty()) runSearch()
            }
        })
        root.addView(searchField, Ui.params(this, 10))

        btnNames = Ui.button(this, "Noms de fichiers")
        btnNames.setOnClickListener { setMode(false) }
        btnContent = Ui.button(this, "Dans le contenu")
        btnContent.setOnClickListener { setMode(true) }
        root.addView(Ui.row(this, 8, btnNames, btnContent))

        status = Ui.note(this)
        root.addView(status, Ui.params(this, 6))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        progress.visibility = View.GONE
        root.addView(progress, Ui.params(this, 4))

        crumb = TextView(this)
        crumb.setTextColor(Ui.ACCENT)
        crumb.textSize = 13f
        crumb.typeface = Typeface.MONOSPACE
        root.addView(crumb, Ui.params(this, 6))

        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = Ui.dp(this, 2)
        adapter = RowAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val r = rows[pos]
            if (r is Row.Folder) toggleFolder(r.path) else if (r is Row.FileRow) toggle(r.item.path)
            true
        }
        root.addView(listView, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).also { it.topMargin = Ui.dp(this, 6) })

        val all = Ui.button(this, "Tout cocher")
        all.setOnClickListener { checkAllVisible() }
        val none = Ui.button(this, "Décocher")
        none.setOnClickListener {
            selected.clear()
            refreshSelection()
        }
        btnSend = Ui.button(this, "Envoyer à Claude", primary = true)
        btnSend.setOnClickListener { sendSelection() }
        listBar = Ui.row(this, 8, all, none)
        root.addView(listBar)
        root.addView(btnSend, Ui.params(this, 8))

        // --- aperçu d'un fichier
        viewerBox = LinearLayout(this)
        viewerBox.orientation = LinearLayout.VERTICAL
        viewerBox.visibility = View.GONE
        viewerInfo = Ui.note(this)
        viewerBox.addView(viewerInfo)
        viewerScroll = ScrollView(this)
        viewerScroll.background = Ui.rounded(this, Ui.SURFACE, 12)
        viewerText = TextView(this)
        viewerText.setTextColor(Ui.TEXT)
        viewerText.textSize = 12f
        viewerText.typeface = Typeface.MONOSPACE
        viewerText.setTextIsSelectable(true)
        val vp = Ui.dp(this, 10)
        viewerText.setPadding(vp, vp, vp, vp)
        viewerText.customSelectionActionModeCallback = selectionCallback
        viewerScroll.addView(viewerText)
        viewerBox.addView(viewerScroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).also { it.topMargin = Ui.dp(this, 6) })
        btnPick = Ui.button(this, "Cocher ce fichier")
        btnPick.setOnClickListener { viewing?.let { toggle(it.path); refreshViewerButtons() } }
        btnNext = Ui.button(this, "Occurrence suivante")
        btnNext.setOnClickListener { nextMatch() }
        viewerBox.addView(Ui.row(this, 8, btnPick, btnNext))
        val sendOne = Ui.button(this, "Envoyer ce fichier à Claude", primary = true)
        sendOne.setOnClickListener { viewing?.let { send(listOf(it.path), null) } }
        viewerBox.addView(sendOne, Ui.params(this, 8))
        viewerBox.addView(
            Ui.note(this, "Astuce : sélectionne un passage du texte puis « Envoyer à Claude » pour n'envoyer que cet extrait."),
            Ui.params(this, 6)
        )
        root.addView(viewerBox, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).also { it.topMargin = Ui.dp(this, 6) })

        setContentView(root)
        setMode(false)
        refreshSelection()
    }

    private fun setMode(content: Boolean) {
        contentMode = content
        btnNames.background = Ui.rounded(this, if (!content) Ui.ACCENT else Ui.SURFACE_HI, 14)
        btnNames.setTextColor(if (!content) Ui.BG else Ui.TEXT)
        btnContent.background = Ui.rounded(this, if (content) Ui.ACCENT else Ui.SURFACE_HI, 14)
        btnContent.setTextColor(if (content) Ui.BG else Ui.TEXT)
        searchField.hint = if (content) "Texte à chercher dans les fichiers, puis Entrée" else "Nom de fichier, morceau de chemin ou *.kt"
        if (index != null) runSearch()
    }

    // ------------------------------------------------------------------ chargement

    private fun pickZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        try {
            startActivityForResult(intent, REQUEST_ZIP)
        } catch (e: Exception) {
            Toast.makeText(this, "Sélecteur de fichiers indisponible.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ZIP) return
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) loadUri(uri) else if (index == null) finish()
    }

    /** Copie le zip choisi (lien Android) dans le cache de l'appli, puis l'ouvre. */
    private fun loadUri(uri: Uri) {
        val name = FilesSource.displayName(this, uri)
        status.text = "Ouverture de $name…"
        Thread {
            try {
                val d = browseDir(this)
                d.mkdirs()
                d.listFiles()?.forEach { it.delete() }
                val f = File(d, ClaudeBundle.safeName(name.removeSuffix(".zip")) + ".zip")
                val input = contentResolver.openInputStream(uri) ?: throw PushException("Impossible d'ouvrir le zip.")
                input.use { i -> f.outputStream().use { i.copyTo(it) } }
                runOnUiThread { load(f, name) }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "✗ ${e.message ?: e.javaClass.simpleName}"
                    status.setTextColor(Ui.ERROR)
                }
            }
        }.start()
    }

    private fun load(file: File, name: String) {
        title = name
        titleView.text = name
        status.text = "Lecture…"
        status.setTextColor(Ui.DIM)
        Thread {
            try {
                val idx = ZipIndex(file)
                runOnUiThread {
                    index?.close()
                    index = idx
                    selected.clear()
                    contentHits = null
                    baseDir = idx.root?.let { "$it/" } ?: ""
                    dir = baseDir
                    closeViewer()
                    val total = idx.items.sumOf { it.size }
                    titleView.text = "$name"
                    status.text = "${idx.items.size} fichier(s), ${formatSize(total)}"
                    runSearch()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "✗ Ce fichier n'est pas un zip lisible (${e.message ?: e.javaClass.simpleName})."
                    status.setTextColor(Ui.ERROR)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ liste

    private fun runSearch() {
        val idx = index ?: return
        val q = searchField.text.toString().trim()
        val version = ++searchVersion
        stopSearch = true
        rows.clear()
        if (q.isEmpty()) {
            contentHits = null
            progress.visibility = View.GONE
            crumb.visibility = View.VISIBLE
            crumb.text = "📂 " + title + "/" + dir.removePrefix(baseDir)
            val (dirs, files) = idx.children(dir)
            if (dir != baseDir) rows.add(Row.Up)
            for (d in dirs) {
                val p = "$dir$d/"
                rows.add(Row.Folder(d, p, idx.under(p).size))
            }
            for (f in files) rows.add(Row.FileRow(f, formatSize(f.size)))
            status.text = "${idx.items.size} fichier(s) · appui long sur un dossier = tout cocher"
            status.setTextColor(Ui.DIM)
            adapter.notifyDataSetChanged()
            return
        }
        crumb.visibility = View.GONE
        if (!contentMode) {
            val found = idx.searchNames(q)
            for (f in found.take(2000)) rows.add(Row.FileRow(f, f.path.removePrefix(baseDir).substringBeforeLast('/', "") + " · " + formatSize(f.size)))
            status.text = "${found.size} fichier(s) dont le nom correspond à « $q »"
            status.setTextColor(if (found.isEmpty()) Ui.WARN else Ui.ACCENT)
            adapter.notifyDataSetChanged()
            return
        }
        // recherche dans le contenu : en arrière-plan
        adapter.notifyDataSetChanged()
        stopSearch = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Recherche de « $q » dans le contenu…"
        status.setTextColor(Ui.DIM)
        Thread {
            val hits = idx.searchContent(q, idx.items, { stopSearch || version != searchVersion }) { p ->
                runOnUiThread { if (version == searchVersion) progress.progress = p }
            }
            runOnUiThread {
                if (version != searchVersion) return@runOnUiThread
                progress.visibility = View.GONE
                contentHits = hits
                rows.clear()
                for (h in hits) {
                    val sb = StringBuilder()
                    sb.append(h.item.path.removePrefix(baseDir)).append(" · ").append(h.count).append(" occurrence(s)")
                    for ((n, line) in h.lines) sb.append("\n  L").append(n).append(" : ").append(line)
                    rows.add(Row.FileRow(h.item, sb.toString()))
                }
                val total = hits.sumOf { it.count }
                status.text = if (hits.isEmpty()) "Aucun fichier ne contient « $q »." else
                    "$total occurrence(s) dans ${hits.size} fichier(s)" + (if (hits.size >= ZipIndex.MAX_HITS) " (liste limitée)" else "")
                status.setTextColor(if (hits.isEmpty()) Ui.WARN else Ui.ACCENT)
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun onRowClick(r: Row) {
        when (r) {
            is Row.Up -> goUp()
            is Row.Folder -> {
                dir = r.path
                runSearch()
                listView.setSelection(0)
            }
            is Row.FileRow -> openViewer(r.item)
        }
    }

    private fun goUp() {
        if (dir == baseDir) return
        val trimmed = dir.removeSuffix("/")
        dir = if (trimmed.contains('/')) trimmed.substringBeforeLast('/') + "/" else ""
        if (!dir.startsWith(baseDir)) dir = baseDir
        runSearch()
    }

    private fun toggle(path: String) {
        if (!selected.remove(path)) selected.add(path)
        refreshSelection()
    }

    private fun toggleFolder(path: String) {
        val idx = index ?: return
        val under = idx.under(path).map { it.path }
        if (under.all { it in selected }) selected.removeAll(under.toSet()) else selected.addAll(under)
        refreshSelection()
    }

    private fun checkAllVisible() {
        val idx = index ?: return
        for (r in rows) {
            when (r) {
                is Row.FileRow -> selected.add(r.item.path)
                is Row.Folder -> selected.addAll(idx.under(r.path).map { it.path })
                else -> {}
            }
        }
        refreshSelection()
    }

    private fun refreshSelection() {
        btnSend.text = if (selected.isEmpty()) "Envoyer à Claude (coche des fichiers)" else "Envoyer à Claude (${selected.size})"
        btnSend.alpha = if (selected.isEmpty()) 0.5f else 1f
        adapter.notifyDataSetChanged()
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx: Context = this@FilesActivity
            val holder = (convertView as? LinearLayout) ?: makeRow(ctx)
            val check = holder.getChildAt(0) as CheckBox
            val texts = holder.getChildAt(1) as LinearLayout
            val main = texts.getChildAt(0) as TextView
            val sub = texts.getChildAt(1) as TextView
            check.setOnCheckedChangeListener(null)
            val idx = index
            when (val r = rows[position]) {
                is Row.Up -> {
                    check.visibility = View.INVISIBLE
                    main.text = "⬆  Dossier parent"
                    sub.visibility = View.GONE
                }
                is Row.Folder -> {
                    check.visibility = View.VISIBLE
                    val under = idx?.under(r.path)?.map { it.path } ?: emptyList()
                    check.isChecked = under.isNotEmpty() && under.all { it in selected }
                    check.setOnCheckedChangeListener { _, _ -> toggleFolder(r.path) }
                    main.text = "📁  ${r.name}"
                    sub.text = "${r.count} fichier(s)"
                    sub.visibility = View.VISIBLE
                }
                is Row.FileRow -> {
                    check.visibility = View.VISIBLE
                    check.isChecked = r.item.path in selected
                    check.setOnCheckedChangeListener { _, _ -> toggle(r.item.path) }
                    main.text = "📄  ${r.item.name}"
                    sub.text = r.sub ?: ""
                    sub.visibility = if (r.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
            }
            return holder
        }

        private fun makeRow(ctx: Context): LinearLayout {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = Ui.rounded(ctx, Ui.SURFACE, 12)
            val p = Ui.dp(ctx, 8)
            row.setPadding(p / 2, p, p, p)
            val check = CheckBox(ctx)
            check.buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            check.isFocusable = false
            row.addView(check, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
            val texts = LinearLayout(ctx)
            texts.orientation = LinearLayout.VERTICAL
            val main = TextView(ctx)
            main.setTextColor(Ui.TEXT)
            main.textSize = 15f
            val sub = TextView(ctx)
            sub.setTextColor(Ui.DIM)
            sub.textSize = 12f
            sub.maxLines = 4
            texts.addView(main)
            texts.addView(sub)
            row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            return row
        }
    }

    // ------------------------------------------------------------------ aperçu

    private fun openViewer(item: ZipItem) {
        val idx = index ?: return
        viewing = item
        viewerInfo.text = "${item.path.removePrefix(baseDir)} · ${formatSize(item.size)} · lecture…"
        viewerText.text = ""
        showViewer(true)
        val q = searchField.text.toString().trim()
        Thread {
            val text = try {
                idx.text(item)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (viewing !== item) return@runOnUiThread
                if (text == null) {
                    viewText = ""
                    viewerInfo.text = "${item.path.removePrefix(baseDir)} · ${formatSize(item.size)}\nFichier binaire ou trop gros : aperçu impossible (il peut quand même être coché)."
                    btnNext.visibility = View.GONE
                    refreshViewerButtons()
                    return@runOnUiThread
                }
                viewText = text
                val shown = if (text.length > MAX_VIEW) text.substring(0, MAX_VIEW) else text
                val lines = text.count { it == '\n' } + 1
                val span = SpannableString(shown)
                var count = 0
                if (q.isNotEmpty()) {
                    var i = shown.indexOf(q, 0, ignoreCase = true)
                    while (i >= 0 && count < 3000) {
                        span.setSpan(BackgroundColorSpan(0x6634C77B), i, i + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        span.setSpan(ForegroundColorSpan(Ui.TEXT), i, i + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        count++
                        i = shown.indexOf(q, i + q.length, ignoreCase = true)
                    }
                }
                viewerText.text = span
                viewerInfo.text = "${item.path.removePrefix(baseDir)} · $lines ligne(s) · ${formatSize(item.size)}" +
                    (if (q.isNotEmpty()) " · $count occurrence(s) de « $q »" else "") +
                    (if (text.length > MAX_VIEW) "\nAperçu limité aux ${MAX_VIEW / 1000} premiers k caractères (l'envoi inclut tout)." else "")
                btnNext.visibility = if (count > 0) View.VISIBLE else View.GONE
                matchPos = -1
                viewerScroll.scrollTo(0, 0)
                if (count > 0) viewerText.post { nextMatch() }
                refreshViewerButtons()
            }
        }.start()
    }

    private fun nextMatch() {
        val q = searchField.text.toString().trim()
        if (q.isEmpty()) return
        val shown = viewerText.text.toString()
        var i = shown.indexOf(q, matchPos + 1, ignoreCase = true)
        if (i < 0) i = shown.indexOf(q, 0, ignoreCase = true)
        if (i < 0) return
        matchPos = i
        val layout = viewerText.layout ?: return
        val line = layout.getLineForOffset(i)
        viewerScroll.smoothScrollTo(0, maxOf(0, layout.getLineTop(line) - Ui.dp(this, 40)))
    }

    private fun refreshViewerButtons() {
        val v = viewing ?: return
        btnPick.text = if (v.path in selected) "✓ Coché (décocher)" else "Cocher ce fichier"
    }

    private fun showViewer(show: Boolean) {
        viewerBox.visibility = if (show) View.VISIBLE else View.GONE
        val listVis = if (show) View.GONE else View.VISIBLE
        listView.visibility = listVis
        listBar.visibility = listVis
        btnSend.visibility = listVis
        crumb.visibility = if (show || searchField.text.isNotEmpty()) View.GONE else View.VISIBLE
    }

    private fun closeViewer() {
        viewing = null
        viewText = ""
        showViewer(false)
        refreshSelection()
    }

    /** Menu de sélection du texte : ajoute « Envoyer à Claude » pour l'extrait choisi. */
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, MENU_SEND, 0, "Envoyer à Claude")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != MENU_SEND) return false
            val start = minOf(viewerText.selectionStart, viewerText.selectionEnd)
            val end = maxOf(viewerText.selectionStart, viewerText.selectionEnd)
            val v = viewing
            if (v != null && start in 0 until end) {
                val all = viewerText.text.toString()
                val from = all.substring(0, start).count { it == '\n' } + 1
                val to = from + all.substring(start, end).trimEnd('\n').count { it == '\n' }
                sendExcerpt(v, all.substring(start, end), from, to)
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    // ------------------------------------------------------------------ envoi

    private fun sendSelection() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Coche d'abord des fichiers (ou appui long sur un dossier).", Toast.LENGTH_SHORT).show()
            return
        }
        send(selected.toList(), null)
    }

    private fun send(paths: List<String>, note: String?) {
        val idx = index ?: return
        status.text = "Préparation pour Claude…"
        status.setTextColor(Ui.DIM)
        Thread {
            try {
                val b = ClaudeBundle.build(this, idx, title.removeSuffix(".zip"), paths, note)
                runOnUiThread {
                    status.text = "Prêt : ${b.label}, ${b.chars / 1000} k caractères (~${b.chars / 4000} k jetons)" +
                        (if (b.skipped.isNotEmpty()) ", ${b.skipped.size} non inclus (binaires/trop gros)" else "")
                    status.setTextColor(Ui.ACCENT)
                    if (b.included == 0) {
                        Toast.makeText(this, "Aucun fichier texte dans la sélection.", Toast.LENGTH_LONG).show()
                    } else {
                        ClaudeSender.show(this, ClaudePayload(b.parts, b.singleText(), b.label))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "✗ ${e.message ?: e.javaClass.simpleName}"
                    status.setTextColor(Ui.ERROR)
                }
            }
        }.start()
    }

    private fun sendExcerpt(item: ZipItem, text: String, from: Int, to: Int) {
        val b = ClaudeBundle.excerpt(this, title.removeSuffix(".zip"), item.path.removePrefix(baseDir), text, from, to)
        ClaudeSender.show(this, ClaudePayload(b.parts, b.singleText(), "extrait de ${item.name} (lignes $from-$to)"))
    }

    private fun showMenu() {
        val idx = index
        val items = arrayOf(
            "Ouvrir un autre zip…",
            "Envoyer tout le projet à Claude",
            "Partager ce zip",
            "Gérer les destinations Claude"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> pickZip()
                    1 -> if (idx != null) {
                        val all = idx.items.filter { !Generated.isIgnoredPath(it.path) && !MdTools.binaryByName(it.path) }
                        send(all.map { it.path }, "Projet complet (fichiers texte).")
                    }
                    2 -> if (idx != null) shareZip(idx.file)
                    3 -> ClaudeSender.manage(this)
                }
            }
            .show()
    }

    private fun shareZip(file: File) {
        ClaudeSender.shareTo(this, listOf(file), null, "application/zip")
    }

    // ------------------------------------------------------------------ retour

    private fun goBack() {
        when {
            viewing != null -> closeViewer()
            searchField.text.isNotEmpty() -> searchField.setText("")
            dir != baseDir -> goUp()
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (viewing == null && searchField.text.isEmpty() && dir == baseDir) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            goBack()
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        private const val REQUEST_ZIP = 5101
        private const val MENU_SEND = 9001
        private const val MAX_VIEW = 400_000

        fun browseDir(ctx: Context) = File(ctx.cacheDir, "browse")

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes o"
            bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
            else -> String.format("%.1f Mo", bytes / 1048576.0)
        }

        fun open(ctx: Context, file: File, title: String) {
            ctx.startActivity(
                Intent(ctx, FilesActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).putExtra(EXTRA_TITLE, title)
            )
        }

        fun open(ctx: Context, uri: Uri) {
            val i = Intent(ctx, FilesActivity::class.java)
            i.data = uri
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(i)
        }
    }
}
