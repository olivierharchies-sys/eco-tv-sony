package com.example.braviaeco

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.security.auth.x500.X500Principal

/**
 * Identité de l'appli auprès des appareils Android TV : une clé RSA et un certificat auto-signé,
 * créés une fois puis gardés dans les réglages.
 */
internal object AtvIdentity {

    class Identity(val key: PrivateKey, val cert: X509Certificate)

    /** Protège la création / le chargement de la clé et du certificat. */
    private val lock = Any()

    @Volatile
    private var cached: Identity? = null

    /** Oublie l'identité gardée en mémoire (elle sera relue dans les réglages au prochain usage). */
    fun clear() {
        synchronized(lock) { cached = null }
    }

    /** Encode un élément DER : étiquette, longueur, contenu. */
    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (part in parts) body.write(part, 0, part.size)
        val content = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        if (n < 0x80) {
            out.write(n)
        } else if (n < 0x100) {
            out.write(0x81)
            out.write(n)
        } else {
            out.write(0x82)
            out.write(n ushr 8)
            out.write(n and 0xFF)
        }
        out.write(content, 0, content.size)
        return out.toByteArray()
    }

    /**
     * Date X.509 : UTCTime (2 chiffres pour l'année) jusqu'en 2049, GeneralizedTime au-delà.
     * Sans cela, une date en 2050 serait lue comme 1950 et le certificat serait aussitôt expiré.
     */
    private fun derTime(millis: Long): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { timeInMillis = millis }.get(java.util.Calendar.YEAR)
        val general = year >= 2050
        val format = SimpleDateFormat(if (general) "yyyyMMddHHmmss'Z'" else "yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = utc
        return der(if (general) 0x18 else 0x17, format.format(Date(millis)).toByteArray(Charsets.US_ASCII))
    }

    /** Certificat X.509 auto-signé (CN=atvremote, RSA, SHA-256) construit à la main. */
    private fun selfSigned(pair: KeyPair): ByteArray {
        val now = System.currentTimeMillis()
        // sha256WithRSAEncryption
        val algorithm = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
            0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00
        )
        val name = X500Principal("CN=atvremote").encoded
        val tbs = der(
            0x30,
            der(0xA0, der(0x02, byteArrayOf(2))),
            der(0x02, BigInteger.valueOf(now).toByteArray()),
            algorithm,
            name,
            der(0x30, derTime(now - 86_400_000L), derTime(now + 20L * 365L * 86_400_000L)),
            name,
            pair.public.encoded
        )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(pair.private)
        signer.update(tbs)
        return der(0x30, tbs, algorithm, der(0x03, byteArrayOf(0), signer.sign()))
    }

    /**
     * Clé et certificat présentés au décodeur, créés une fois puis gardés dans les réglages de l'appli
     * (clé logicielle : aucune restriction du coffre Android pendant la poignée de main TLS).
     */
    fun get(ctx: Context): Identity {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val sp = AppPrefs.sp(ctx)
            val factory = CertificateFactory.getInstance("X.509")
            val savedKey = sp.getString("atv_key", null)
            val savedCert = sp.getString("atv_cert", null)
            if (savedKey != null && savedCert != null) {
                val loaded = runCatching {
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(savedKey, Base64.NO_WRAP)))
                    val cert = factory.generateCertificate(
                        ByteArrayInputStream(Base64.decode(savedCert, Base64.NO_WRAP))
                    ) as X509Certificate
                    Identity(key, cert)
                }.getOrNull()
                if (loaded != null) {
                    cached = loaded
                    return loaded
                }
            }
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            val certBytes = selfSigned(pair)
            val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            sp.edit()
                .putString("atv_key", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
                .putString("atv_cert", Base64.encodeToString(certBytes, Base64.NO_WRAP))
                .putBoolean("atv_paired", false)
                .putBoolean("atv_tv_paired", false)
                .putBoolean("atv_edenwood_paired", false)
                .apply()
            val created = Identity(pair.private, cert)
            cached = created
            return created
        }
    }
}
