package com.example.gitzippush

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Chiffrement AES-GCM avec une clé stockée dans l'Android Keystore (la clé ne quitte jamais la puce). */
object SecureStore {
    private const val ALIAS = "envoi_github_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    /** Renvoie null si le texte est illisible (clé supprimée, données corrompues). */
    fun decrypt(stored: String): String? {
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= IV_SIZE) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, IV_SIZE)))
            String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

/** Un dépôt mémorisé : nom « pseudo/depot », branche habituelle (vide = par défaut), dernier usage. */
class RepoEntry(val repo: String, val branch: String, val usedAt: Long)

/** Réglages enregistrés : le jeton est chiffré, le reste est en clair. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("envoi_github", Context.MODE_PRIVATE)

    var repo: String
        get() = prefs.getString("repo", "") ?: ""
        set(value) = prefs.edit().putString("repo", value.trim()).apply()

    var branch: String
        get() = prefs.getString("branch", "") ?: ""
        set(value) = prefs.edit().putString("branch", value.trim()).apply()

    var message: String
        get() = prefs.getString("message", "") ?: ""
        set(value) = prefs.edit().putString("message", value).apply()

    // ------------------------------------------------------------------ dépôts mémorisés

    /** Dépôts mémorisés : les plus récemment utilisés d'abord, puis par ordre alphabétique. */
    fun repos(): List<RepoEntry> {
        val raw = prefs.getString("repos", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<RepoEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("repo")
                if (name.isNotEmpty()) list.add(RepoEntry(name, o.optString("branch"), o.optLong("t")))
            }
            list.sortedWith(compareByDescending<RepoEntry> { it.usedAt }.thenBy { it.repo.lowercase() })
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveRepos(list: List<RepoEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().put("repo", e.repo).put("branch", e.branch).put("t", e.usedAt))
        }
        prefs.edit().putString("repos", arr.toString()).apply()
    }

    /** Mémorise (ou remonte en tête) un dépôt utilisé avec succès. */
    fun rememberRepo(repo: String, branch: String) {
        val others = repos().filter { !it.repo.equals(repo, ignoreCase = true) }
        saveRepos(listOf(RepoEntry(repo, branch.trim(), System.currentTimeMillis())) + others)
    }

    /** Ajoute des dépôts inconnus (sans les remonter en tête). Renvoie le nombre d'ajouts. */
    fun addKnownRepos(names: List<String>): Int {
        val current = repos().toMutableList()
        var added = 0
        for (n in names) {
            if (current.none { it.repo.equals(n, ignoreCase = true) }) {
                current.add(RepoEntry(n, "", 0L))
                added++
            }
        }
        if (added > 0) saveRepos(current)
        return added
    }

    fun forgetRepo(repo: String) {
        saveRepos(repos().filter { !it.repo.equals(repo, ignoreCase = true) })
    }

    // ------------------------------------------------------------------ associations nom de zip -> dépôt

    fun learnedRepo(key: String): RepoEntry? {
        val raw = prefs.getString("zipmap", null) ?: return null
        return try {
            val o = JSONObject(raw).optJSONObject(key) ?: return null
            RepoEntry(o.optString("repo"), o.optString("branch"), o.optLong("t"))
        } catch (e: Exception) {
            null
        }
    }

    /** Retient que les envois nommés ainsi vont dans ce dépôt (garde les 60 plus récents). */
    fun learnZip(keys: List<String>, repo: String, branch: String) {
        val root = try {
            JSONObject(prefs.getString("zipmap", null) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val now = System.currentTimeMillis()
        for (k in keys) {
            if (k.isNotEmpty()) root.put(k, JSONObject().put("repo", repo).put("branch", branch).put("t", now))
        }
        if (root.length() > 60) {
            val all = ArrayList<Pair<String, Long>>()
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                all.add(k to root.getJSONObject(k).optLong("t"))
            }
            all.sortBy { p -> p.second }
            for (i in 0 until all.size - 60) root.remove(all[i].first)
        }
        prefs.edit().putString("zipmap", root.toString()).apply()
    }

    var autoWorkflow: Boolean
        get() = prefs.getBoolean("auto_workflow", true)
        set(value) = prefs.edit().putBoolean("auto_workflow", value).apply()

    var cleanJunk: Boolean
        get() = prefs.getBoolean("clean_junk", true)
        set(value) = prefs.edit().putBoolean("clean_junk", value).apply()

    var ignoreGenerated: Boolean
        get() = prefs.getBoolean("ignore_generated", true)
        set(value) = prefs.edit().putBoolean("ignore_generated", value).apply()

    var notifAsked: Boolean
        get() = prefs.getBoolean("notif_asked", false)
        set(value) = prefs.edit().putBoolean("notif_asked", value).apply()

    /** Récupérer et installer l'APK automatiquement après chaque envoi. */
    var autoApk: Boolean
        get() = prefs.getBoolean("auto_apk", false)
        set(value) = prefs.edit().putBoolean("auto_apk", value).apply()

    /** Nombre de sauvegardes de compilations réussies gardées (0 = toutes). */
    var archiveKeep: Int
        get() = prefs.getInt("archive_keep", 30)
        set(value) = prefs.edit().putInt("archive_keep", value).apply()

    /** Copier aussi chaque sauvegarde dans Téléchargements/Envoi GitHub. */
    var archivePublic: Boolean
        get() = prefs.getBoolean("archive_public", true)
        set(value) = prefs.edit().putBoolean("archive_public", value).apply()

    /** Copier le zip dans Documents/Github/<dépôt> (3 derniers gardés) quand la compilation réussit. */
    var archiveDocs: Boolean
        get() = prefs.getBoolean("archive_docs", true)
        set(value) = prefs.edit().putBoolean("archive_docs", value).apply()

    /** Supprimer du téléphone le zip d'origine quand sa compilation échoue. */
    var deleteFailedZip: Boolean
        get() = prefs.getBoolean("delete_failed_zip", true)
        set(value) = prefs.edit().putBoolean("delete_failed_zip", value).apply()

    // ------------------------------------------------------------------ nettoyage GitHub

    var cleanFailedRuns: Boolean
        get() = prefs.getBoolean("clean_failed", true)
        set(value) = prefs.edit().putBoolean("clean_failed", value).apply()

    var cleanOldRuns: Boolean
        get() = prefs.getBoolean("clean_old", true)
        set(value) = prefs.edit().putBoolean("clean_old", value).apply()

    /** Nombre de compilations les plus récentes gardées par dépôt. */
    var cleanKeepRuns: Int
        get() = prefs.getInt("clean_keep", 5)
        set(value) = prefs.edit().putInt("clean_keep", value).apply()

    var cleanArtifacts: Boolean
        get() = prefs.getBoolean("clean_artifacts", true)
        set(value) = prefs.edit().putBoolean("clean_artifacts", value).apply()

    var cleanCaches: Boolean
        get() = prefs.getBoolean("clean_caches", false)
        set(value) = prefs.edit().putBoolean("clean_caches", value).apply()

    var cleanBranches: Boolean
        get() = prefs.getBoolean("clean_branches", false)
        set(value) = prefs.edit().putBoolean("clean_branches", value).apply()

    /** Nettoyage GitHub automatique chaque semaine. */
    var cleanAuto: Boolean
        get() = prefs.getBoolean("clean_auto", false)
        set(value) = prefs.edit().putBoolean("clean_auto", value).apply()

    /** Incrémenter versionCode automatiquement à chaque envoi. */
    var autoVersion: Boolean
        get() = prefs.getBoolean("auto_version", true)
        set(value) = prefs.edit().putBoolean("auto_version", value).apply()

    var cleanAllRepos: Boolean
        get() = prefs.getBoolean("clean_all", false)
        set(value) = prefs.edit().putBoolean("clean_all", value).apply()

    /** Destinations Claude mémorisées (JSON). */
    var claudeDests: String
        get() = prefs.getString("claude_dests", "[]") ?: "[]"
        set(value) = prefs.edit().putString("claude_dests", value).apply()

    fun token(): String? {
        val stored = prefs.getString("token", null) ?: return null
        return SecureStore.decrypt(stored)
    }

    fun hasToken(): Boolean = token() != null

    fun saveToken(token: String) {
        prefs.edit().putString("token", SecureStore.encrypt(token)).apply()
    }

    fun clearToken() {
        prefs.edit().remove("token").apply()
    }

    companion object {
        /** Retire tout espace ou retour à la ligne invisible collé avec le jeton. */
        fun cleanToken(raw: String): String = raw.filter { !it.isWhitespace() }
    }
}
