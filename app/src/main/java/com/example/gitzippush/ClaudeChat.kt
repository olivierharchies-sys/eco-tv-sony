package com.example.gitzippush

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dialogue de dépannage avec Claude, en copier-coller.
 * Claude répond par des commandes « @… » ; l'appli les exécute sur le dépôt et renvoie le strict nécessaire.
 * Un @PATCH / @WRITE / @DELETE crée un commit, puis l'appli suit la compilation et renvoie le résultat.
 */
object ChatProtocol {
    const val TEXT =
        "## Protocole (appli Envoi GitHub, copier-coller)\n" +
            "Réponds brièvement. S'il te manque une info, demande-la par commandes, une par ligne :\n" +
            "@FILE chemin [début-fin]   — fichier numéroté (ou plage de lignes)\n" +
            "@GREP \"regex\" [dossier]   — recherche dans le dépôt\n" +
            "@TREE [dossier] [profondeur]\n" +
            "@LOG [n] [\"regex\"]         — fin du journal en échec, ou lignes filtrées\n" +
            "@REPORT MOYEN|COMPLET\n" +
            "Pour corriger (un seul commit, puis compilation ; le résultat te revient) :\n" +
            "@PATCH suivi d'un bloc ```diff (diff unifié, chemins depuis la racine)\n" +
            "@WRITE chemin suivi d'un bloc ``` (fichier complet)\n" +
            "@DELETE chemin\n" +
            "Préfère @PATCH, petits hunks, contexte exact."

    const val MAX_OUT = 60_000
    const val MAX_FILE_LINES = 400
    const val MAX_GREP_FILES = 150
    const val MAX_GREP_HITS = 150
    const val MAX_TREE = 500
    private val TEXT_EXT = setOf(
        "kt", "kts", "java", "gradle", "xml", "yml", "yaml", "properties", "toml", "json", "md", "txt", "pro", "cfg", "sh"
    )

    fun isText(path: String) = path.substringAfterLast('.', "").lowercase() in TEXT_EXT
}

/** Une commande lue dans la réponse de Claude. [body] : contenu du bloc qui suit (@PATCH, @WRITE). */
class ChatCommand(val name: String, val args: List<String>, val body: String?)

/** Une bulle du dialogue. [fromApp] : produite par l'appli (à copier vers Claude). */
class ChatMessage(val fromApp: Boolean, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("a", fromApp).put("t", text)
}

class ChatSession(
    val repo: String?,
    val branch: String?,
    @Volatile var ref: String?,
    @Volatile var runId: Long?,
    @Volatile var fullReport: String
) {
    private val cache = HashMap<String, String?>()
    private var treeCache: Pair<String, List<Pair<String, String>>>? = null

    // ------------------------------------------------------------------ lecture des commandes

    companion object {
        private val HEADER_REPO = Regex("(?m)^- Dépôt : (\\S+)")
        private val HEADER_BRANCH = Regex("(?m)^- Branche : (\\S+)")
        private val HEADER_SHA = Regex("(?m)^- Commit : ([0-9a-f]{7,40})")
        private val RUN_LINK = Regex("github\\.com/[^/\\s]+/[^/\\s]+/actions/runs/(\\d+)")
        private val STAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z ?")
        private val CMD = Regex("^\\s*@([A-Za-z]+)\\b(.*)$")
        private val NAMES = setOf("FILE", "GREP", "TREE", "LOG", "REPORT", "PATCH", "WRITE", "DELETE")

        fun fromReport(text: String) = ChatSession(
            HEADER_REPO.find(text)?.groupValues?.get(1),
            HEADER_BRANCH.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "null" },
            HEADER_SHA.find(text)?.groupValues?.get(1),
            RUN_LINK.find(text)?.groupValues?.get(1)?.toLongOrNull(),
            text
        )

        /** Découpe les arguments, guillemets respectés. */
        fun splitArgs(s: String): List<String> {
            val out = ArrayList<String>()
            val m = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)'|(\\S+)").findAll(s)
            for (x in m) {
                out.add(
                    when {
                        x.groups[1] != null -> x.groupValues[1].replace("\\\"", "\"")
                        x.groups[2] != null -> x.groupValues[2]
                        else -> x.groupValues[3]
                    }
                )
            }
            return out
        }

        /** Commandes contenues dans le texte collé. */
        fun parse(text: String): List<ChatCommand> {
            val lines = text.replace("\r\n", "\n").split("\n")
            val out = ArrayList<ChatCommand>()
            var i = 0
            while (i < lines.size) {
                val m = CMD.find(lines[i])
                val name = m?.groupValues?.get(1)?.uppercase()
                if (m == null || name !in NAMES) {
                    i++
                    continue
                }
                val args = splitArgs(m.groupValues[2].trim().trim('`'))
                i++
                var body: String? = null
                if (name == "PATCH" || name == "WRITE") {
                    // saute les lignes vides jusqu'au bloc
                    var j = i
                    while (j < lines.size && lines[j].isBlank()) j++
                    val open = if (j < lines.size) Regex("^\\s*(`{3,}|~{3,})").find(lines[j]) else null
                    val sb = StringBuilder()
                    if (open != null) {
                        val fence = open.groupValues[1]
                        j++
                        while (j < lines.size && lines[j].trim() != fence) {
                            sb.append(lines[j]).append('\n')
                            j++
                        }
                        i = j + 1
                    } else {
                        // pas de bloc (texte copié sans Markdown) : jusqu'à la prochaine commande
                        while (j < lines.size && CMD.find(lines[j])?.groupValues?.get(1)?.uppercase() !in NAMES) {
                            sb.append(lines[j]).append('\n')
                            j++
                        }
                        i = j
                    }
                    body = sb.toString()
                }
                out.add(ChatCommand(name!!, args, body))
            }
            return out
        }

        fun load(f: File): MutableList<ChatMessage> {
            val out = ArrayList<ChatMessage>()
            try {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (k in 0 until arr.length()) {
                    val o = arr.getJSONObject(k)
                    out.add(ChatMessage(o.optBoolean("a"), o.optString("t")))
                }
            } catch (e: Exception) {
                // nouveau dialogue
            }
            return out
        }

        fun save(f: File, list: List<ChatMessage>) {
            val arr = JSONArray()
            for (m in list) arr.put(m.toJson())
            try {
                f.writeText(arr.toString(), Charsets.UTF_8)
            } catch (e: Exception) {
                // facultatif
            }
        }

        /** Fichier de dialogue associé à un rapport. */
        fun fileFor(ctx: Context, report: File): File =
            File(File(ctx.filesDir, "chats").also { it.mkdirs() }, report.name.removeSuffix(".md") + ".json")
    }

    /** Premier message : rapport COURT + protocole. */
    fun firstMessage(): String = ErrorReport.render(fullReport, ErrorReport.Level.SHORT, null, chat = true)

    // ------------------------------------------------------------------ exécution

    class Outcome(val text: String, val commitSha: String?)

    /** Exécute les commandes. Hors fil UI. */
    fun execute(api: GitHubApi, cmds: List<ChatCommand>): Outcome {
        val sb = StringBuilder()
        val changes = LinkedHashMap<String, String?>()
        val changeErrors = StringBuilder()
        for (c in cmds) {
            when (c.name) {
                "PATCH", "WRITE", "DELETE" -> try {
                    collectChange(api, c, changes)
                } catch (e: Exception) {
                    changeErrors.append("### @${c.name} ${c.args.joinToString(" ")}\n✗ ").append(e.message).append("\n\n")
                }
                else -> {
                    sb.append("### @").append(c.name)
                    if (c.args.isNotEmpty()) sb.append(' ').append(c.args.joinToString(" "))
                    sb.append('\n')
                    val r = try {
                        run(api, c)
                    } catch (e: Exception) {
                        "✗ " + (e.message ?: e.javaClass.simpleName)
                    }
                    sb.append(r.trimEnd()).append("\n\n")
                }
            }
        }
        var newSha: String? = null
        if (changeErrors.isNotEmpty()) {
            sb.append(changeErrors).append("Aucun commit : corrige le(s) bloc(s) ci-dessus et renvoie TOUTES les modifications.\n")
        } else if (changes.isNotEmpty()) {
            try {
                val sha = commit(api, changes)
                newSha = sha
                sb.append("✓ Commit ${sha.take(7)} sur ${branch ?: "?"} : ")
                    .append(changes.keys.joinToString(", ")).append(". Compilation en cours…\n")
            } catch (e: Exception) {
                sb.append("✗ Commit impossible : ").append(e.message).append('\n')
            }
        }
        var out = sb.toString().trimEnd()
        if (out.isEmpty()) out = "(aucune commande reconnue : les commandes commencent par @ en début de ligne)"
        if (out.length > ChatProtocol.MAX_OUT) out = out.take(ChatProtocol.MAX_OUT) + "\n[… coupé : demande une plage plus étroite]"
        return Outcome(out, newSha)
    }

    private fun needRepo(): String = repo ?: throw PushException("dépôt inconnu (rapport sans dépôt)")
    private fun needRef(): String = ref ?: branch ?: throw PushException("commit inconnu")

    private fun run(api: GitHubApi, c: ChatCommand): String = when (c.name) {
        "FILE" -> cmdFile(api, c.args)
        "GREP" -> cmdGrep(api, c.args)
        "TREE" -> cmdTree(api, c.args)
        "LOG" -> cmdLog(api, c.args)
        "REPORT" -> {
            val lv = if (c.args.firstOrNull()?.uppercase()?.startsWith("C") == true) ErrorReport.Level.FULL else ErrorReport.Level.MEDIUM
            ErrorReport.render(fullReport, lv, null, chat = true, withAsk = false)
        }
        else -> "commande inconnue"
    }

    private fun cmdFile(api: GitHubApi, args: List<String>): String {
        val path = clean(args.firstOrNull() ?: return "chemin manquant")
        val text = fileAt(api, path, needRef()) ?: return "introuvable : $path" + suggest(api, path)
        val lines = splitLines(text)
        val range = args.getOrNull(1)?.let { Regex("(\\d+)\\s*[-:]\\s*(\\d+)").find(it) }
        val (from, to) = if (range != null) {
            range.groupValues[1].toInt().coerceAtLeast(1) to range.groupValues[2].toInt()
        } else {
            1 to minOf(lines.size, ChatProtocol.MAX_FILE_LINES)
        }
        val end = to.coerceAtMost(lines.size)
        if (lines.isEmpty()) return "(fichier vide)"
        if (from > end) return "plage hors fichier ($path : ${lines.size} lignes)"
        val w = end.toString().length
        val sb = StringBuilder("```").append(lang(path)).append('\n')
        for (n in from..end) sb.append(n.toString().padStart(w)).append("| ").append(lines[n - 1]).append('\n')
        sb.append("```")
        if (range == null && lines.size > end) sb.append("\n[${lines.size} lignes : demande la suite par plage]")
        return sb.toString()
    }

    private fun cmdGrep(api: GitHubApi, args: List<String>): String {
        val pat = args.firstOrNull() ?: return "motif manquant"
        val re = try {
            Regex(pat)
        } catch (e: Exception) {
            Regex(Regex.escape(pat))
        }
        val dir = args.getOrNull(1)?.let { clean(it).trimEnd('/') + "/" }.orEmpty()
        val files = tree(api).map { it.first }
            .filter { (dir.isEmpty() || it.startsWith(dir)) && ChatProtocol.isText(it) && !it.contains("/build/") }
        val sb = StringBuilder()
        var hits = 0
        for (p in files.take(ChatProtocol.MAX_GREP_FILES)) {
            val text = fileAt(api, p, needRef()) ?: continue
            for ((i, l) in splitLines(text).withIndex()) {
                if (!re.containsMatchIn(l)) continue
                sb.append(p).append(':').append(i + 1).append(": ").append(l.trim().take(200)).append('\n')
                if (++hits >= ChatProtocol.MAX_GREP_HITS) {
                    sb.append("[… limite de $hits résultats]")
                    return sb.toString()
                }
            }
        }
        if (files.size > ChatProtocol.MAX_GREP_FILES) sb.append("[${files.size - ChatProtocol.MAX_GREP_FILES} fichiers non lus : précise le dossier]\n")
        return if (hits == 0) "aucun résultat (${minOf(files.size, ChatProtocol.MAX_GREP_FILES)} fichiers lus)" else sb.toString()
    }

    private fun cmdTree(api: GitHubApi, args: List<String>): String {
        val dir = args.firstOrNull { it.toIntOrNull() == null }?.let { clean(it).trimEnd('/') + "/" }?.takeIf { it != "/" }.orEmpty()
        val depth = args.firstNotNullOfOrNull { it.toIntOrNull() } ?: 99
        val paths = tree(api).map { it.first }.filter { dir.isEmpty() || it.startsWith(dir) }
        if (paths.isEmpty()) return "aucun fichier sous « $dir »"
        val shown = java.util.TreeMap<String, Int>()
        for (p in paths) {
            val seg = p.removePrefix(dir).split('/')
            if (seg.size > depth) {
                val k = seg.take(depth).joinToString("/") + "/"
                shown[k] = (shown[k] ?: 0) + 1
            } else {
                shown[p.removePrefix(dir)] = 0
            }
        }
        val sb = StringBuilder()
        if (dir.isNotEmpty()) sb.append("(sous $dir)\n")
        for ((k, n) in shown.entries.take(ChatProtocol.MAX_TREE)) {
            sb.append(k)
            if (n > 0) sb.append("  ($n fichiers)")
            sb.append('\n')
        }
        if (shown.size > ChatProtocol.MAX_TREE) sb.append("[… ${shown.size - ChatProtocol.MAX_TREE} autres : précise le dossier]")
        return sb.toString()
    }

    private fun cmdLog(api: GitHubApi, args: List<String>): String {
        val repo = needRepo()
        val id = runId ?: return "aucune compilation associée"
        val n = args.firstNotNullOfOrNull { it.toIntOrNull() }?.coerceIn(10, 600) ?: 80
        val pat = args.firstOrNull { it.toIntOrNull() == null }?.let {
            try {
                Regex(it)
            } catch (e: Exception) {
                Regex(Regex.escape(it))
            }
        }
        val jobs = api.get("/repos/$repo/actions/runs/$id/jobs?per_page=50")
        val arr = jobs.json?.optJSONArray("jobs") ?: return "jobs illisibles (HTTP ${jobs.code})"
        val sb = StringBuilder()
        for (k in 0 until arr.length()) {
            val j = arr.getJSONObject(k)
            val c = j.optString("conclusion")
            if (c == "success" || c == "skipped") continue
            val logs = api.get("/repos/$repo/actions/jobs/${j.optLong("id")}/logs")
            if (!logs.ok) {
                sb.append("job « ${j.optString("name")} » : journal illisible (HTTP ${logs.code})\n")
                continue
            }
            val lines = logs.text.lines().map { STAMP.replace(it.trimEnd('\r'), "").take(300) }
            sb.append("job « ${j.optString("name")} »\n```\n")
            if (pat != null) {
                val keep = java.util.TreeSet<Int>()
                for ((i, l) in lines.withIndex()) if (pat.containsMatchIn(l)) for (x in i - 2..i + 2) if (x in lines.indices) keep.add(x)
                var prev = -10
                for (x in keep.take(n)) {
                    if (prev >= 0 && x != prev + 1) sb.append("…\n")
                    sb.append(x + 1).append(": ").append(lines[x]).append('\n')
                    prev = x
                }
                if (keep.isEmpty()) sb.append("(aucune ligne)\n")
            } else {
                val err = lines.indexOfFirst { it.contains("##[error]Process completed") }
                val end = if (err >= 0) err + 1 else lines.size
                for (l in lines.subList((end - n).coerceAtLeast(0), end)) sb.append(l).append('\n')
            }
            sb.append("```\n")
        }
        return sb.toString().ifEmpty { "aucun job en échec" }
    }

    // ------------------------------------------------------------------ modifications

    private fun collectChange(api: GitHubApi, c: ChatCommand, changes: MutableMap<String, String?>) {
        when (c.name) {
            "DELETE" -> {
                val p = clean(c.args.firstOrNull() ?: throw PushException("chemin manquant"))
                if (current(api, p, changes) == null) throw PushException("introuvable : $p")
                changes[p] = null
            }
            "WRITE" -> {
                val p = clean(c.args.firstOrNull() ?: throw PushException("chemin manquant"))
                val body = c.body ?: throw PushException("bloc de code manquant")
                if (body.isBlank()) throw PushException("bloc vide")
                changes[p] = body
            }
            "PATCH" -> {
                val diff = c.body ?: throw PushException("bloc diff manquant")
                val files = UnifiedDiff.parse(diff)
                if (files.isEmpty()) throw PushException("diff illisible (lignes --- / +++ / @@ attendues)")
                for (f in files) {
                    if (f.deleted) {
                        changes[f.oldPath!!] = null
                        continue
                    }
                    val path = f.newPath ?: throw PushException("chemin manquant dans le diff")
                    val base = if (f.oldPath == null) "" else {
                        current(api, f.oldPath, changes) ?: throw PushException("introuvable : ${f.oldPath}" + suggest(api, f.oldPath))
                    }
                    changes[path] = UnifiedDiff.apply(base, f, path)
                    if (f.oldPath != null && f.oldPath != path) changes[f.oldPath] = null
                }
            }
        }
    }

    /** Contenu actuel d'un fichier, modifications en attente comprises. */
    private fun current(api: GitHubApi, path: String, changes: Map<String, String?>): String? =
        if (changes.containsKey(path)) changes[path] else fileAt(api, path, needRef())

    /** Un commit unique (API Git Data), puis mise à jour de la branche. Renvoie le SHA. */
    private fun commit(api: GitHubApi, changes: Map<String, String?>): String {
        val repo = needRepo()
        val br = branch ?: throw PushException("branche inconnue")
        val base = "/repos/$repo"
        val refReply = api.get("$base/git/ref/heads/${Uri.encode(br, "/")}")
        val head = refReply.json?.optJSONObject("object")?.optString("sha")
            ?: throw GitHubApi.explain(refReply, "Lecture de la branche")
        val commitReply = api.get("$base/git/commits/$head")
        val baseTree = commitReply.json?.optJSONObject("tree")?.optString("sha")
            ?: throw GitHubApi.explain(commitReply, "Lecture du commit")
        val modes = tree(api).toMap()
        val entries = JSONArray()
        for ((p, content) in changes) {
            val e = JSONObject().put("path", p).put("mode", modes[p] ?: "100644").put("type", "blob")
            if (content == null) e.put("sha", JSONObject.NULL) else e.put("content", content)
            entries.put(e)
        }
        val treeReply = api.call("POST", "$base/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries))
        val newTree = treeReply.json?.optString("sha")?.takeIf { treeReply.ok } ?: throw GitHubApi.explain(treeReply, "Création de l'arbre")
        val msg = "Correction via dialogue Claude : " + changes.keys.joinToString(", ") { it.substringAfterLast('/') }
        val cReply = api.call(
            "POST", "$base/git/commits",
            JSONObject().put("message", msg.take(200)).put("tree", newTree).put("parents", JSONArray().put(head))
        )
        val sha = cReply.json?.optString("sha")?.takeIf { cReply.ok } ?: throw GitHubApi.explain(cReply, "Création du commit")
        val up = api.call("PATCH", "$base/git/refs/heads/${Uri.encode(br, "/")}", JSONObject().put("sha", sha).put("force", false))
        if (!up.ok) throw GitHubApi.explain(up, "Mise à jour de la branche")
        ref = sha
        cache.clear()
        treeCache = null
        return sha
    }

    // ------------------------------------------------------------------ suivi de compilation

    /**
     * Attend la compilation du commit [sha]. Renvoie le message à transmettre à Claude
     * (succès, ou rapport court de l'échec). [alive] : false pour abandonner.
     */
    fun waitBuild(ctx: Context, api: GitHubApi, sha: String, alive: () -> Boolean, progress: (String) -> Unit): String? {
        val repo = needRepo()
        val start = System.currentTimeMillis()
        var seen = false
        while (alive()) {
            val elapsed = (System.currentTimeMillis() - start) / 1000
            if (!seen && elapsed > 150) return "Aucune compilation déclenchée par le commit ${sha.take(7)} (workflow absent ou filtré ?)."
            if (elapsed > 40 * 60) return "Compilation toujours en cours après 40 min : relance le suivi plus tard."
            val r = api.get("/repos/$repo/actions/runs?head_sha=$sha&per_page=10")
            val runs = r.json?.optJSONArray("workflow_runs")
            if (runs != null && runs.length() > 0) {
                seen = true
                var pending = false
                var failed: JSONObject? = null
                for (k in 0 until runs.length()) {
                    val run = runs.getJSONObject(k)
                    if (run.optString("status") != "completed") pending = true
                    else if (run.optString("conclusion") !in setOf("success", "skipped", "neutral")) failed = failed ?: run
                }
                if (!pending) {
                    if (failed == null) return "✅ Compilation réussie (commit ${sha.take(7)}). Problème résolu."
                    runId = failed.optLong("id")
                    val report = try {
                        ErrorReport.forRun(api, repo, failed)
                    } catch (e: Exception) {
                        null
                    }
                    if (report == null) return "✗ Compilation échouée (${failed.optString("conclusion")}) : ${failed.optString("html_url")}"
                    fullReport = report
                    try {
                        Reports.save(ctx.applicationContext, repo.substringAfter('/') + "_" + sha.take(7), report)
                    } catch (e: Exception) {
                        // historique facultatif
                    }
                    return "✗ Nouvel échec (commit ${sha.take(7)}).\n\n" +
                        ErrorReport.render(report, ErrorReport.Level.SHORT, null, chat = true, withAsk = false)
                }
                progress("Compilation en cours… ${elapsed / 60} min ${elapsed % 60} s")
            } else {
                progress("En attente du démarrage de la compilation…")
            }
            try {
                Thread.sleep(15_000)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return null
    }

    // ------------------------------------------------------------------ outils

    private fun clean(p: String) = p.trim().trim('`', '"', '\'').removePrefix("./").removePrefix("/")

    fun fileAt(api: GitHubApi, path: String, sha: String): String? {
        val key = "$sha:$path"
        if (cache.containsKey(key)) return cache[key]
        val r = api.get("/repos/${needRepo()}/contents/${Uri.encode(path, "/")}?ref=$sha")
        val text = if (!r.ok) null else {
            val o = r.json
            val content = o?.optString("content").orEmpty()
            if (o == null || o.optString("type") != "file") null
            else if (content.isNotEmpty()) decode(content)
            else blob(api, o.optString("sha"))
        }
        cache[key] = text
        return text
    }

    /** Fichiers > 1 Mo : passer par le blob. */
    private fun blob(api: GitHubApi, sha: String): String? {
        if (sha.isEmpty()) return null
        val r = api.get("/repos/${needRepo()}/git/blobs/$sha")
        return r.json?.optString("content")?.takeIf { r.ok && it.isNotEmpty() }?.let { decode(it) }
    }

    private fun decode(b64: String): String? = try {
        String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** (chemin, mode) des fichiers du commit courant. */
    private fun tree(api: GitHubApi): List<Pair<String, String>> {
        val sha = needRef()
        treeCache?.let { if (it.first == sha) return it.second }
        val r = api.get("/repos/${needRepo()}/git/trees/$sha?recursive=1")
        val items = r.json?.optJSONArray("tree") ?: throw GitHubApi.explain(r, "Lecture de l'arborescence")
        val out = ArrayList<Pair<String, String>>()
        for (k in 0 until items.length()) {
            val n = items.getJSONObject(k)
            if (n.optString("type") == "blob") out.add(n.optString("path") to n.optString("mode", "100644"))
        }
        treeCache = sha to out
        return out
    }

    /** Fichiers de même nom, si le chemin donné n'existe pas. */
    private fun suggest(api: GitHubApi, path: String): String = try {
        val name = path.substringAfterLast('/')
        val same = tree(api).map { it.first }.filter { it.substringAfterLast('/') == name }.take(5)
        if (same.isEmpty()) "" else " — même nom : " + same.joinToString(", ")
    } catch (e: Exception) {
        ""
    }

    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.replace("\r\n", "\n").removeSuffix("\n").split("\n")

    private fun lang(p: String) = when (p.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "yml", "yaml" -> "yaml"
        "json" -> "json"
        else -> ""
    }
}

/** Diff unifié : lecture et application tolérante (position décalée, espaces de fin). */
object UnifiedDiff {
    class Hunk(val oldStart: Int, val lines: List<String>)
    class FileDiff(val oldPath: String?, val newPath: String?, val hunks: List<Hunk>) {
        val deleted get() = newPath == null && oldPath != null
    }

    private val HUNK = Regex("^@@ -(\\d+)(?:,\\d+)? \\+\\d+(?:,\\d+)? @@")

    private fun path(raw: String): String? {
        val p = raw.substring(4).substringBefore('\t').trim()
        if (p == "/dev/null") return null
        return if (p.startsWith("a/") || p.startsWith("b/")) p.substring(2) else p
    }

    fun parse(diff: String): List<FileDiff> {
        val lines = diff.replace("\r\n", "\n").split("\n")
        val out = ArrayList<FileDiff>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith("--- ") || i + 1 >= lines.size || !lines[i + 1].startsWith("+++ ")) {
                i++
                continue
            }
            val oldP = path(lines[i])
            val newP = path(lines[i + 1])
            i += 2
            val hunks = ArrayList<Hunk>()
            while (i < lines.size && !(lines[i].startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ "))) {
                val m = HUNK.find(lines[i])
                if (m == null) {
                    i++
                    continue
                }
                val start = m.groupValues[1].toInt()
                i++
                val body = ArrayList<String>()
                while (i < lines.size && !lines[i].startsWith("@@") &&
                    !(lines[i].startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ "))
                ) {
                    val l = lines[i]
                    when {
                        l.startsWith("\\") -> {}
                        l.isEmpty() -> body.add(" ")
                        l[0] == ' ' || l[0] == '+' || l[0] == '-' -> body.add(l)
                        else -> body.add(" $l") // espace initial perdu au copier-coller
                    }
                    i++
                }
                // lignes vides finales = fin du bloc, pas du contexte
                while (body.isNotEmpty() && body.last() == " ") body.removeAt(body.size - 1)
                hunks.add(Hunk(start, body))
            }
            out.add(FileDiff(oldP, newP, hunks))
        }
        return out
    }

    fun apply(base: String, f: FileDiff, path: String): String {
        val text = base.replace("\r\n", "\n")
        val endsNl = text.isEmpty() || text.endsWith("\n")
        val lines: ArrayList<String> = if (text.isEmpty()) ArrayList() else ArrayList(text.removeSuffix("\n").split("\n"))
        var offset = 0
        var floor = 0
        for ((n, h) in f.hunks.withIndex()) {
            val old = h.lines.filter { it[0] != '+' }.map { it.substring(1) }
            val new = h.lines.filter { it[0] != '-' }.map { it.substring(1) }
            val hint = (h.oldStart - 1 + offset).coerceAtLeast(0)
            val at = if (old.isEmpty()) {
                hint.coerceAtMost(lines.size)
            } else {
                find(lines, old, hint, floor) { a, b -> a == b }
                    ?: find(lines, old, hint, floor) { a, b -> a.trimEnd() == b.trimEnd() }
                    ?: find(lines, old, hint, 0) { a, b -> a.trim() == b.trim() }
                    ?: throw PushException(
                        "$path : hunk ${n + 1} (@@ -${h.oldStart}) ne correspond pas au fichier. Lignes actuelles :\n" +
                            around(lines, h.oldStart - 1)
                    )
            }
            for (k in old.indices) lines.removeAt(at)
            lines.addAll(at, new)
            offset += new.size - old.size
            floor = at + new.size
        }
        return lines.joinToString("\n") + (if (endsNl && lines.isNotEmpty()) "\n" else "")
    }

    /** Position de [old] dans [lines], la plus proche de [hint], à partir de [floor]. */
    private fun find(lines: List<String>, old: List<String>, hint: Int, floor: Int, eq: (String, String) -> Boolean): Int? {
        var best: Int? = null
        for (s in floor..lines.size - old.size) {
            var ok = true
            for (k in old.indices) if (!eq(lines[s + k], old[k])) {
                ok = false
                break
            }
            if (ok && (best == null || Math.abs(s - hint) < Math.abs(best - hint))) best = s
        }
        return best
    }

    private fun around(lines: List<String>, center: Int): String {
        val from = (center - 6).coerceAtLeast(0)
        val to = (center + 6).coerceAtMost(lines.size - 1)
        if (from > to) return "(fichier de ${lines.size} lignes)"
        val sb = StringBuilder("```\n")
        for (k in from..to) sb.append(k + 1).append("| ").append(lines[k]).append('\n')
        return sb.append("```").toString()
    }
}
