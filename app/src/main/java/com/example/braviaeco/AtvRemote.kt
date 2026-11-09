package com.example.braviaeco

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread

private const val ATV_ALIAS = "eco_tv_atvremote"

/** Réglages du décodeur Android TV piloté en Wi-Fi direct. */
object AtvPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    fun ip(ctx: Context): String = sp(ctx).getString("atv_ip", "") ?: ""
    fun paired(ctx: Context): Boolean = sp(ctx).getBoolean("atv_paired", false)

    fun saveIp(ctx: Context, ip: String) {
        sp(ctx).edit().putString("atv_ip", ip.trim()).apply()
    }

    fun savePaired(ctx: Context, paired: Boolean) {
        sp(ctx).edit().putBoolean("atv_paired", paired).apply()
    }
}

/** Encodeur / décodeur protobuf minimal (entiers et champs de longueur variable). */
internal class Pb {
    private val out = ByteArrayOutputStream()

    private fun raw(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    fun int(field: Int, value: Int): Pb {
        raw((field shl 3).toLong())
        raw(value.toLong())
        return this
    }

    fun bytes(field: Int, value: ByteArray): Pb {
        raw(((field shl 3) or 2).toLong())
        raw(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    fun str(field: Int, value: String): Pb = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, value: Pb): Pb = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        /** Champ → dernière valeur lue : Long (entier) ou ByteArray (texte, sous-message). */
        fun parse(data: ByteArray): Map<Int, Any> {
            val map = HashMap<Int, Any>()
            var pos = 0
            while (pos < data.size) {
                var tag = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Message tronqué")
                    val b = data[pos].toInt() and 0xFF
                    pos++
                    tag = tag or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                    if (shift > 63) throw IOException("Message illisible")
                }
                val field = (tag ushr 3).toInt()
                when ((tag and 7L).toInt()) {
                    0 -> {
                        var value = 0L
                        var s = 0
                        while (true) {
                            if (pos >= data.size) throw IOException("Message tronqué")
                            val b = data[pos].toInt() and 0xFF
                            pos++
                            value = value or ((b and 0x7F).toLong() shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                            if (s > 63) throw IOException("Message illisible")
                        }
                        map[field] = value
                    }
                    1 -> pos += 8
                    2 -> {
                        var len = 0
                        var s = 0
                        while (true) {
                            if (pos >= data.size) throw IOException("Message tronqué")
                            val b = data[pos].toInt() and 0xFF
                            pos++
                            len = len or ((b and 0x7F) shl s)
                            if ((b and 0x80) == 0) break
                            s += 7
                            if (s > 28) throw IOException("Message illisible")
                        }
                        if (len < 0 || pos + len > data.size) throw IOException("Message tronqué")
                        map[field] = data.copyOfRange(pos, pos + len)
                        pos += len
                    }
                    5 -> pos += 4
                    else -> throw IOException("Message illisible")
                }
            }
            return map
        }
    }
}

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

/**
 * Télécommande Android TV « v2 » (celle de l'appli Google TV) : pilote le décodeur directement
 * en Wi-Fi, sans passer par la TV ni le HDMI-CEC. Association une seule fois par code (port 6467),
 * puis touches et lancement d'applications (port 6466). À appeler hors du thread principal.
 */
object AtvRemote {

    private const val PAIRING_PORT = 6467
    private const val REMOTE_PORT = 6466
    private const val FEATURES = 622
    private const val STALE_MS = 15000L

    private val lock = Any()
    private val writeLock = Any()
    private var socket: SSLSocket? = null

    @Volatile
    private var lastSeen = 0L

    @Volatile
    private var cachedContext: SSLContext? = null

    private val KEYS: Map<String, Int> = mapOf(
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "confirm" to 23, "enter" to 66,
        "return" to 4, "back" to 4, "home" to 3, "options" to 82,
        "volumeup" to 24, "volumedown" to 25, "mute" to 164, "power" to 26,
        "channelup" to 166, "channeldown" to 167,
        "play" to 126, "pause" to 127, "stop" to 86, "rewind" to 89, "forward" to 90,
        "next" to 87, "prev" to 88,
        "red" to 183, "green" to 184, "yellow" to 185, "blue" to 186,
        "epg" to 172, "guide" to 172, "display" to 165,
        "num0" to 7, "num1" to 8, "num2" to 9, "num3" to 10, "num4" to 11,
        "num5" to 12, "num6" to 13, "num7" to 14, "num8" to 15, "num9" to 16
    )

    /** Code Android de la touche portant ce nom (noms de la télécommande Sony), ou null. */
    fun keyCode(name: String): Int? = KEYS[name.lowercase()]

    // ---------------------------------------------------------------- certificat et TLS

    private fun keyStore(): KeyStore {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        return ks
    }

    private fun certificate(): X509Certificate {
        val existing = keyStore().getCertificate(ATV_ALIAS)
        if (existing is X509Certificate) return existing

        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            ATV_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setCertificateSubject(X500Principal("CN=atvremote"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 20L * 365L * 86_400_000L))
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS
            )
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(spec)
        generator.generateKeyPair()
        return keyStore().getCertificate(ATV_ALIAS) as X509Certificate
    }

    private fun sslContext(): SSLContext {
        cachedContext?.let { return it }
        val cert = certificate()
        val key = keyStore().getKey(ATV_ALIAS, null) as PrivateKey
        // Le décodeur présente un certificat auto-signé : c'est l'association par code qui fait foi.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf<KeyManager>(AtvKeyManager(key, cert)), arrayOf<TrustManager>(trustAll), null)
        cachedContext = context
        return context
    }

    private fun open(ip: String, port: Int, readTimeoutMs: Int): SSLSocket {
        val s = sslContext().socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(ip, port), 3000)
            s.soTimeout = 8000
            s.startHandshake()
            s.soTimeout = readTimeoutMs
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    private fun writeFrame(out: OutputStream, message: Pb) {
        val payload = message.toByteArray()
        val buf = ByteArrayOutputStream()
        var v = payload.size
        while (v >= 0x80) {
            buf.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        buf.write(v)
        buf.write(payload, 0, payload.size)
        synchronized(writeLock) {
            out.write(buf.toByteArray())
            out.flush()
        }
    }

    private fun readFrame(input: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connexion fermée par le décodeur")
            len = len or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 21) throw IOException("Message illisible")
        }
        val data = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(data, off, len - off)
            if (n < 0) throw EOFException("Connexion fermée par le décodeur")
            off += n
        }
        return data
    }

    private fun friendly(e: Throwable): Throwable = when (e) {
        is SSLException -> IllegalStateException("Décodeur : association refusée, refais-la dans les réglages")
        is IOException -> IllegalStateException("Décodeur injoignable en Wi-Fi (allumé ? adresse IP ?)")
        else -> e
    }

    private fun <T> friendly(result: Result<T>): Result<T> {
        val e = result.exceptionOrNull() ?: return result
        return Result.failure(friendly(e))
    }

    // ---------------------------------------------------------------- association

    /** Association en cours : le code à 6 caractères est affiché sur la TV. */
    class Pairing internal constructor(
        private val socket: SSLSocket,
        private val clientCert: X509Certificate
    ) {
        fun finish(ctx: Context, code: String): Result<Unit> {
            val result = runCatching {
                try {
                    val hex = code.trim().uppercase()
                    if (!Regex("[0-9A-F]{6}").matches(hex)) error("Le code comporte 6 caractères (0-9, A-F)")
                    val codeBytes = ByteArray(3) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

                    val server = socket.session.peerCertificates[0].publicKey as RSAPublicKey
                    val client = clientCert.publicKey as RSAPublicKey
                    val md = MessageDigest.getInstance("SHA-256")
                    md.update(unsigned(client.modulus))
                    md.update(unsigned(client.publicExponent))
                    md.update(unsigned(server.modulus))
                    md.update(unsigned(server.publicExponent))
                    md.update(codeBytes, 1, 2)
                    val hash = md.digest()
                    if (hash[0] != codeBytes[0]) error("Code incorrect")

                    writeFrame(socket.outputStream, outer().msg(40, Pb().bytes(1, hash)))
                    expectOk(socket.inputStream)
                    AtvPrefs.savePaired(ctx, true)
                    disconnect()
                } finally {
                    close()
                }
            }
            return friendly(result)
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0].toInt() == 0) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun outer(): Pb = Pb().int(1, 2).int(2, 200)

    private fun expectOk(input: InputStream) {
        val msg = Pb.parse(readFrame(input))
        val status = (msg[2] as? Long) ?: 0L
        if (status != 200L) error("Association refusée par le décodeur (code $status)")
    }

    /** Démarre l'association : le décodeur affiche un code à l'écran. Puis appeler [Pairing.finish]. */
    fun startPairing(ctx: Context): Result<Pairing> {
        val result = runCatching {
            val ip = AtvPrefs.ip(ctx)
            if (ip.isEmpty()) error("Adresse IP du décodeur non configurée")
            val cert = certificate()
            val s = open(ip, PAIRING_PORT, 60000)
            try {
                val out = s.outputStream
                val input = s.inputStream
                val encoding = Pb().int(1, 3).int(2, 6)
                writeFrame(out, outer().msg(10, Pb().str(1, "atvremote").str(2, "Eco TV Sony")))
                expectOk(input)
                writeFrame(out, outer().msg(20, Pb().msg(1, encoding).int(3, 1)))
                expectOk(input)
                writeFrame(out, outer().msg(30, Pb().msg(1, encoding).int(2, 1)))
                expectOk(input)
                Pairing(s, cert)
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }
        }
        return friendly(result)
    }

    // ---------------------------------------------------------------- télécommande

    fun disconnect() {
        synchronized(lock) {
            socket?.let { s -> runCatching { s.close() } }
            socket = null
        }
    }

    /** Connexion prête à l'emploi (à appeler avec [lock] tenu). */
    private fun connection(ctx: Context): SSLSocket {
        val current = socket
        if (current != null && !current.isClosed && System.currentTimeMillis() - lastSeen < STALE_MS) {
            return current
        }
        if (current != null) runCatching { current.close() }
        socket = null

        val ip = AtvPrefs.ip(ctx)
        if (ip.isEmpty() || !AtvPrefs.paired(ctx)) error("Décodeur non associé (Réglages > Décodeur)")

        val s = open(ip, REMOTE_PORT, 20000)
        val ready = CountDownLatch(1)
        lastSeen = System.currentTimeMillis()
        thread(isDaemon = true, name = "atv-remote") { readLoop(s, ready) }
        ready.await(4, TimeUnit.SECONDS)
        if (s.isClosed) throw IOException("Le décodeur a fermé la connexion")
        socket = s
        return s
    }

    /** Répond aux messages du décodeur (configuration, activation, « ping » de maintien). */
    private fun readLoop(s: SSLSocket, ready: CountDownLatch) {
        try {
            val input = s.inputStream
            val out = s.outputStream
            while (true) {
                val msg = Pb.parse(readFrame(input))
                lastSeen = System.currentTimeMillis()
                if (msg.containsKey(1)) {
                    val info = Pb().int(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0")
                    writeFrame(out, Pb().msg(1, Pb().int(1, FEATURES).msg(2, info)))
                } else if (msg.containsKey(2)) {
                    writeFrame(out, Pb().msg(2, Pb().int(1, FEATURES)))
                    ready.countDown()
                } else if (msg.containsKey(8)) {
                    val ping = Pb.parse(msg[8] as? ByteArray ?: ByteArray(0))
                    val value = (ping[1] as? Long) ?: 0L
                    writeFrame(out, Pb().msg(9, Pb().int(1, value.toInt())))
                } else if (msg.containsKey(40)) {
                    ready.countDown()
                }
            }
        } catch (e: Exception) {
            // connexion perdue : elle sera rouverte à la prochaine touche
        } finally {
            runCatching { s.close() }
            ready.countDown()
        }
    }

    private fun send(ctx: Context, message: Pb): Result<Unit> {
        val result = runCatching {
            synchronized(lock) {
                try {
                    writeFrame(connection(ctx).outputStream, message)
                } catch (e: IOException) {
                    // Connexion périmée : une seule nouvelle tentative sur une connexion neuve.
                    socket?.let { s -> runCatching { s.close() } }
                    socket = null
                    writeFrame(connection(ctx).outputStream, message)
                }
            }
        }
        return friendly(result)
    }

    /** Appui court sur une touche (code Android KeyEvent). */
    fun sendKey(ctx: Context, keyCode: Int): Result<Unit> =
        send(ctx, Pb().msg(10, Pb().int(1, keyCode).int(2, 3)))

    /** Ouvre une application du décodeur : nom de paquet (com.netflix.ninja) ou lien (https://…). */
    fun launch(ctx: Context, link: String): Result<Unit> {
        val text = link.trim()
        val target = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(text)) text else "market://launch?id=$text"
        return send(ctx, Pb().msg(90, Pb().str(1, target)))
    }
}
