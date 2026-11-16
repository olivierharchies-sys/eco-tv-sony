package com.example.braviaeco

import android.content.Context
import java.security.MessageDigest
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

private const val ATV_ALIAS = "eco_tv_atvremote"

/** Présente toujours notre certificat au décodeur. */
private class AtvKeyManager(
    private val key: PrivateKey,
    private val cert: X509Certificate
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = ATV_ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = ATV_ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = arrayOf(ATV_ALIAS)
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = arrayOf(cert)
    override fun getPrivateKey(alias: String?): PrivateKey? = key
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

/** Contextes TLS des appareils Android TV : certificat client de l'appli, certificat du serveur épinglé. */
internal object AtvTls {

    /** Contexte TLS par appareil : chacun vérifie l'empreinte du certificat de son propre appareil. */
    private val contexts = ConcurrentHashMap<AtvDevice, SSLContext>()

    /** Oublie les contextes et l'identité gardés en mémoire (après une restauration de sauvegarde). */
    fun reset() {
        AtvIdentity.clear()
        contexts.clear()
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /**
     * Vérifie le certificat de l'appareil. Un appareil Android TV présente un certificat auto-signé, qui ne
     * peut pas être validé par une autorité : on l'épingle donc. Le code à 6 caractères de l'association a
     * authentifié l'appareil ; la première connexion qui suit mémorise l'empreinte de son certificat, et toute
     * connexion ultérieure doit présenter le même. Pendant l'association elle-même ([enforce] = false), rien
     * n'est vérifié ici : c'est le code saisi (qui contient la clé de l'appareil) qui fait foi.
     */
    private class PinningTrustManager(
        private val app: Context,
        private val device: AtvDevice,
        private val enforce: Boolean
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!enforce) return
            val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Certificat absent")
            val seen = fingerprint(leaf)
            synchronized(PIN_LOCK) {
                val pinned = device.pin(app)
                if (pinned.isEmpty()) {
                    device.savePin(app, seen)
                } else if (!pinned.equals(seen, ignoreCase = true)) {
                    throw java.security.cert.CertificateException(
                        "le certificat de ${device.label} a changé : refais l'association"
                    )
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val PIN_LOCK = Any()

    fun context(ctx: Context, device: AtvDevice, enforce: Boolean): SSLContext {
        if (enforce) contexts[device]?.let { return it }
        val app = ctx.applicationContext ?: ctx
        val id = AtvIdentity.get(app)
        val context = SSLContext.getInstance("TLS")
        context.init(
            arrayOf<KeyManager>(AtvKeyManager(id.key, id.cert)),
            arrayOf<TrustManager>(PinningTrustManager(app, device, enforce)),
            null
        )
        if (enforce) contexts[device] = context
        return context
    }
}
