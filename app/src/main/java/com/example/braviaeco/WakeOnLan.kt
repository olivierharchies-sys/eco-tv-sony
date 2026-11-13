package com.example.braviaeco

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Réveil réseau (« paquet magique » Wake-on-LAN) et petits outils réseau. À appeler hors du thread principal. */
object WakeOnLan {

    /** Adresse MAC mise au format AA:BB:CC:DD:EE:FF (séparateurs : ou - ou aucun), ou null si invalide. */
    fun normalize(text: String): String? {
        val hex = text.filter { it.isLetterOrDigit() }.uppercase()
        if (!Regex("[0-9A-F]{12}").matches(hex)) return null
        return hex.chunked(2).joinToString(":")
    }

    /** Envoie le paquet magique (diffusion générale et du sous-réseau, puis directement à l'IP). */
    fun send(mac: String, ip: String): Boolean {
        val text = normalize(mac) ?: return false
        return runCatching {
            val bytes = ByteArray(6) { text.split(':')[it].toInt(16).toByte() }
            val packet = ByteArray(102)
            for (i in 0 until 6) packet[i] = 0xFF.toByte()
            for (i in 1..16) System.arraycopy(bytes, 0, packet, i * 6, 6)

            val targets = LinkedHashSet<String>()
            targets.add("255.255.255.255")
            val dot = ip.lastIndexOf('.')
            if (dot > 0) targets.add(ip.substring(0, dot) + ".255")
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
