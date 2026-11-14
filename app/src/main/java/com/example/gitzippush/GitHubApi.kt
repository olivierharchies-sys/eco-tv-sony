package com.example.gitzippush

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Erreur lisible par l'utilisateur. */
open class PushException(message: String, val detail: String? = null) : Exception(message)

/** Réponse de l'API GitHub. */
class ApiReply(val code: Int, val text: String, val scopes: String?, val expires: String? = null) {
    val ok: Boolean get() = code in 200..299

    val json: JSONObject?
        get() = try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }

    val message: String get() = json?.optString("message").orEmpty()
}

/** Client minimal de l'API REST GitHub (jeton d'accès personnel). À appeler hors du thread principal. */
class GitHubApi(
    private val token: String,
    private val baseUrl: String = "https://api.github.com",
    private val client: OkHttpClient = defaultClient()
) {
    /** Appelé quand on attend à cause d'une limite de débit (nombre de secondes). */
    var onWait: ((Long) -> Unit)? = null

    /** Service appelant : détermine les droits appliqués (écran Accès). */
    @Volatile var service: Service = Service.APP

    /** Contrôle des droits + jeton propre au dépôt (Accès). */
    private fun authFor(method: String, path: String, body: JSONObject?): String {
        AccessGuard.check(service, method, path, body)
        return "Bearer " + (AccessGuard.tokenFor(AccessGuard.repoOf(path)) ?: token)
    }

    fun get(path: String): ApiReply = call("GET", path, null)

    /**
     * Télécharge un fichier binaire (ex. artefact de compilation) vers [target].
     * GitHub répond par une redirection vers un stockage signé ; OkHttp la suit et retire le jeton
     * automatiquement quand l'hôte change.
     */
    fun download(
        path: String,
        target: File,
        expectedBytes: Long,
        onProgress: (done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", authFor("GET", path, null))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "EnvoiGitHub")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    throw explain(ApiReply(response.code, text, null), "Téléchargement")
                }
                val body = response.body ?: throw PushException("Téléchargement : réponse vide.")
                val total = if (body.contentLength() > 0) body.contentLength() else expectedBytes
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var done = 0L
                        while (true) {
                            if (cancelled()) throw PushException("Annulé.")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw PushException("Réseau indisponible : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun call(method: String, path: String, body: JSONObject?): ApiReply {
        val auth = authFor(method, path, body)
        var rateRetries = 0
        var networkRetries = 0
        while (true) {
            val payload = when {
                body != null -> body.toString().toRequestBody(JSON)
                method == "GET" -> null
                else -> "".toRequestBody(JSON)
            }
            val request = Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", auth)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "EnvoiGitHub")
                .method(method, payload)
                .build()

            var retryAfter: Long? = null
            val reply: ApiReply = try {
                client.newCall(request).execute().use { response ->
                    retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
                    ApiReply(
                        response.code, response.body?.string().orEmpty(), response.header("X-OAuth-Scopes"),
                        response.header("github-authentication-token-expiration")
                    )
                }
            } catch (e: IOException) {
                if (networkRetries < 2) {
                    networkRetries++
                    sleep(2)
                    continue
                }
                throw PushException("Réseau indisponible : ${e.message ?: e.javaClass.simpleName}")
            }

            val limited = (reply.code == 429) ||
                (reply.code == 403 && (retryAfter != null || reply.message.contains("rate limit", ignoreCase = true)))
            if (limited && rateRetries < 4) {
                val wait = (retryAfter ?: 60L).coerceIn(1L, 90L)
                rateRetries++
                onWait?.invoke(wait)
                sleep(wait)
                continue
            }
            return reply
        }
    }

    private fun sleep(seconds: Long) {
        try {
            Thread.sleep(seconds * 1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PushException("Envoi interrompu")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        /** Transforme une réponse d'erreur en message clair. */
        fun explain(reply: ApiReply, action: String): PushException {
            val detail = reply.message
            val text = when (reply.code) {
                401 -> "$action : jeton refusé (401). Il est invalide, expiré ou mal copié."
                403 -> "$action : accès refusé (403)" + (if (detail.isNotEmpty()) " — $detail" else "") +
                    ". Vérifie les droits du jeton (« repo », et « workflow » si le zip contient .github/workflows)."
                404 -> "$action : introuvable (404). Le dépôt n'existe pas, ou le jeton n'y a pas accès " +
                    "(il faut le droit « repo »)."
                409 -> "$action : conflit (409)" + (if (detail.isNotEmpty()) " — $detail" else "")
                422 -> "$action : refusé par GitHub (422)" + (if (detail.isNotEmpty()) " — $detail" else "")
                else -> "$action : erreur HTTP ${reply.code}" + (if (detail.isNotEmpty()) " — $detail" else "")
            }
            return PushException(text, "HTTP ${reply.code} — ${reply.text.take(3000)}")
        }
    }
}

/** Lecture d'un nom de dépôt : « pseudo/depot », adresse https ou adresse git. */
object RepoRef {
    private val VALID = Regex("^[A-Za-z0-9_.-]+$")

    fun parse(input: String): Pair<String, String>? {
        var s = input.trim()
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (s.contains("@github.com")) {
            s = "github.com" + s.substringAfter("@github.com")
        }
        if (s.startsWith("git@github.com:")) {
            s = s.removePrefix("git@github.com:")
        } else if (s.startsWith("github.com")) {
            s = s.removePrefix("github.com").trimStart('/', ':')
        }
        val parts = s.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        if (!VALID.matches(owner) || !VALID.matches(repo) || repo.isEmpty()) return null
        return Pair(owner, repo)
    }
}
