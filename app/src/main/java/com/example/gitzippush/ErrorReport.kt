package com.example.gitzippush

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeSet

/** Échec d'une compilation GitHub Actions : garde la compilation concernée pour le rapport. */
class BuildFailedException(message: String, val run: JSONObject) : PushException(message)

/**
 * Rapport d'erreur en Markdown, prêt à coller dans une conversation Claude.
 * Pour un échec de compilation : jobs, étapes, annotations, lignes d'erreur et fin du journal, fichier du workflow.
 */
object ErrorReport {
    /** Taille maximale du fichier de rapport. */
    private const val MAX_CHARS = 800_000
    private const val FULL_LOG_CHARS = 250_000
    private const val MAX_CITED_FILES = 15
    private const val FULL_FILE_LINES = 400
    private const val CONTEXT_LINES = 30
    private const val SHORT_CONTEXT = 8
    private const val MAX_TREE = 2500
    private const val MAX_PATCH = 4000
    private const val MAX_DIFF_CHARS = 80_000
    private const val TAIL_LINES = 150
    private const val MAX_ERROR_LINES = 250
    private const val MAX_LINE = 400
    private const val MAX_JOBS = 3

    private val reportedRuns = HashSet<Long>()
    private val CITED = Regex("/home/runner/work/[^/\\s]+/[^/\\s]+/([^\\s:'\"()\\[\\]]+\\.[A-Za-z]{1,10})(?::(\\d+))?")
    private val CONFIG_FILES = listOf(
        "settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "gradle.properties",
        "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
        "app/build.gradle.kts", "app/build.gradle", "app/proguard-rules.pro", "app/src/main/AndroidManifest.xml"
    )
    private val STAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z ?")
    private val ERROR_LINE = Regex(
        "^e: |error:|Error:|ERROR|FAILURE|FAILED|What went wrong|Exception|Unresolved reference|" +
            "##\\[error]|Execution failed|Caused by|Could not resolve|Could not find"
    )

    private const val ASK_BUILD =
        "## Demande\n" +
            "Cette compilation GitHub Actions (projet Android, Gradle/Kotlin) a échoué. " +
            "Trouve la cause exacte à partir des éléments ci-dessous, puis donne la correction : " +
            "chemin du fichier et modification à faire. Le rapport contient : étapes, lignes d'erreur, " +
            "fichiers cités, modifications depuis la dernière compilation réussie, configuration Gradle, " +
            "workflow, arborescence et journal complet.\n\n"

    private const val ASK_APP =
        "## Demande\n" +
            "L'application Android « Envoi GitHub » a rencontré cette erreur en dialoguant avec GitHub. " +
            "Explique la cause et ce qu'il faut corriger (réglage, jeton, dépôt ou code de l'appli).\n\n"

    // ------------------------------------------------------------------ échec de compilation

    /** Rapport d'une compilation échouée. Null si cette compilation a déjà été rapportée. Hors fil UI. */
    fun forRun(api: GitHubApi, repo: String, run: JSONObject): String? {
        val runId = run.optLong("id")
        synchronized(reportedRuns) { if (!reportedRuns.add(runId)) return null }
        val base = "/repos/$repo"
        val sha = run.optString("head_sha")
        val sb = StringBuilder()
        sb.append("# Rapport d'erreur — Envoi GitHub\n\n")
        sb.append("- Type : échec de compilation GitHub Actions\n")
        sb.append("- Dépôt : $repo\n")
        sb.append("- Branche : ${run.optString("head_branch")}\n")
        sb.append("- Commit : $sha\n")
        val commitMsg = run.optJSONObject("head_commit")?.optString("message").orEmpty().lineSequence().firstOrNull()
        if (!commitMsg.isNullOrBlank()) sb.append("- Message du commit : $commitMsg\n")
        sb.append("- Workflow : ${run.optString("name")} (${run.optString("path")})\n")
        sb.append("- Résultat : ${run.optString("conclusion")}, tentative ${run.optInt("run_attempt", 1)}\n")
        sb.append("- Date : ${run.optString("updated_at")}\n")
        sb.append("- Lien : ${run.optString("html_url")}\n\n")
        sb.append(ASK_BUILD)

        // --- jobs et étapes
        val failedJobs = ArrayList<JSONObject>()
        sb.append("## Jobs et étapes\n")
        val jobsReply = api.get("$base/actions/runs/$runId/jobs?per_page=50")
        val jobs = jobsReply.json?.optJSONArray("jobs")
        when {
            !jobsReply.ok -> sb.append("(lecture impossible : HTTP ${jobsReply.code} ${jobsReply.message})\n")
            jobs == null || jobs.length() == 0 ->
                sb.append("Aucun job : le workflow n'a pas pu démarrer (fichier YAML invalide ou mal placé ?).\n")
            else -> for (i in 0 until jobs.length()) {
                val j = jobs.getJSONObject(i)
                val c = j.optString("conclusion").ifEmpty { j.optString("status") }
                sb.append("- Job « ${j.optString("name")} » : $c\n")
                if (c != "success" && c != "skipped") failedJobs.add(j)
                val steps = j.optJSONArray("steps") ?: continue
                for (k in 0 until steps.length()) {
                    val s = steps.getJSONObject(k)
                    val sc = s.optString("conclusion").ifEmpty { s.optString("status") }
                    val mark = when (sc) {
                        "success" -> "✓"
                        "skipped" -> "–"
                        else -> "✗"
                    }
                    sb.append("  - $mark ${s.optInt("number")}. ${s.optString("name")} ($sc)\n")
                }
            }
        }
        sb.append('\n')

        // --- détails des jobs en échec
        val fullLogs = LinkedHashMap<String, List<String>>()
        val cited = LinkedHashMap<String, TreeSet<Int>>()
        for (j in failedJobs.take(MAX_JOBS)) {
            val id = j.optLong("id")
            val name = j.optString("name")

            val ann = api.get("$base/check-runs/$id/annotations?per_page=50")
            val list = try {
                if (ann.ok) JSONArray(ann.text) else null
            } catch (e: Exception) {
                null
            }
            if (list != null && list.length() > 0) {
                sb.append("## Annotations GitHub — job « $name »\n")
                for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i)
                    val where = a.optString("path") + (if (a.optInt("start_line") > 0) ":" + a.optInt("start_line") else "")
                    sb.append("- [${a.optString("annotation_level")}] $where — ${clip(a.optString("message"))}\n")
                }
                sb.append('\n')
            }

            val logs = api.get("$base/actions/jobs/$id/logs")
            if (!logs.ok) {
                sb.append("## Journal — job « $name »\n(lecture impossible : HTTP ${logs.code} ${logs.message})\n\n")
                continue
            }
            val lines = logs.text.lines().map { STAMP.replace(it.trimEnd('\r'), "") }
            fullLogs[name] = lines
            collectCited(lines, cited)
            sb.append("## Lignes d'erreur — job « $name »\n```\n").append(errorLines(lines)).append("\n```\n\n")
            sb.append("## Fin du journal — job « $name »\n```\n").append(tail(lines)).append("\n```\n\n")
        }
        if (failedJobs.size > MAX_JOBS) sb.append("(${failedJobs.size - MAX_JOBS} autre(s) job(s) en échec non détaillé(s))\n\n")

        // --- fichiers cités dans les erreurs
        if (sha.isNotEmpty() && cited.isNotEmpty()) {
            val short = StringBuilder("## Extraits des fichiers cités (±$SHORT_CONTEXT lignes, « >| » = ligne en erreur)\n")
            val whole = StringBuilder("## Fichiers cités (complets ou larges extraits)\n")
            for ((p, nums) in cited.entries.take(MAX_CITED_FILES)) {
                val text = fileAt(api, base, p, sha)
                if (text == null) {
                    short.append("### $p\n(introuvable dans le dépôt : fichier généré ou supprimé)\n\n")
                    continue
                }
                val title = "### $p" + (if (nums.isNotEmpty()) " — lignes ${nums.joinToString(", ")}" else "") + "\n```" + lang(p) + "\n"
                short.append(title).append(excerpt(text, nums, SHORT_CONTEXT, 0)).append("\n```\n\n")
                whole.append(title).append(excerpt(text, nums, CONTEXT_LINES, FULL_FILE_LINES)).append("\n```\n\n")
            }
            if (cited.size > MAX_CITED_FILES) short.append("(${cited.size - MAX_CITED_FILES} autre(s) fichier(s) cité(s))\n\n")
            sb.append(short).append(whole)
        }

        // --- modifications depuis la dernière compilation réussie
        if (sha.isNotEmpty()) {
            try {
                appendDiffSinceSuccess(api, base, run, sha, sb)
            } catch (e: Exception) {
                sb.append("## Fichiers modifiés\n(lecture impossible : ${e.javaClass.simpleName})\n\n")
            }
        }

        // --- configuration Gradle / Android
        if (sha.isNotEmpty()) {
            val found = ArrayList<Pair<String, String>>()
            for (p in CONFIG_FILES) fileAt(api, base, p, sha)?.let { found.add(p to it) }
            if (found.isNotEmpty()) {
                sb.append("## Fichiers de configuration\n")
                for ((p, t) in found) {
                    sb.append("### $p\n```").append(lang(p)).append('\n').append(t.take(20_000).trimEnd()).append("\n```\n\n")
                }
            }
        }

        // --- fichier du workflow
        val path = run.optString("path").substringBefore('@')
        if (path.startsWith(".github/") && sha.isNotEmpty()) {
            val wf = api.get("$base/contents/$path?ref=$sha")
            val content = wf.json?.optString("content").orEmpty()
            if (wf.ok && content.isNotEmpty()) {
                val text = try {
                    String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) {
                    ""
                }
                if (text.isNotEmpty()) {
                    sb.append("## Fichier du workflow ($path)\n```yaml\n").append(text.take(6000).trimEnd()).append("\n```\n\n")
                }
            }
        }

        // --- arborescence du dépôt
        if (sha.isNotEmpty()) {
            val tree = api.get("$base/git/trees/$sha?recursive=1")
            val items = tree.json?.optJSONArray("tree")
            if (tree.ok && items != null) {
                val paths = ArrayList<String>()
                for (i in 0 until items.length()) {
                    val node = items.getJSONObject(i)
                    if (node.optString("type") == "blob") paths.add(node.optString("path"))
                }
                sb.append("## Arborescence du dépôt (${paths.size} fichiers)\n```\n")
                sb.append(paths.take(MAX_TREE).joinToString("\n"))
                if (paths.size > MAX_TREE) sb.append("\n[… ${paths.size - MAX_TREE} autres]")
                if (tree.json?.optBoolean("truncated") == true) sb.append("\n[liste tronquée par GitHub]")
                sb.append("\n```\n\n")
            }
        }

        // --- journaux complets
        for ((name, lines) in fullLogs) {
            val all = lines.joinToString("\n")
            sb.append("## Journal complet — job « $name »\n```\n")
            if (all.length > FULL_LOG_CHARS) {
                sb.append("[… début omis]\n").append(all.takeLast(FULL_LOG_CHARS))
            } else {
                sb.append(all)
            }
            sb.append("\n```\n\n")
        }
        return finish(sb)
    }

    // ------------------------------------------------------------------ erreur de l'appli

    /** Rapport d'une erreur rencontrée par l'appli (envoi, test, import…). */
    fun forError(ctx: Context, action: String, repo: String?, e: Throwable, appLog: String): String {
        val sb = StringBuilder()
        sb.append("# Rapport d'erreur — Envoi GitHub\n\n")
        sb.append("- Type : erreur de l'application\n")
        sb.append("- Action : $action\n")
        if (!repo.isNullOrBlank()) sb.append("- Dépôt : $repo\n")
        sb.append("- Date : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}\n")
        sb.append("- Appli : ${appVersion(ctx)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
        sb.append(ASK_APP)
        sb.append("## Message\n").append(e.message ?: e.javaClass.name).append("\n\n")
        val detail = (e as? PushException)?.detail
        if (!detail.isNullOrBlank()) sb.append("## Réponse brute de GitHub\n```\n").append(detail).append("\n```\n\n")
        if (e !is PushException) {
            sb.append("## Trace\n```\n").append(e.stackTraceToString().take(8000).trimEnd()).append("\n```\n\n")
        }
        if (appLog.isNotBlank()) {
            sb.append("## Journal de l'appli\n```\n").append(appLog.takeLast(8000).trimEnd()).append("\n```\n")
        }
        return finish(sb)
    }

    // ------------------------------------------------------------------ presse-papiers

    /** Enregistre le rapport complet dans un fichier et copie un résumé. Renvoie le fichier. */
    fun deliver(ctx: Context, label: String, text: String): File? {
        val f = try {
            Reports.save(ctx.applicationContext, label, text)
        } catch (e: Exception) {
            null
        }
        copy(ctx, render(text, Level.SHORT, f?.name))
        return f
    }

    /** Copie le rapport dans le presse-papiers (depuis n'importe quel fil). */
    fun copy(ctx: Context, text: String) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Rapport d'erreur", text))
                // Android 13+ affiche déjà sa propre confirmation
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(app, "Rapport d'erreur copié : colle-le dans Claude.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(app, "Copie du rapport impossible : ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Rapport d'un échec détecté par [BuildArchive.check] : fichier + résumé copié. Fichier, ou null. Hors fil UI. */
    fun copyBuildFailure(ctx: Context, api: GitHubApi, repo: String, c: BuildArchive.Check): File? {
        val run = c.failedRun ?: return null
        val text = try {
            forRun(api, repo, run)
        } catch (e: Exception) {
            null
        } ?: return null
        val label = repo.substringAfter('/') + "_" + run.optString("head_sha").take(7)
        return deliver(ctx, label, text)
    }

    /** Relance les jobs en échec d'une compilation. Hors fil UI. */
    fun rerunFailed(api: GitHubApi, repo: String, runId: Long): Boolean =
        api.call("POST", "/repos/$repo/actions/runs/$runId/rerun-failed-jobs", null).ok

    // ------------------------------------------------------------------ niveaux de rapport

    /**
     * Niveaux de détail. Le fichier enregistré est le rapport COMPLET ; COURT et MOYEN en sont tirés
     * (sections filtrées et plafonnées) pour économiser les tokens.
     */
    enum class Level(
        val label: String,
        val sections: List<String>?,
        val sectionCap: Int,
        val totalCap: Int
    ) {
        SHORT(
            "COURT",
            listOf(
                "Jobs et étapes", "Annotations", "Journal —", "Lignes d'erreur", "Extraits des fichiers cités",
                "Fichiers modifiés", "Message", "Réponse brute", "Trace"
            ),
            3_500, 16_000
        ),
        MEDIUM(
            "MOYEN",
            listOf(
                "Jobs et étapes", "Annotations", "Journal —", "Lignes d'erreur", "Extraits des fichiers cités",
                "Fichiers modifiés", "Message", "Réponse brute", "Trace",
                "Fin du journal", "Diff", "Fichiers de configuration", "Fichier du workflow", "Journal de l'appli"
            ),
            15_000, 70_000
        ),
        FULL("COMPLET", null, Int.MAX_VALUE, 800_000)
    }

    /** Estimation grossière : 1 token ≈ 3,5 caractères. */
    fun tokens(chars: Int): Int = (chars / 3.5).toInt()

    private class Section(val heading: String, val body: String)

    /** Découpe un rapport en préambule + sections « ## » (hors blocs de code). */
    private fun split(text: String): Pair<String, List<Section>> {
        val pre = StringBuilder()
        val out = ArrayList<Section>()
        var heading: String? = null
        var body = StringBuilder()
        var fence = false
        for (line in text.lines()) {
            if (line.startsWith("```")) fence = !fence
            if (!fence && line.startsWith("## ")) {
                heading?.let { out.add(Section(it, body.toString())) }
                heading = line.removePrefix("## ").trim()
                body = StringBuilder()
                continue
            }
            if (heading == null) pre.append(line).append('\n') else body.append(line).append('\n')
        }
        heading?.let { out.add(Section(it, body.toString())) }
        return pre.toString().trimEnd() + "\n\n" to out
    }

    /** Coupe [s] à [max] caractères sur une fin de ligne, en refermant un bloc de code ouvert. */
    private fun cap(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf('\n', max).let { if (it <= 0) max else it }
        val head = s.substring(0, cut)
        val open = head.lines().count { it.startsWith("```") } % 2 == 1
        return head + (if (open) "\n```" else "") + "\n[… coupé : voir niveau supérieur]\n"
    }

    /** Rapport au niveau [level], tiré du rapport complet [full]. */
    fun render(full: String, level: Level, fileName: String? = null, chat: Boolean = false, withAsk: Boolean = true): String {
        val (pre, sections) = split(full)
        val ask = sections.firstOrNull { it.heading == "Demande" }?.body?.trim().orEmpty()
        val kept = sections.filter { sec ->
            sec.heading != "Demande" && (level.sections == null || level.sections.any { sec.heading.startsWith(it) })
        }
        val bodies = StringBuilder()
        for (sec in kept) {
            var body = sec.body
            if (level == Level.SHORT && sec.heading.startsWith("Jobs")) {
                body = body.lines().filter { !it.startsWith("  - ✓") && !it.startsWith("  - –") }.joinToString("\n")
            }
            bodies.append("## ").append(sec.heading).append('\n').append(cap(body.trimEnd(), level.sectionCap)).append("\n\n")
        }
        val sizes = Level.values().associateWith { lv ->
            if (lv == level) 0 else tokens(if (lv == Level.FULL) full.length else renderBodyLength(sections, lv))
        }
        val sb = StringBuilder(if (withAsk) pre else "")
        if (withAsk) sb.append("## Demande\n").append(ask).append("\n\n")
        if (!chat) sb.append(instructions(level, sizes, fileName)).append("\n\n")
        else if (withAsk) sb.append(ChatProtocol.TEXT).append("\n\n")
        sb.append(cap(bodies.toString(), level.totalCap))
        return sb.toString().trimEnd() + "\n"
    }

    private fun renderBodyLength(sections: List<Section>, lv: Level): Int =
        sections.filter { s -> lv.sections == null || lv.sections.any { s.heading.startsWith(it) } }
            .sumOf { minOf(it.body.length, lv.sectionCap) }.coerceAtMost(lv.totalCap)

    /** Consignes pour Claude : demander le niveau ou le fichier dont il a besoin. */
    private fun instructions(level: Level, sizes: Map<Level, Int>, fileName: String?): String {
        val sb = StringBuilder("## Niveau de ce rapport : ${level.label}")
        if (fileName != null) sb.append(" (fichier ").append(fileName).append(')')
        sb.append('\n')
        sb.append(
            "- Commence par analyser ce rapport. Si la cause est identifiable, donne directement la correction " +
                "(chemin du fichier + modification, ou fichier complet si plus simple).\n" +
                "- N'utilise PAS d'hypothèse floue : s'il manque une information, demande-la précisément, " +
                "avec la formule exacte entre guillemets ci-dessous (l'utilisateur la recopie dans l'appli).\n"
        )
        if (level != Level.FULL) {
            if (level == Level.SHORT) {
                sb.append(
                    "- « Envoie le rapport MOYEN » (≈ ${sizes[Level.MEDIUM]} tokens) : ajoute fin du journal, diff complet, " +
                        "fichiers Gradle, manifeste, workflow. À demander si l'erreur vient de la configuration, " +
                        "d'une dépendance, du workflow, ou si les lignes d'erreur sont coupées.\n"
                )
            }
            sb.append(
                "- « Envoie le rapport COMPLET » (≈ ${sizes[Level.FULL]} tokens) : ajoute fichiers cités entiers, " +
                    "arborescence du dépôt, journal complet. À demander seulement si les niveaux inférieurs ne suffisent pas " +
                    "(fichier mal placé, doublon, erreur loin dans le journal).\n"
            )
        }
        sb.append(
            "- « Envoie le fichier <chemin> » : un seul fichier du dépôt (appli > Explorer, ou Historique > Parcourir). " +
                "Moins coûteux qu'un niveau supérieur quand un seul fichier manque.\n" +
                "- Rappelle à l'utilisateur ce qu'il doit envoyer, en une ligne, en fin de réponse."
        )
        return sb.toString()
    }

    /** Écrit le niveau demandé dans un fichier partageable. Hors fil UI possible. */
    fun levelFile(ctx: Context, report: File, level: Level): File {
        if (level == Level.FULL) return report
        val text = render(report.readText(Charsets.UTF_8), level, report.name)
        val dir = ShareProvider.dir(ctx).also { it.mkdirs() }
        val f = File(dir, report.name.removeSuffix(".md") + "_" + level.label.lowercase() + ".md")
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    // ------------------------------------------------------------------ outils

    /** Chemins de fichiers du dépôt cités dans les lignes d'erreur, avec numéros de ligne. */
    private fun collectCited(lines: List<String>, out: MutableMap<String, TreeSet<Int>>) {
        for (l in lines) {
            if (!ERROR_LINE.containsMatchIn(l)) continue
            for (m in CITED.findAll(l)) {
                val p = m.groupValues[1]
                if (p.contains("/build/") || p.startsWith("build/") || p.contains(".gradle/")) continue
                val set = out.getOrPut(p) { TreeSet() }
                m.groupValues[2].toIntOrNull()?.let { set.add(it) }
            }
        }
    }

    /** Contenu d'un fichier du dépôt à la version [sha], ou null. */
    private fun fileAt(api: GitHubApi, base: String, path: String, sha: String): String? {
        val r = api.get("$base/contents/${path.split('/').joinToString("/") { android.net.Uri.encode(it) }}?ref=$sha")
        if (!r.ok) return null
        val content = r.json?.optString("content").orEmpty()
        if (content.isEmpty()) return null
        return try {
            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fichier entier (numéroté) s'il fait au plus [fullUpTo] lignes, sinon les lignes à ±[context] des erreurs.
     * Sans numéro de ligne : les [fullUpTo] premières lignes (40 au moins).
     */
    private fun excerpt(text: String, nums: Set<Int>, context: Int, fullUpTo: Int): String {
        val lines = text.lines()
        val w = lines.size.toString().length
        fun fmt(i: Int) = (i + 1).toString().padStart(w) + (if ((i + 1) in nums) " >| " else "  | ") + lines[i]
        if (lines.size <= fullUpTo || nums.isEmpty()) {
            val n = if (nums.isEmpty()) minOf(lines.size, fullUpTo.coerceAtLeast(40)) else lines.size
            val s = (0 until n).joinToString("\n") { fmt(it) }
            return if (n < lines.size) "$s\n[… ${lines.size - n} lignes de plus]" else s
        }
        val keep = TreeSet<Int>()
        for (n in nums) for (k in n - 1 - context..n - 1 + context) if (k in lines.indices) keep.add(k)
        val out = StringBuilder()
        var prev = -10
        for (k in keep) {
            if (prev >= 0 && k != prev + 1) out.append("…\n")
            out.append(fmt(k)).append('\n')
            prev = k
        }
        return out.toString().trimEnd()
    }

    /** Différences entre la dernière compilation réussie de la branche et ce commit. */
    private fun appendDiffSinceSuccess(api: GitHubApi, base: String, run: JSONObject, sha: String, sb: StringBuilder) {
        val branch = run.optString("head_branch")
        val wf = run.optLong("workflow_id")
        val q = "$base/actions/workflows/$wf/runs?status=success&per_page=1" +
            (if (branch.isNotEmpty()) "&branch=" + android.net.Uri.encode(branch) else "")
        val okRun = api.get(q).json?.optJSONArray("workflow_runs")?.optJSONObject(0)
        val okSha = okRun?.optString("head_sha").orEmpty()
        val cmp = if (okSha.isNotEmpty() && okSha != sha) {
            sb.append("## Fichiers modifiés depuis la dernière compilation réussie (${okSha.take(7)} → ${sha.take(7)})\n")
            api.get("$base/compare/$okSha...$sha")
        } else {
            sb.append("## Fichiers modifiés par ce commit (aucune compilation réussie connue avant)\n")
            api.get("$base/commits/$sha")
        }
        if (!cmp.ok) {
            sb.append("(lecture impossible : HTTP ${cmp.code})\n\n")
            return
        }
        val files = cmp.json?.optJSONArray("files")
        if (files == null || files.length() == 0) {
            sb.append("(aucun fichier modifié)\n\n")
            return
        }
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            sb.append("- ${f.optString("status")} ${f.optString("filename")} (+${f.optInt("additions")} −${f.optInt("deletions")})\n")
        }
        sb.append('\n')
        sb.append("## Diff de ces modifications\n")
        var used = 0
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val patch = f.optString("patch")
            if (patch.isEmpty() || used > MAX_DIFF_CHARS) continue
            val part = if (patch.length > MAX_PATCH) patch.take(MAX_PATCH) + "\n[… diff tronqué]" else patch
            sb.append("### ${f.optString("filename")}\n```diff\n").append(part.trimEnd()).append("\n```\n")
            used += part.length
        }
        if (used > MAX_DIFF_CHARS) sb.append("[… autres diffs omis]\n")
        sb.append('\n')
    }

    private fun lang(p: String): String = when (p.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "toml" -> "toml"
        "yml", "yaml" -> "yaml"
        "gradle" -> "groovy"
        "properties", "pro" -> "properties"
        else -> ""
    }

    private fun errorLines(lines: List<String>): String {
        val keep = TreeSet<Int>()
        for ((i, l) in lines.withIndex()) {
            if (l.contains("What went wrong")) {
                for (k in i..i + 15) if (k in lines.indices) keep.add(k)
            } else if (ERROR_LINE.containsMatchIn(l)) {
                for (k in i - 2..i + 2) if (k in lines.indices) keep.add(k)
            }
        }
        if (keep.isEmpty()) return "(aucune ligne d'erreur reconnue : voir la fin du journal)"
        val out = StringBuilder()
        var prev = -10
        var count = 0
        for (k in keep) {
            if (count >= MAX_ERROR_LINES) {
                out.append("[… autres lignes omises]\n")
                break
            }
            if (prev >= 0 && k != prev + 1) out.append("…\n")
            out.append(clip(lines[k])).append('\n')
            prev = k
            count++
        }
        return out.toString().trimEnd()
    }

    /** Les dernières lignes avant la fin de l'étape en échec. */
    private fun tail(lines: List<String>): String {
        val err = lines.indexOfFirst { it.contains("##[error]Process completed") }
        val end = if (err >= 0) err + 1 else lines.size
        val start = (end - TAIL_LINES).coerceAtLeast(0)
        return lines.subList(start, end).joinToString("\n") { clip(it) }.trimEnd()
    }

    private fun clip(s: String) = if (s.length > MAX_LINE) s.take(MAX_LINE) + " […]" else s

    private fun finish(sb: StringBuilder): String =
        if (sb.length <= MAX_CHARS) sb.toString().trimEnd() + "\n"
        else sb.substring(0, MAX_CHARS) + "\n\n[… rapport tronqué]\n"

    private fun appVersion(ctx: Context): String = try {
        "v" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (e: Exception) {
        "version inconnue"
    }
}
