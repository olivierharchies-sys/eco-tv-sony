package com.example.braviaeco

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement de la sauvegarde des réglages par mot de passe : PBKDF2 (HMAC-SHA1, disponible dès Android 7)
 * pour dériver une clé AES-256, puis AES-GCM. Format : « ECOTV1: » + base64(sel 16 o + IV 12 o + données).
 * La sauvegarde contient la clé PSK de la TV et la clé privée d'association : elle ne doit jamais circuler en clair.
 */
object BackupCrypto {

    private const val PREFIX = "ECOTV1:"
    private const val ITERATIONS = 200_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    /** Mot de passe trop court : refusé à l'export. */
    const val MIN_PASSPHRASE = 6

    class WrongPassphraseException : GeneralSecurityException("Mot de passe incorrect ou sauvegarde abîmée")

    fun isEncrypted(text: String): Boolean = text.trim().startsWith(PREFIX)

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: String, passphrase: CharArray): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(salt + iv + data, Base64.NO_WRAP)
    }

    /** Déchiffre une sauvegarde produite par [encrypt]. Lève [WrongPassphraseException] si le mot de passe est faux. */
    fun decrypt(text: String, passphrase: CharArray): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) throw IllegalArgumentException("Ce n'est pas une sauvegarde chiffrée")
        val raw = try {
            Base64.decode(trimmed.removePrefix(PREFIX), Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw WrongPassphraseException()
        }
        if (raw.size <= SALT_BYTES + IV_BYTES) throw WrongPassphraseException()
        val salt = raw.copyOfRange(0, SALT_BYTES)
        val iv = raw.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val data = raw.copyOfRange(SALT_BYTES + IV_BYTES, raw.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseException()
        }
    }
}
