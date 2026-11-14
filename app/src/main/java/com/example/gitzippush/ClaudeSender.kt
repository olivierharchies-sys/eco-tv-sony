package com.example.gitzippush

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Une destination Claude mémorisée : une conversation (éventuellement dans un projet) ou un projet.
 * [url] : lien claude.ai copié depuis l'appli ou le site (« Partager » / barre d'adresse).
 */
class ClaudeDest(val id: String, val name: String, val url: String, val project: String, val usedAt: Long) {
    val isProject: Boolean get() = ClaudeDests.isProjectUrl(url)

    fun label(): String = when {
        isProject -> "📁 $name"
        project.isNotEmpty() -> "💬 $name  ·  projet $project"
        else -> "💬 $name"
    }

    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("name", name).put("url", url).put("project", project).put("t", usedAt)
}

object ClaudeDests {
    fun isProjectUrl(url: String) = url.contains("/project/")

    fun list(ctx: Context): List<ClaudeDest> {
        return try {
            val arr = JSONArray(Settings(ctx).claudeDests)
            val out = ArrayList<ClaudeDest>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ClaudeDest(
                        o.optString("id"), o.optString("name"), o.optString("url"),
                        o.optString("project"), o.optLong("t")
                    )
                )
            }
            out.sortedByDescending { it.usedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<ClaudeDest>) {
        val arr = JSONArray()
        for (d in list) arr.put(d.toJson())
        Settings(ctx).claudeDests = arr.toString()
    }

    fun add(ctx: Context, d: ClaudeDest) = save(ctx, list(ctx).filter { it.id != d.id } + d)
    fun remove(ctx: Context, id: String) = save(ctx, list(ctx).filter { it.id != id })
    fun touch(ctx: Context, d: ClaudeDest) =
        add(ctx, ClaudeDest(d.id, d.name, d.url, d.project, System.currentTimeMillis()))

    /** Nettoie un lien collé (texte autour, espaces). Renvoie null s'il n'y a pas de lien. */
    fun cleanUrl(raw: String): String? {
        val m = Regex("https?://\\S+").find(raw.trim()) ?: return null
        return m.value.trimEnd('.', ',', ')', ']')
    }
}

/** Ce qu'on envoie : des fichiers Markdown (et le texte, s'il tient dans le presse-papiers). */
class ClaudePayload(val files: List<File>, val text: String?, val label: String, val repo: String? = null)

/**
 * Envoi d'une sélection à Claude. Android ne permet pas de déposer un fichier directement dans une conversation
 * ou un projet précis : on prépare donc le contenu (presse-papiers + fichier dans Téléchargements/Envoi GitHub/Pour Claude),
 * puis on ouvre la bonne conversation ou le bon projet dans Claude, où il reste à coller ou joindre.
 */
object ClaudeSender {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    fun show(a: Activity, p: ClaudePayload) {
        val sizeNote = "${p.label} · ${p.files.size} fichier(s) Markdown" +
            (if (p.text != null) "" else " (trop long pour le presse-papiers : à joindre)")
        val choices = arrayOf(
            "Nouvelle conversation",
            "Conversation existante…",
            "Nouvelle conversation dans un projet…",
            "Connaissances (contexte) d'un projet…",
            "Contexte de la conversation ouverte (copier)",
            "Autre application…"
        )
        AlertDialog.Builder(a)
            .setTitle("Envoyer à Claude — $sizeNote")
            .setItems(choices) { _, i ->
                when (i) {
                    0 -> shareTo(a, p.files, CLAUDE_PACKAGE)
                    1 -> pick(a, projects = false, title = "Quelle conversation ?") { d -> deliver(a, p, d, Mode.CONVERSATION) }
                    2 -> pick(a, projects = true, title = "Quel projet ?") { d -> deliver(a, p, d, Mode.PROJECT_CHAT) }
                    3 -> pick(a, projects = true, title = "Quel projet ?") { d -> deliver(a, p, d, Mode.PROJECT_KNOWLEDGE) }
                    4 -> copyOnly(a, p)
                    5 -> shareTo(a, p.files, null)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private enum class Mode { CONVERSATION, PROJECT_CHAT, PROJECT_KNOWLEDGE }

    // ------------------------------------------------------------------ choix d'une destination

    private fun pick(a: Activity, projects: Boolean, title: String, onPick: (ClaudeDest) -> Unit) {
        val list = ClaudeDests.list(a).filter { it.isProject == projects }
        val labels = list.map { it.label() }.toTypedArray()
        val b = AlertDialog.Builder(a).setTitle(title)
        if (list.isEmpty()) {
            b.setMessage(
                if (projects) {
                    "Aucun projet mémorisé. Ajoute son lien : dans Claude, ouvre le projet puis copie son adresse " +
                        "(https://claude.ai/project/…)."
                } else {
                    "Aucune conversation mémorisée. Ajoute son lien : dans Claude, ouvre la conversation puis copie son " +
                        "adresse (https://claude.ai/chat/…)."
                }
            )
        } else {
            b.setItems(labels) { _, i ->
                ClaudeDests.touch(a, list[i])
                onPick(list[i])
            }
        }
        b.setPositiveButton("Ajouter…") { _, _ -> editDest(a, projects) { d -> onPick(d) } }
        b.setNegativeButton("Fermer", null)
        b.show()
    }

    /** Ajout d'une destination : nom, lien, projet (facultatif, pour une conversation de projet). */
    fun editDest(a: Activity, project: Boolean?, onSaved: ((ClaudeDest) -> Unit)?) {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(a, 20)
        box.setPadding(pad, Ui.dp(a, 8), pad, 0)
        val name = Ui.field(a, "Nom (ex. Appli Éco TV)")
        val url = Ui.field(a, "Lien claude.ai/chat/… ou claude.ai/project/…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val proj = Ui.field(a, "Projet de cette conversation (facultatif)")
        box.addView(name)
        box.addView(url, Ui.params(a, 8))
        if (project != true) box.addView(proj, Ui.params(a, 8))
        val paste = Ui.button(a, "Coller le lien")
        paste.setOnClickListener {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(a).toString() else ""
            val u = ClaudeDests.cleanUrl(t)
            if (u == null) Toast.makeText(a, "Pas de lien dans le presse-papiers.", Toast.LENGTH_SHORT).show()
            else url.setText(u)
        }
        box.addView(paste, Ui.params(a, 8))
        box.addView(
            Ui.note(
                a,
                "Le lien se trouve dans la barre d'adresse sur claude.ai, ou via « Partager » / « Copier le lien ». " +
                    "Un lien /project/ désigne un projet, un lien /chat/ une conversation."
            ),
            Ui.params(a, 8)
        )
        AlertDialog.Builder(a)
            .setTitle(
                when (project) {
                    true -> "Ajouter un projet"
                    false -> "Ajouter une conversation"
                    null -> "Ajouter une destination Claude"
                }
            )
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val u = ClaudeDests.cleanUrl(url.text.toString())
                if (u == null) {
                    Toast.makeText(a, "Lien invalide : il doit commencer par https://", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (!u.contains("claude.ai")) {
                    Toast.makeText(a, "Attention : ce n'est pas un lien claude.ai.", Toast.LENGTH_LONG).show()
                }
                val n = name.text.toString().trim().ifEmpty {
                    if (ClaudeDests.isProjectUrl(u)) "Projet" else "Conversation"
                }
                val d = ClaudeDest(
                    "d" + System.currentTimeMillis(), n, u,
                    if (ClaudeDests.isProjectUrl(u)) "" else proj.text.toString().trim(),
                    System.currentTimeMillis()
                )
                ClaudeDests.add(a, d)
                onSaved?.invoke(d)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Liste des destinations, avec ajout et suppression (onglet Compte). */
    fun manage(a: Activity) {
        val list = ClaudeDests.list(a)
        val b = AlertDialog.Builder(a).setTitle("Destinations Claude")
        if (list.isEmpty()) {
            b.setMessage("Aucune destination. Ajoute le lien d'une conversation ou d'un projet Claude.")
        } else {
            b.setItems(list.map { it.label() }.toTypedArray()) { _, i ->
                val d = list[i]
                AlertDialog.Builder(a)
                    .setTitle(d.name)
                    .setMessage(d.url)
                    .setPositiveButton("Ouvrir") { _, _ -> openInClaude(a, d.url) }
                    .setNeutralButton("Supprimer") { _, _ ->
                        ClaudeDests.remove(a, d.id)
                        manage(a)
                    }
                    .setNegativeButton("Fermer", null)
                    .show()
            }
        }
        b.setPositiveButton("Ajouter…") { _, _ -> editDest(a, null) { manage(a) } }
        b.setNegativeButton("Fermer", null)
        b.show()
    }

    // ------------------------------------------------------------------ livraison

    private fun deliver(a: Activity, p: ClaudePayload, d: ClaudeDest, mode: Mode) {
        val copied = p.text != null && copy(a, p.text)
        val saved = ArrayList<String>()
        for (f in p.files) {
            PublicFiles.save(a, "Pour Claude", f.name, f, "text/markdown", p.repo)?.let { saved.add(it) }
        }
        val sb = StringBuilder()
        if (copied) sb.append("✓ Texte copié (${p.text!!.length / 1000} k caractères).\n")
        if (saved.isNotEmpty()) {
            sb.append("✓ Fichier(s) enregistré(s) :\n")
            for (s in saved) sb.append("   ").append(s).append('\n')
        }
        if (!copied && saved.isEmpty()) {
            sb.append("Impossible de copier ou d'enregistrer le contenu sur ce téléphone : utilise « Nouvelle conversation » ou « Autre application ».\n")
        }
        sb.append('\n')
        sb.append(
            when (mode) {
                Mode.CONVERSATION ->
                    "Dans la conversation « ${d.name} » : appui long dans la zone de saisie > Coller" +
                        (if (saved.isNotEmpty()) ", ou bouton + > Fichiers > le chemin indiqué ci-dessus." else ".")
                Mode.PROJECT_CHAT ->
                    "Dans le projet « ${d.name} » : démarre une nouvelle conversation puis colle le texte" +
                        (if (saved.isNotEmpty()) " ou joins le fichier (bouton +)." else ".")
                Mode.PROJECT_KNOWLEDGE ->
                    "Dans le projet « ${d.name} » : section Connaissances / Fichiers du projet > + > " +
                        (if (saved.isNotEmpty()) "Importer un fichier (chemin ci-dessus)" else "") +
                        (if (saved.isNotEmpty() && copied) ", ou " else "") +
                        (if (copied) "Ajouter du texte puis Coller" else "") + "."
            }
        )
        AlertDialog.Builder(a)
            .setTitle("Prêt pour Claude")
            .setMessage(sb.toString())
            .setPositiveButton("Ouvrir dans Claude") { _, _ -> openInClaude(a, d.url) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun copyOnly(a: Activity, p: ClaudePayload) {
        val text = p.text
        if (text == null) {
            AlertDialog.Builder(a)
                .setTitle("Trop long pour le presse-papiers")
                .setMessage("Ce contenu dépasse ${ClaudeBundle.CLIP_MAX / 1000} k caractères ou comporte plusieurs parties. Partage plutôt les fichiers.")
                .setPositiveButton("Partager vers Claude") { _, _ -> shareTo(a, p.files, CLAUDE_PACKAGE) }
                .setNegativeButton("Fermer", null)
                .show()
            return
        }
        if (copy(a, text)) {
            Toast.makeText(a, "Copié : colle-le dans la conversation Claude ouverte.", Toast.LENGTH_LONG).show()
            openClaudeApp(a)
        }
    }

    private fun copy(a: Activity, text: String): Boolean = try {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Pour Claude", text))
        true
    } catch (e: Exception) {
        Toast.makeText(a, "Copie impossible : ${e.message}", Toast.LENGTH_LONG).show()
        false
    }

    /** Ouvre un lien claude.ai dans l'appli Claude si elle le prend en charge, sinon dans le navigateur. */
    fun openInClaude(a: Activity, url: String) {
        val uri = Uri.parse(url)
        val inApp = Intent(Intent.ACTION_VIEW, uri).setPackage(CLAUDE_PACKAGE)
        try {
            a.startActivity(inApp)
            return
        } catch (e: Exception) {
            // l'appli Claude n'ouvre pas ce lien : navigateur
        }
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(a, "Aucune appli pour ouvrir ce lien.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openClaudeApp(a: Activity) {
        val launch = a.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE) ?: return
        try {
            a.startActivity(launch)
        } catch (e: Exception) {
            // pas grave : le texte est copié
        }
    }

    /** Partage des fichiers ; [pkg] = appli visée directement (Claude), sinon choix de l'appli. */
    fun shareTo(a: Activity, files: List<File>, pkg: String?, mime: String = "text/markdown") {
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
        val clip = ClipData.newUri(a.contentResolver, "fichiers", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        if (pkg != null) {
            try {
                a.startActivity(Intent(intent).setPackage(pkg))
                return
            } catch (e: Exception) {
                Toast.makeText(a, "Appli Claude introuvable : choisis l'appli.", Toast.LENGTH_SHORT).show()
            }
        }
        try {
            a.startActivity(Intent.createChooser(intent, "Envoyer"))
        } catch (e: Exception) {
            Toast.makeText(a, "Partage impossible : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
