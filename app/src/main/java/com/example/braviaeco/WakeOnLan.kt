package com.example.braviaeco

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Calculs d'adresses IPv4 (entiers de 32 bits) : sous-réseau, diffusion. */
object Subnet {
    /** « 192.168.1.20 » → entier ; null si ce n'est pas une adresse IPv4. */
    fun parse(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            value = (value shl 8) or n
        }
        return value
    }

    fun format(value: Int): String =
        "${(value ushr 24) and 255}.${(value ushr 16) and 255}.${(value ushr 8) and 255}.${value and 255}"

    private fun mask(prefix: Int): Int = if (prefix <= 0) 0 else -1 shl (32 - prefix)

    fun broadcast(ip: Int, prefix: Int): Int = ip or mask(prefix).inv()

    fun same(a: Int, b: Int, prefix: Int): Boolean = (a and mask(prefix)) == (b and mask(prefix))
}

/** Réveil réseau (« paquet magique » Wake-on-LAN) et petits outils réseau. À appeler hors du thread principal. */
object WakeOnLan {

    /** Adresse MAC mise au format AA:BB:CC:DD:EE:FF (séparateurs : ou - ou aucun), ou null si invalide. */
    fun normalize(text: String): String? {
        val hex = text.filter { it.isLetterOrDigit() }.uppercase()
        if (!Regex("[0-9A-F]{12}").matches(hex)) return null
        return hex.chunked(2).joinToString(":")
    }

    /** Adresse de diffusion du sous-réseau Wi-Fi du téléphone, d'après son vrai masque ; null si inconnue. */
    fun subnetBroadcast(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val mine = cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = Subnet.parse((mine.address as Inet4Address).hostAddress.orEmpty()) ?: return null
        if (mine.prefixLength !in 8..30) return null
        Subnet.format(Subnet.broadcast(ip, mine.prefixLength))
    }.getOrNull()

    /**
     * Envoie le paquet magique (diffusion générale, diffusion du sous-réseau, puis directement à l'IP).
     * [broadcast] : adresse de diffusion réelle du sous-réseau (voir [subnetBroadcast]) ; à défaut, on suppose un /24.
     */
    fun send(mac: String, ip: String, broadcast: String? = null): Boolean {
        val text = normalize(mac) ?: return false
        return runCatching {
            val bytes = ByteArray(6) { text.split(':')[it].toInt(16).toByte() }
            val packet = ByteArray(102)
            for (i in 0 until 6) packet[i] = 0xFF.toByte()
            for (i in 1..16) System.arraycopy(bytes, 0, packet, i * 6, 6)

            val targets = LinkedHashSet<String>()
            targets.add("255.255.255.255")
            if (broadcast != null) {
                targets.add(broadcast)
            } else {
                val dot = ip.lastIndexOf('.')
                if (dot > 0) targets.add(ip.substring(0, dot) + ".255")
            }
            if (ip.isNotEmpty()) targets.add(ip)

            val socket = DatagramSocket()
            try {
                socket.broadcast = true
                for (target in targets) {
                    for (port in intArrayOf(9, 7)) {
                        runCatching {
                            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(target), port))
                        }
                    }
                }
            } finally {
                socket.close()
            }
            true
        }.getOrDefault(false)
    }

    /** true si l'appareil accepte une connexion sur ce port (TV allumée, ou veille « réseau » active). */
    fun reachable(ip: String, port: Int, timeoutMs: Int = 1200): Boolean {
        if (ip.isEmpty()) return false
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            true
        }.getOrDefault(false)
    }

    /**
     * Lit l'adresse MAC de l'appareil dans la table ARP du téléphone (TV allumée). Android récent
     * (10 et plus) interdit souvent cette lecture aux applis : renvoie alors null, la saisie à la main reste possible.
     */
    fun learnMac(ip: String, port: Int): String? {
        if (ip.isEmpty()) return null
        reachable(ip, port) // remplit la table ARP
        return runCatching {
            File("/proc/net/arp").readLines().drop(1).firstNotNullOfOrNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[0] == ip && parts[3] != "00:00:00:00:00:00") normalize(parts[3]) else null
            }
        }.getOrNull()
    }
}
