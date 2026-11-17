package com.example.braviaeco

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

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

    /** Taille maximale acceptée pour un message de l'appareil (les vrais messages font quelques centaines d'octets). */
    private const val MAX_FRAME = 256 * 1024

    /** Connexion ouverte vers un appareil. */
    private class Link {
        /** Un verrou par appareil : un appareil lent ou injoignable ne bloque pas les touches des autres. */
        val lock = Any()

        @Volatile
        var socket: SSLSocket? = null

        @Volatile
        var lastSeen = 0L

        /** Compteurs du clavier de l'appareil (messages 20, 21, 22), à renvoyer avec le texte. */
        @Volatile
        var imeCounter = 0

        @Volatile
        var fieldCounter = 0

        @Volatile
        var hasIme = false

        @Volatile
        var hasField = false

        /** État annoncé par l'appareil : allumé (message 40), volume (message 50). */
        @Volatile
        var power: Boolean? = null

        @Volatile
        var volume: Int? = null

        @Volatile
        var volumeMax: Int? = null

        @Volatile
        var muted: Boolean? = null
    }

    /** Appelé (thread réseau) quand un appareil annonce son état marche/veille ou son volume. */
    @Volatile
    var onStatus: ((AtvDevice) -> Unit)? = null

    /** Allumé (true), en veille (false) ou inconnu (null), selon la dernière annonce de l'appareil. */
    fun powerState(device: AtvDevice): Boolean? = link(device).power

    /** Volume annoncé : (actuel, maximum, muet), ou null. */
    fun volumeState(device: AtvDevice): Triple<Int, Int, Boolean>? {
        val l = link(device)
        val v = l.volume ?: return null
        return Triple(v, l.volumeMax ?: 100, l.muted ?: false)
    }

    private val links = mapOf(AtvDevice.DECODER to Link(), AtvDevice.TV to Link(), AtvDevice.EDENWOOD to Link())

    private fun link(device: AtvDevice): Link = links.getValue(device)

    private fun deviceOf(l: Link): AtvDevice? = links.entries.firstOrNull { it.value === l }?.key

    private val KEYS: Map<String, Int> = mapOf(
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "confirm" to 23, "enter" to 66,
        "return" to 4, "back" to 4, "home" to 3, "options" to 82,
        "volumeup" to 24, "volumedown" to 25, "mute" to 164, "power" to 26,
        "channelup" to 166, "channeldown" to 167,
        "play" to 126, "pause" to 127, "playpause" to 85, "stop" to 86, "rewind" to 89, "forward" to 90,
        "next" to 87, "prev" to 88,
        "red" to 183, "green" to 184, "yellow" to 185, "blue" to 186,
        "epg" to 172, "guide" to 172, "display" to 165, "search" to 84,
        "input" to 178, "settings" to 176, "info" to 165, "sleep" to 223,
        "num0" to 7, "num1" to 8, "num2" to 9, "num3" to 10, "num4" to 11,
        "num5" to 12, "num6" to 13, "num7" to 14, "num8" to 15, "num9" to 16
    )

    /** Code Android de la touche portant ce nom (noms de la télécommande Sony), ou null. */
    fun keyCode(name: String): Int? = KEYS[name.lowercase()]

    // ---------------------------------------------------------------- connexion TLS

    private fun open(ctx: Context, device: AtvDevice, ip: String, port: Int, readTimeoutMs: Int, enforce: Boolean = true): SSLSocket {
        val s = AtvTls.context(ctx, device, enforce).socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(ip, port), 3000)
            // Chaque touche est un petit message : sans cela, l'algorithme de Nagle peut le retenir
            // quelques dizaines de ms avant l'envoi, ce qui rend les déplacements saccadés.
            s.tcpNoDelay = true
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
        // Un flux de sortie par connexion : le verrouiller suffit à ne pas mélanger deux messages.
        synchronized(out) {
            out.write(buf.toByteArray())
            out.flush()
        }
    }

    private fun readFrame(input: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connexion fermée par l'appareil")
            len = len or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 21) throw IOException("Message illisible")
        }
        // La taille vient du réseau : sans plafond, un appareil défaillant ou usurpé pourrait faire réserver
        // des centaines de Mo (OutOfMemoryError, qui plante l'appli).
        if (len < 0 || len > MAX_FRAME) throw IOException("Message trop grand ($len octets)")
        val data = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(data, off, len - off)
            if (n < 0) throw EOFException("Connexion fermée par l'appareil")
            off += n
        }
        return data
    }

    /** Détail technique court, pour comprendre un échec sans journal. */
    private fun detail(e: Throwable): String {
        val root = e.cause ?: e
        val text = (root.message ?: e.message ?: "").replace('\n', ' ').take(90)
        return root.javaClass.simpleName + (if (text.isEmpty()) "" else " : $text")
    }

    private fun friendly(e: Throwable, pairing: Boolean, device: AtvDevice): Throwable = when (e) {
        is SSLException -> IllegalStateException(
            if (pairing) "${device.label} : connexion sécurisée refusée (${detail(e)})"
            else "${device.label} : association à refaire dans les réglages (${detail(e)})"
        )
        is IOException -> IllegalStateException("${device.label} injoignable en Wi-Fi (${detail(e)})")
        else -> e
    }

    private fun <T> friendly(result: Result<T>, pairing: Boolean, device: AtvDevice): Result<T> {
        val e = result.exceptionOrNull() ?: return result
        return Result.failure(friendly(e, pairing, device))
    }

    // ---------------------------------------------------------------- association

    /** Association en cours : le code à 6 caractères est affiché sur la TV. */
    class Pairing internal constructor(
        private val socket: SSLSocket,
        private val clientCert: X509Certificate,
        private val device: AtvDevice
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
                    device.savePaired(ctx, true)
                    disconnect(device)
                } finally {
                    close()
                }
            }
            return friendly(result, true, device)
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
        if (status != 200L) error("Association refusée par l'appareil (code $status)")
    }

    /** Démarre l'association : l'appareil affiche un code à l'écran. Puis appeler [Pairing.finish]. */
    fun startPairing(ctx: Context, device: AtvDevice = AtvDevice.DECODER): Result<Pairing> {
        val result = runCatching {
            val ip = device.ip(ctx)
            if (ip.isEmpty()) error("Adresse IP non configurée (${device.label})")
            val cert = AtvIdentity.get(ctx).cert
            // Pas d'épinglage ici : c'est le code saisi qui authentifie l'appareil ; l'empreinte de son
            // certificat est mémorisée à la première connexion de télécommande qui suit.
            val s = open(ctx, device, ip, PAIRING_PORT, 60000, enforce = false)
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
                Pairing(s, cert, device)
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }
        }
        return friendly(result, true, device)
    }

    // ---------------------------------------------------------------- télécommande

    /**
     * Oublie la clé, le certificat et les connexions gardés en mémoire. À appeler après une restauration
     * de sauvegarde : sinon l'appli continuerait à présenter l'ancienne identité tant qu'elle n'est pas relancée.
     * À appeler hors du thread principal (ferme des connexions réseau).
     */
    fun reset() {
        AtvTls.reset()
        for (device in AtvDevice.values()) disconnect(device)
    }

    /**
     * Ferme la connexion à l'appareil. Volontairement sans verrou : si un envoi est bloqué sur une connexion
     * morte, la fermer le libère au lieu d'attendre la fin de son délai.
     */
    fun disconnect(device: AtvDevice = AtvDevice.DECODER) {
        val l = link(device)
        val s = l.socket
        l.socket = null
        if (s != null) runCatching { s.close() }
    }

    /** Connexion prête à l'emploi (à appeler avec le verrou de l'appareil, [Link.lock], tenu). */
    private fun connection(ctx: Context, device: AtvDevice): SSLSocket {
        val l = link(device)
        val current = l.socket
        if (current != null && !current.isClosed && System.currentTimeMillis() - l.lastSeen < STALE_MS) {
            return current
        }
        if (current != null) runCatching { current.close() }
        l.socket = null

        val ip = device.ip(ctx)
        if (ip.isEmpty() || !device.paired(ctx)) {
            error(
                when (device) {
                    AtvDevice.TV -> "Clavier de cette appli : associe d'abord la TV (Réglages > Associer la TV)"
                    AtvDevice.EDENWOOD -> "Edenwood non associée (Réglages > TV Edenwood)"
                    AtvDevice.DECODER -> "Décodeur non associé (Réglages > Décodeur)"
                }
            )
        }

        val s = open(ctx, device, ip, REMOTE_PORT, 20000)
        l.imeCounter = 0
        l.fieldCounter = 0
        l.hasIme = false
        l.hasField = false
        val ready = CountDownLatch(1)
        l.lastSeen = System.currentTimeMillis()
        Bg.run { readLoop(s, ready, l) }
        ready.await(4, TimeUnit.SECONDS)
        if (s.isClosed) throw IOException("L'appareil a fermé la connexion")
        l.socket = s
        return s
    }

    /** Répond aux messages de l'appareil (configuration, activation, « ping » de maintien). */
    private fun readLoop(s: SSLSocket, ready: CountDownLatch, l: Link) {
        try {
            val input = s.inputStream
            val out = s.outputStream
            while (true) {
                val msg = Pb.parse(readFrame(input))
                l.lastSeen = System.currentTimeMillis()
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
                } else if (msg.containsKey(20)) {
                    // Application / champ de saisie au premier plan : compteurs à renvoyer avec le texte.
                    val inject = Pb.parse(msg[20] as? ByteArray ?: ByteArray(0))
                    val app = Pb.parse(inject[1] as? ByteArray ?: ByteArray(0))
                    val field = Pb.parse(inject[2] as? ByteArray ?: ByteArray(0))
                    l.imeCounter = ((app[1] as? Long) ?: 0L).toInt()
                    l.fieldCounter = ((field[1] as? Long) ?: 0L).toInt()
                    l.hasIme = l.imeCounter != 0
                    l.hasField = l.fieldCounter != 0
                } else if (msg.containsKey(22)) {
                    val show = Pb.parse(msg[22] as? ByteArray ?: ByteArray(0))
                    val status = show[2] as? ByteArray
                    if (status != null) {
                        l.fieldCounter = ((Pb.parse(status)[1] as? Long) ?: 0L).toInt()
                        l.hasField = true
                    }
                } else if (msg.containsKey(21)) {
                    val edit = Pb.parse(msg[21] as? ByteArray ?: ByteArray(0))
                    if (!l.hasIme) l.imeCounter = ((edit[1] as? Long) ?: 0L).toInt()
                    if (!l.hasField) l.fieldCounter = ((edit[2] as? Long) ?: 0L).toInt()
                } else if (msg.containsKey(40)) {
                    // Marche / veille : champ 1 (absent = false).
                    val start = Pb.parse(msg[40] as? ByteArray ?: ByteArray(0))
                    l.power = ((start[1] as? Long) ?: 0L) != 0L
                    ready.countDown()
                    deviceOf(l)?.let { d -> runCatching { onStatus?.invoke(d) } }
                } else if (msg.containsKey(50)) {
                    // Volume : max (6), actuel (7), muet (8).
                    val vol = Pb.parse(msg[50] as? ByteArray ?: ByteArray(0))
                    l.volumeMax = ((vol[6] as? Long) ?: 0L).toInt().takeIf { it > 0 }
                    l.volume = ((vol[7] as? Long) ?: 0L).toInt()
                    l.muted = ((vol[8] as? Long) ?: 0L) != 0L
                    deviceOf(l)?.let { d -> runCatching { onStatus?.invoke(d) } }
                }
            }
        } catch (e: Exception) {
            // connexion perdue : elle sera rouverte à la prochaine touche
        } finally {
            runCatching { s.close() }
            ready.countDown()
        }
    }

    private fun send(ctx: Context, message: Pb, device: AtvDevice): Result<Unit> {
        val l = link(device)
        val result = runCatching {
            synchronized(l.lock) {
                try {
                    writeFrame(connection(ctx, device).outputStream, message)
                } catch (e: IOException) {
                    // Connexion périmée : une seule nouvelle tentative sur une connexion neuve.
                    l.socket?.let { s -> runCatching { s.close() } }
                    l.socket = null
                    writeFrame(connection(ctx, device).outputStream, message)
                }
            }
        }
        return friendly(result, false, device)
    }

    /** Ouvre d'avance la connexion à l'appareil : la première touche ne paie plus la poignée de main TLS. */
    fun warmUp(ctx: Context, device: AtvDevice) {
        runCatching { synchronized(link(device).lock) { connection(ctx, device) } }
    }

    /** Sens de l'appui : début d'appui long, fin d'appui long, ou appui court. */
    const val PRESS_START_LONG = 1
    const val PRESS_END_LONG = 2
    const val PRESS_SHORT = 3

    /** Appui sur une touche (code Android KeyEvent) ; court par défaut. */
    fun sendKey(
        ctx: Context,
        keyCode: Int,
        device: AtvDevice = AtvDevice.DECODER,
        direction: Int = PRESS_SHORT
    ): Result<Unit> =
        send(ctx, Pb().msg(10, Pb().int(1, keyCode).int(2, direction)), device)

    /** Ouvre une application du décodeur : nom de paquet (com.netflix.ninja) ou lien (https://…). */
    fun launch(ctx: Context, link: String, device: AtvDevice = AtvDevice.DECODER): Result<Unit> {
        val text = link.trim()
        val target = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(text)) text else "market://launch?id=$text"
        return send(ctx, Pb().msg(90, Pb().str(1, target)), device)
    }

    // ---------------------------------------------------------------- saisie par le clavier de l'appareil

    private const val IME_WAIT_MS = 400L

    /**
     * Fait passer le champ de saisie ouvert sur l'appareil de [from] à [to] : effacements, puis
     * insertion de la suite au curseur par le clavier de l'appareil (majuscules et accents compris).
     * Renvoie false si l'appareil n'a annoncé aucun champ de saisie : il faut alors passer par [type].
     */
    fun sendText(ctx: Context, from: String, to: String, device: AtvDevice): Result<Boolean> = runCatching {
        val l = link(device)
        friendly(runCatching { synchronized(l.lock) { connection(ctx, device) } }, false, device).getOrThrow()
        // Connexion toute neuve : l'annonce du champ de saisie arrive juste après.
        if (l.imeCounter == 0 && l.fieldCounter == 0) Thread.sleep(IME_WAIT_MS)
        if (l.imeCounter == 0 && l.fieldCounter == 0) return@runCatching false
        val keep = from.commonPrefixWith(to).length
        repeat(from.length - keep) {
            sendKey(ctx, KEY_DEL, device).getOrThrow()
            Thread.sleep(TYPE_DELAY_MS)
        }
        val added = to.substring(keep)
        if (added.isNotEmpty()) {
            val status = Pb().int(1, added.length).int(2, added.length).str(3, added)
            val edit = Pb().int(1, l.imeCounter).int(2, l.fieldCounter).msg(3, Pb().int(1, 1).msg(2, status))
            send(ctx, Pb().msg(21, edit), device).getOrThrow()
        }
        true
    }

    // ---------------------------------------------------------------- saisie touche par touche

    private const val KEY_DEL = 67
    private const val TYPE_DELAY_MS = 40L

    /** Caractères qu'une touche de clavier Android produit sans majuscule ni combinaison. */
    private val CHAR_KEYS: Map<Char, Int> = HashMap<Char, Int>().apply {
        for (c in 'a'..'z') put(c, 29 + (c - 'a'))
        for (c in '0'..'9') put(c, 7 + (c - '0'))
        put(' ', 62)
        put(',', 55)
        put('.', 56)
        put('-', 69)
        put('\'', 75)
        put('/', 76)
        put('@', 77)
    }

    /** Texte réduit à ce que les touches savent écrire : minuscules, sans accents ni symboles rares. */
    fun typable(text: String): String {
        val flat = java.text.Normalizer.normalize(
            text.lowercase().replace('\u2019', '\''),
            java.text.Normalizer.Form.NFD
        )
        return flat.filter { CHAR_KEYS.containsKey(it) }
    }

    /**
     * Pour les applications qui ont leur propre clavier à l'écran : fait passer le texte déjà saisi
     * de [from] à [to] (tous deux issus de [typable]) par des effacements puis des lettres.
     */
    fun type(ctx: Context, from: String, to: String, device: AtvDevice): Result<Unit> = runCatching {
        val keep = from.commonPrefixWith(to).length
        val keys = ArrayList<Int>()
        repeat(from.length - keep) { keys.add(KEY_DEL) }
        for (c in to.substring(keep)) CHAR_KEYS[c]?.let { keys.add(it) }
        for (k in keys) {
            sendKey(ctx, k, device).getOrThrow()
            Thread.sleep(TYPE_DELAY_MS)
        }
    }
}
