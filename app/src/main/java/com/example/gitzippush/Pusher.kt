package com.example.gitzippush

import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class PushOptions(
    val owner: String,
    val repo: String,
    /** Vide = branche par défaut du dépôt. */
    val branch: String,
    val message: String,
    val stripRoot: Boolean,
    val deleteMissing: Boolean,
    /** Sous-dossier du dépôt où placer les fichiers (vide = racine). */
    val targetDir: String = "",
    /** Ajoute un workflow « Build APK » si c'est un projet Android sans workflow. */
    val autoWorkflow: Boolean = false,
    /** Fichiers seuls : chacun est placé au bon endroit du dépôt (voir Placer). */
    val autoPlace: Boolean = false,
    /** Supprime du dépôt les fichiers inutiles déjà présents (build/, .gradle/, .idea/, local.properties, *.iml…). */
    val cleanJunk: Boolean = false
)

class PushResult(
    val branch: String,
    /** null = rien n'a été envoyé (dépôt déjà à jour). */
    val commitSha: String?,
    val uploaded: Int,
    val unchanged: Int,
    val deleted: Int,
    val workflowAdded: Boolean = false
)

/**
 * Équivalent d'un « git add -A, git commit, git push » : lit le zip, ne transfère que les fichiers
 * qui diffèrent du dépôt, puis crée UN SEUL commit via l'API Git de GitHub.
 */
class Pusher(
    private val api: GitHubApi,
    private val log: (String) -> Unit,
    private val progress: (done: Int, total: Int) -> Unit
) {

    private sealed class RefState {
        class Found(val sha: String) : RefState()
        object Missing : RefState()
        object EmptyRepo : RefState()
    }

    // ------------------------------------------------------------------ test de connexion

    fun check(owner: String, repo: String, branch: String): List<String> {
        val lines = ArrayList<String>()

        val user = api.get("/user")
        if (!user.ok) throw GitHubApi.explain(user, "Connexion")
        lines.add("Connecté en tant que « ${user.json?.optString("login").orEmpty()} »")

        val scopes = user.scopes
        if (scopes != null) {
            lines.add("Droits du jeton : " + (if (scopes.isBlank()) "aucun" else scopes))
            if (!hasScope(scopes, "repo") && !hasScope(scopes, "public_repo")) {
                lines.add("⚠ le droit « repo » manque : l'envoi sera refusé")
            }
            if (!hasScope(scopes, "workflow")) {
                lines.add("⚠ sans le droit « workflow », les fichiers .github/workflows seront refusés")
            }
        }

        val reply = api.get("/repos/$owner/$repo")
        if (!reply.ok) throw GitHubApi.explain(reply, "Dépôt $owner/$repo")
        val json = reply.json
        val canPush = json?.optJSONObject("permissions")?.optBoolean("push", false) ?: true
        lines.add("Dépôt $owner/$repo : " + (if (canPush) "écriture autorisée" else "lecture seule"))
        if (!canPush) lines.add("⚠ ce jeton ne peut pas écrire dans ce dépôt")

        val defaultBranch = json?.optString("default_branch", "main").orEmpty().ifEmpty { "main" }
        val target = branch.trim().ifEmpty { defaultBranch }
        when (refState("/repos/$owner/$repo", target)) {
            is RefState.Found -> lines.add("Branche « $target » : trouvée")
            is RefState.Missing -> lines.add("Branche « $target » : sera créée à partir de « $defaultBranch »")
            is RefState.EmptyRepo -> lines.add("Dépôt vide : le premier commit sera créé")
        }
        return lines
    }

    // ------------------------------------------------------------------ liste des dépôts du compte

    /** Noms « pseudo/depot » des dépôts où le jeton peut écrire (jusqu'à 500, les plus récents d'abord). */
    fun listRepos(): List<String> {
        val names = ArrayList<String>()
        var page = 1
        while (page <= 5) {
            val reply = api.get(
                "/user/repos?per_page=100&page=$page&sort=pushed&affiliation=owner,collaborator,organization_member"
            )
            if (!reply.ok) throw GitHubApi.explain(reply, "Liste des dépôts")
            val arr = try {
                JSONArray(reply.text)
            } catch (e: Exception) {
                throw PushException("Réponse de GitHub illisible (liste des dépôts).")
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val canPush = o.optJSONObject("permissions")?.optBoolean("push", true) ?: true
                if (canPush && !o.optBoolean("archived", false)) {
                    val full = o.optString("full_name")
                    if (full.isNotEmpty()) names.add(full)
                }
            }
            if (arr.length() < 100) break
            page++
        }
        return names
    }

    /** Chemins des fichiers de la branche par défaut d'un dépôt (null si illisible). */
    fun treePaths(owner: String, repo: String): Set<String>? {
        val info = api.get("/repos/$owner/$repo")
        if (!info.ok) return null
        val branch = info.json?.optString("default_branch", "main").orEmpty().ifEmpty { "main" }
        val tree = api.get("/repos/$owner/$repo/git/trees/${encodePath(branch)}?recursive=1")
        if (!tree.ok) return null
        val arr = tree.json?.optJSONArray("tree") ?: return null
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "blob") out.add(o.optString("path"))
        }
        return out
    }

    // ------------------------------------------------------------------ création d'un dépôt

    /** Crée le dépôt (avec un premier commit « README ») et renvoie son adresse. */
    fun createRepo(owner: String, name: String, isPrivate: Boolean): String {
        val existing = api.get("/repos/$owner/$name")
        if (existing.ok) throw PushException("Le dépôt $owner/$name existe déjà : rien à créer.")

        val user = api.get("/user")
        if (!user.ok) throw GitHubApi.explain(user, "Connexion")
        val login = user.json?.optString("login").orEmpty()

        val body = JSONObject()
            .put("name", name)
            .put("private", isPrivate)
            .put("auto_init", true)
            .put("description", "Créé depuis l'appli Envoi GitHub")
        val path = if (login.equals(owner, ignoreCase = true)) "/user/repos" else "/orgs/$owner/repos"

        val reply = api.call("POST", path, body)
        if (reply.code == 403 || reply.code == 404) {
            throw PushException(
                "Création refusée (${reply.code}). Le jeton doit avoir le droit « repo » " +
                    "(jeton classique), et tu dois pouvoir créer des dépôts pour « $owner »."
            )
        }
        if (!reply.ok) throw GitHubApi.explain(reply, "Création du dépôt")
        return reply.json?.optString("html_url").orEmpty().ifEmpty { "https://github.com/$owner/$name" }
    }

    // ------------------------------------------------------------------ envoi

    fun push(source: FileSource, opt: PushOptions): PushResult {
        val owner = opt.owner
        val repo = opt.repo
        val base = "/repos/$owner/$repo"

        log("Lecture des fichiers…")
        val scan = source.scan()
        if (scan.files.isEmpty()) throw PushException("Aucun fichier utilisable à envoyer.")

        val strip = opt.stripRoot && scan.root != null
        val prefix = if (strip) scan.root + "/" else ""
        val dir = opt.targetDir.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }
        // nom dans la source -> chemin choisi par le placement automatique
        val placed = HashMap<String, String>()
        val texts = HashMap<String, String?>()
        if (opt.autoPlace) {
            for (f in scan.files) {
                val t = source.readText(f.name)
                texts[f.name] = t
                Placer.marker(t)?.let { placed[f.name] = it }
            }
        }
        fun repoPath(name: String): String = placed[name] ?: (dir + name.removePrefix(prefix))

        // chemin dans le dépôt -> fichier de la source
        val files = LinkedHashMap<String, ScannedFile>()
        for (f in scan.files) {
            files[repoPath(f.name)] = f
        }
        log(
            files.size.toString() + " fichier(s) à envoyer" +
                (if (strip) " (dossier « ${scan.root} » ignoré)" else "") +
                (if (dir.isNotEmpty()) " dans « ${dir.trimEnd('/')} »" else "") +
                (if (scan.skipped > 0) ", ${scan.skipped} ignoré(s)" else "")
        )

        // --- dépôt et branche
        val repoReply = api.get(base)
        if (repoReply.code == 404) {
            throw PushException(
                "Dépôt $owner/$repo introuvable. Vérifie son nom, ou crée-le avec le bouton « Créer le dépôt » " +
                    "(si c'est un dépôt privé, le jeton doit avoir le droit « repo »)."
            )
        }
        if (!repoReply.ok) throw GitHubApi.explain(repoReply, "Dépôt $owner/$repo")
        val repoJson = repoReply.json ?: throw PushException("Réponse de GitHub illisible.")
        val perms = repoJson.optJSONObject("permissions")
        if (perms != null && !perms.optBoolean("push", false)) {
            throw PushException("Le jeton n'a pas le droit d'écrire dans $owner/$repo.")
        }
        val defaultBranch = repoJson.optString("default_branch", "main").ifEmpty { "main" }
        val branch = opt.branch.trim().ifEmpty { defaultBranch }

        val userReply = api.get("/user")
        val scopes: String? = if (userReply.ok) userReply.scopes else null

        var state = refState(base, branch)
        var bootstrapped = 0
        var bootstrapSha: String? = null

        if (state is RefState.EmptyRepo) {
            log("Dépôt vide : création du premier commit…")
            val first = files.entries.first()
            var found: ByteArray? = null
            source.forEach { name, bytes ->
                if (name == first.value.name && found == null) found = bytes
            }
            val content = found ?: throw PushException("Les fichiers ont changé pendant la lecture.")
            val body = JSONObject()
                .put("message", messageOf(opt))
                .put("content", content.toByteString().base64())
            val put = api.call("PUT", "$base/contents/${encodePath(first.key)}", body)
            if (!put.ok) throw GitHubApi.explain(put, "Premier commit")
            bootstrapped = 1
            bootstrapSha = put.json?.optJSONObject("commit")?.optString("sha")
            state = refState(base, branch)
        }

        val head: String = when (val s = state) {
            is RefState.Found -> s.sha
            is RefState.Missing -> {
                val def = refState(base, defaultBranch)
                val defSha = (def as? RefState.Found)?.sha
                    ?: throw PushException("La branche « $branch » n'existe pas et la branche par défaut est illisible.")
                val created = api.call(
                    "POST", "$base/git/refs",
                    JSONObject().put("ref", "refs/heads/$branch").put("sha", defSha)
                )
                if (!created.ok) throw GitHubApi.explain(created, "Création de la branche $branch")
                log("Branche « $branch » créée à partir de « $defaultBranch »")
                defSha
            }
            is RefState.EmptyRepo -> throw PushException("Le dépôt est toujours vide après le premier commit.")
        }

        // --- contenu actuel du dépôt
        val commitReply = api.get("$base/git/commits/$head")
        if (!commitReply.ok) throw GitHubApi.explain(commitReply, "Lecture du dernier commit")
        val baseTree = commitReply.json?.optJSONObject("tree")?.optString("sha").orEmpty()
        if (baseTree.isEmpty()) throw PushException("Arbre du dernier commit introuvable.")

        val treeReply = api.get("$base/git/trees/$baseTree?recursive=1")
        if (!treeReply.ok) throw GitHubApi.explain(treeReply, "Lecture du contenu du dépôt")
        val treeJson = treeReply.json
        val truncated = treeJson?.optBoolean("truncated", false) ?: false
        val existingSha = HashMap<String, String>()
        val existingMode = HashMap<String, String>()
        val arr = treeJson?.optJSONArray("tree")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") == "blob") {
                    val p = o.optString("path")
                    existingSha[p] = o.optString("sha")
                    existingMode[p] = o.optString("mode", "100644")
                }
            }
        }

        // --- placement automatique des fichiers seuls
        if (opt.autoPlace) {
            if (truncated) log("Dépôt très volumineux : placement automatique peut-être incomplet.")
            for (f in scan.files) {
                if (f.name in placed) continue
                val p = Placer.match(f.name, texts[f.name], existingSha.keys)
                placed[f.name] = p ?: (dir + Placer.baseName(f.name))
            }
            files.clear()
            for (f in scan.files) {
                val p = repoPath(f.name)
                files[p] = f
                log("  → $p" + if (p in existingSha) "" else " (nouveau)")
            }
        }

        // --- workflow de compilation ajouté automatiquement (projet Android sans workflow)
        val extras = HashMap<String, ByteArray>()
        if (opt.autoWorkflow) {
            if (dir.isNotEmpty()) {
                log("Workflow automatique ignoré : il ne fonctionne qu'à la racine du dépôt.")
            } else if (WorkflowTemplate.isAndroidProject(files.keys) &&
                !WorkflowTemplate.hasWorkflow(files.keys) && !WorkflowTemplate.hasWorkflow(existingSha.keys)
            ) {
                val propsName = prefix + WorkflowTemplate.WRAPPER_PROPS
                val props = if (WorkflowTemplate.WRAPPER_PROPS in files.keys) source.readText(propsName) else null
                val text = WorkflowTemplate.build(repo, files.keys, props)
                val bytes = text.toByteArray(Charsets.UTF_8)
                extras[WorkflowTemplate.PATH] = bytes
                files[WorkflowTemplate.PATH] = ScannedFile(WorkflowTemplate.PATH, bytes.size.toLong(), ZipTools.gitBlobSha(bytes))
                log("Projet Android sans workflow : ajout de ${WorkflowTemplate.PATH}")
            }
        }

        // --- conservation des artefacts limitée à 1 jour dans les workflows envoyés
        for ((path, f) in files.entries.toList()) {
            if (path in extras || !path.startsWith(".github/workflows/") ||
                !(path.endsWith(".yml") || path.endsWith(".yaml"))
            ) continue
            val text = source.readText(f.name) ?: continue
            val patched = WorkflowTemplate.withRetention(text)
            if (patched != text) {
                val bytes = patched.toByteArray(Charsets.UTF_8)
                extras[path] = bytes
                files[path] = ScannedFile(path, bytes.size.toLong(), ZipTools.gitBlobSha(bytes))
                log("$path : artefacts conservés 1 jour")
            }
        }

        // --- différences
        val toUpload = ArrayList<String>()
        var unchanged = 0
        for ((path, f) in files) {
            if (existingSha[path] == f.sha) unchanged++ else toUpload.add(path)
        }
        val toDelete = ArrayList<String>()
        if (opt.deleteMissing) {
            if (truncated) {
                throw PushException(
                    "Le dépôt est trop volumineux pour supprimer automatiquement les fichiers absents du zip. " +
                        "Désactive cette option."
                )
            }
            for (p in existingSha.keys) {
                if (!files.containsKey(p)) toDelete.add(p)
            }
        }
        if (opt.cleanJunk) {
            var junk = 0
            for (p in existingSha.keys) {
                if (Generated.isIgnoredPath(p) && !files.containsKey(p) && p !in toDelete) {
                    toDelete.add(p)
                    junk++
                }
            }
            if (junk > 0) {
                log("$junk fichier(s) inutile(s) dans le dépôt (build/, .gradle/, .idea/…) : suppression")
                if (truncated) log("Dépôt très volumineux : nettoyage peut-être incomplet.")
            }
        }
        toDelete.sort()
        val alreadyCounted = bootstrapped
        unchanged -= alreadyCounted

        log("${toUpload.size} nouveau(x) ou modifié(s), $unchanged identique(s)" +
            (if (toDelete.isNotEmpty()) ", ${toDelete.size} à supprimer" else ""))

        if (toUpload.isEmpty() && toDelete.isEmpty()) {
            return PushResult(branch, bootstrapSha, bootstrapped, unchanged, 0)
        }

        if (scopes != null && !hasScope(scopes, "workflow") &&
            toUpload.any { it.startsWith(".github/workflows/") }
        ) {
            throw PushException(
                "Les fichiers contiennent un .github/workflows/ mais le jeton n'a pas le droit « workflow ». " +
                    "Crée un jeton avec « repo » ET « workflow » cochés."
            )
        }

        // --- envoi des fichiers (un blob par fichier modifié)
        val pending = HashSet<String>(toUpload)
        val entries = JSONArray()
        val total = toUpload.size
        var done = 0
        progress(0, total)

        fun upload(path: String, bytes: ByteArray, expected: ScannedFile) {
            run {
                pending.remove(path)
                val reply = api.call(
                    "POST", "$base/git/blobs",
                    JSONObject().put("content", bytes.toByteString().base64()).put("encoding", "base64")
                )
                if (!reply.ok) throw GitHubApi.explain(reply, "Envoi de $path")
                val sha = reply.json?.optString("sha").orEmpty()
                if (sha != expected.sha) throw PushException("Contrôle d'intégrité échoué pour $path.")
                entries.put(
                    JSONObject()
                        .put("path", path)
                        .put("mode", existingMode[path] ?: "100644")
                        .put("type", "blob")
                        .put("sha", sha)
                )
                done++
                progress(done, total)
                log("  ↑ $path")
            }
        }
        source.forEach { name, bytes ->
            val path = repoPath(name)
            val expected = files[path]
            if (expected != null && path !in extras && path in pending &&
                ZipTools.gitBlobSha(bytes) == expected.sha
            ) {
                upload(path, bytes, expected)
            }
        }
        for ((path, bytes) in extras) {
            val expected = files[path]
            if (expected != null && path in pending) upload(path, bytes, expected)
        }
        if (pending.isNotEmpty()) {
            throw PushException("Les fichiers ont changé pendant la lecture : ${pending.first()}")
        }

        for (p in toDelete) {
            entries.put(
                JSONObject()
                    .put("path", p)
                    .put("mode", existingMode[p] ?: "100644")
                    .put("type", "blob")
                    .put("sha", JSONObject.NULL)
            )
            log("  ✕ $p")
        }

        // --- arbre, commit, branche
        val treeNew = api.call(
            "POST", "$base/git/trees",
            JSONObject().put("base_tree", baseTree).put("tree", entries)
        )
        if (!treeNew.ok) throw GitHubApi.explain(treeNew, "Création de l'arbre")
        val newTree = treeNew.json?.optString("sha").orEmpty()
        if (newTree.isEmpty()) throw PushException("Réponse de GitHub illisible (arbre).")
        if (newTree == baseTree) {
            log("Aucun changement réel à enregistrer.")
            return PushResult(branch, bootstrapSha, bootstrapped, unchanged, 0)
        }

        val commit = api.call(
            "POST", "$base/git/commits",
            JSONObject()
                .put("message", messageOf(opt))
                .put("tree", newTree)
                .put("parents", JSONArray().put(head))
        )
        if (!commit.ok) throw GitHubApi.explain(commit, "Création du commit")
        val commitSha = commit.json?.optString("sha").orEmpty()
        if (commitSha.isEmpty()) throw PushException("Réponse de GitHub illisible (commit).")

        val update = api.call(
            "PATCH", "$base/git/refs/heads/${encodePath(branch)}",
            JSONObject().put("sha", commitSha).put("force", false)
        )
        if (!update.ok) {
            if (update.code == 422) {
                throw PushException("Le dépôt a changé pendant l'envoi (quelqu'un a envoyé un commit entre-temps). Relance l'envoi.")
            }
            throw GitHubApi.explain(update, "Mise à jour de la branche $branch")
        }

        return PushResult(branch, commitSha, toUpload.size + bootstrapped, unchanged, toDelete.size, WorkflowTemplate.PATH in extras && WorkflowTemplate.PATH !in existingSha)
    }

    // ------------------------------------------------------------------ outils

    private fun refState(base: String, branch: String): RefState {
        val reply = api.get("$base/git/ref/heads/${encodePath(branch)}")
        return when {
            reply.ok -> {
                val sha = reply.json?.optJSONObject("object")?.optString("sha").orEmpty()
                if (sha.isEmpty()) throw PushException("Réponse de GitHub illisible (branche).")
                RefState.Found(sha)
            }
            reply.code == 404 -> RefState.Missing
            reply.code == 409 -> RefState.EmptyRepo
            else -> throw GitHubApi.explain(reply, "Lecture de la branche $branch")
        }
    }

    private fun messageOf(opt: PushOptions): String =
        opt.message.trim().ifEmpty { "Mise à jour depuis le téléphone" }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun hasScope(scopes: String, name: String): Boolean =
        scopes.split(',').any { it.trim() == name }
}
