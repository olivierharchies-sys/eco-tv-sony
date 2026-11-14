package com.example.gitzippush

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Dialogue de dépannage avec Claude : l'appli fournit le strict nécessaire, Claude demande le reste
 * par commandes « @… », l'utilisateur colle sa réponse, l'appli exécute — jusqu'à la compilation réussie.
 */
class ClaudeChatActivity : Activity() {
    companion object {
        const val EXTRA_REPORT = "report"
        private const val SHOWN = 2500

        fun open(a: Activity, report: File) {
            a.startActivity(Intent(a, ClaudeChatActivity::class.java).putExtra(EXTRA_REPORT, report.absolutePath))
        }
    }

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var run: TextView
    private lateinit var session: ChatSession
    private lateinit var chatFile: File
    private val messages = ArrayList<ChatMessage>()
    @Volatile private var alive = true
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val report = intent.getStringExtra(EXTRA_REPORT)?.let { File(it) }
        val text = try {
            report?.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
        if (report == null || text == null) {
            Toast.makeText(this, "Rapport illisible.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        session = ChatSession.fromReport(text)
        chatFile = ChatSession.fileFor(this, report)
        buildUi()
        messages.addAll(ChatSession.load(chatFile))
        if (messages.isEmpty()) messages.add(ChatMessage(true, session.firstMessage()))
        for (m in messages) addBubble(m)
        scrollDown()
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        val pad = Ui.dp(this, 12)
        root.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 8))

        val back = Ui.button(this, "←")
        back.setOnClickListener { finish() }
        val title = Ui.heading(this, "Dialogue Claude")
        val claude = Ui.button(this, "Claude")
        claude.setOnClickListener { openClaude() }
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.WRAP))
        val tlp = LinearLayout.LayoutParams(0, Ui.WRAP, 1f)
        tlp.leftMargin = Ui.dp(this, 10)
        head.addView(title, tlp)
        head.addView(claude, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        root.addView(head)

        status = Ui.note(this, (session.repo ?: "dépôt inconnu") + (session.branch?.let { " · $it" } ?: "") +
            (session.ref?.let { " · ${it.take(7)}" } ?: ""))
        root.addView(status, Ui.params(this, 6))

        scroll = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        val slp = LinearLayout.LayoutParams(Ui.MATCH, 0, 1f)
        slp.topMargin = Ui.dp(this, 8)
        root.addView(scroll, slp)

        input = Ui.field(this, "Colle ici la réponse de Claude (ou tape @FILE …)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        input.setSingleLine(false)
        input.maxLines = 5
        input.typeface = Typeface.MONOSPACE
        input.textSize = 13f
        root.addView(input, Ui.params(this, 8))

        val paste = Ui.button(this, "Coller")
        paste.setOnClickListener { input.setText(clipboard()) }
        run = Ui.button(this, "Exécuter", primary = true)
        run.setOnClickListener { execute() }
        val more = Ui.button(this, "…")
        more.setOnClickListener { menu() }
        root.addView(Ui.row(this, 6, paste, run, more))
        setContentView(root)
    }

    private fun addBubble(m: ChatMessage) {
        val card = Ui.card(this)
        card.background = Ui.rounded(this, if (m.fromApp) Ui.SURFACE else Ui.SURFACE_HI, 14)
        val who = Ui.note(this, if (m.fromApp) "Appli → à copier pour Claude · ≈ ${ErrorReport.tokens(m.text.length)} tokens" else "Claude")
        card.addView(who)
        val t = TextView(this)
        t.setTextColor(Ui.TEXT)
        t.textSize = 12f
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        t.text = if (m.text.length > SHOWN) m.text.take(SHOWN) + "\n[… ${m.text.length - SHOWN} caractères de plus, inclus dans la copie]" else m.text
        card.addView(t, Ui.params(this, 4))
        if (m.fromApp) {
            val copy = Ui.button(this, "Copier", primary = true)
            copy.setOnClickListener { copyOut(m.text) }
            val share = Ui.button(this, "Copier + Claude")
            share.setOnClickListener { if (copyOut(m.text)) openClaude() }
            card.addView(Ui.row(this, 8, copy, share))
        }
        list.addView(card, Ui.params(this, 8))
    }

    private fun push(m: ChatMessage) {
        messages.add(m)
        ChatSession.save(chatFile, messages)
        addBubble(m)
        scrollDown()
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun setBusy(b: Boolean, note: String? = null) {
        busy = b
        run.isEnabled = !b
        run.alpha = if (b) 0.5f else 1f
        if (note != null) status.text = note
    }

    // ------------------------------------------------------------------ actions

    private fun execute() {
        if (busy) return
        val text = input.text.toString()
        if (text.isBlank()) {
            input.setText(clipboard())
            if (input.text.isBlank()) return
            return execute()
        }
        val cmds = ChatSession.parse(text)
        if (cmds.isEmpty()) {
            Toast.makeText(this, "Aucune commande @… : rien à exécuter.", Toast.LENGTH_LONG).show()
            return
        }
        val token = Settings(this).token()
        if (token == null) {
            Toast.makeText(this, "Aucun jeton GitHub : renseigne-le dans Compte.", Toast.LENGTH_LONG).show()
            return
        }
        input.setText("")
        push(ChatMessage(false, text.trim()))
        setBusy(true, "Exécution de ${cmds.size} commande(s)…")
        val api = GitHubApi(token).also { it.service = Service.CLAUDE }
        Thread {
            val out = try {
                session.execute(api, cmds)
            } catch (e: Exception) {
                ChatSession.Outcome("✗ " + (e.message ?: e.javaClass.simpleName), null)
            }
            runOnUiThread {
                if (!alive) return@runOnUiThread
                push(ChatMessage(true, out.text))
                if (out.commitSha != null) follow(api, out.commitSha) else setBusy(false, statusLine())
            }
        }.start()
    }

    /** Suit la compilation du commit, puis ajoute le résultat à copier. */
    private fun follow(api: GitHubApi, sha: String) {
        setBusy(true, "Compilation de ${sha.take(7)}…")
        Thread {
            val msg = try {
                session.waitBuild(applicationContext, api, sha, { alive }) { p ->
                    runOnUiThread { if (alive) status.text = p }
                }
            } catch (e: Exception) {
                "✗ Suivi impossible : ${e.message}"
            }
            runOnUiThread {
                if (!alive) return@runOnUiThread
                if (msg != null) push(ChatMessage(true, msg))
                setBusy(false, statusLine())
            }
        }.start()
    }

    private fun statusLine() = (session.repo ?: "dépôt inconnu") + (session.branch?.let { " · $it" } ?: "") +
        (session.ref?.let { " · ${it.take(7)}" } ?: "")

    private fun menu() {
        val items = arrayOf("Suivre la compilation du dernier commit", "Recommencer le dialogue", "Aide : commandes")
        android.app.AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val sha = session.ref
                        val token = Settings(this).token()
                        if (sha == null || token == null || busy) Toast.makeText(this, "Impossible maintenant.", Toast.LENGTH_SHORT).show()
                        else follow(GitHubApi(token).also { it.service = Service.CLAUDE }, sha)
                    }
                    1 -> {
                        messages.clear()
                        list.removeAllViews()
                        push(ChatMessage(true, session.firstMessage()))
                    }
                    2 -> android.app.AlertDialog.Builder(this)
                        .setTitle("Commandes")
                        .setMessage(ChatProtocol.TEXT)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    /** Copie vers le presse-papiers ; trop long → fichier partagé vers Claude. */
    private fun copyOut(text: String): Boolean {
        if (text.length > ClaudeBundle.CLIP_MAX) {
            val dir = ShareProvider.dir(this).also { it.mkdirs() }
            val f = File(dir, "dialogue_claude.md")
            f.writeText(text, Charsets.UTF_8)
            ClaudeSender.shareTo(this, listOf(f), ClaudeSender.CLAUDE_PACKAGE)
            return false
        }
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Pour Claude", text))
            if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copié.", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Copie impossible : ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun clipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ""
        return if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
    }

    private fun openClaude() {
        val launch = packageManager.getLaunchIntentForPackage(ClaudeSender.CLAUDE_PACKAGE)
        if (launch == null) {
            Toast.makeText(this, "Appli Claude introuvable.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            Toast.makeText(this, "Ouverture impossible.", Toast.LENGTH_SHORT).show()
        }
    }
}
