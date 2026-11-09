package com.example.braviaeco

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Recherche automatique de la TV et du décodeur sur le réseau local. */
object Discovery {

    /** Adresse IP de la TV Sony (réponse SSDP au service IRCC). À appeler hors du thread principal. */
    fun findTv(): Result<String> = runCatching {
        val query = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: urn:schemas-sony-com:service:IRCC:1\r\n\r\n").toByteArray(Charsets.US_ASCII)
        val socket = DatagramSocket()
        try {
            socket.soTimeout = 3500
            val group = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(query, query.size, group, 1900))
            socket.send(DatagramPacket(query, query.size, group, 1900))
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            packet.address?.hostAddress ?: error("Réponse sans adresse")
        } catch (e: SocketTimeoutException) {
            error("Aucune TV Sony trouvée (TV allumée ? même Wi-Fi ?)")
        } finally {
            socket.close()
        }
    }

    /**
     * Adresse IP du décodeur Android TV (annonce mDNS de sa télécommande réseau).
     * [onResult] est appelé une fois sur le thread principal, avec null si rien n'est trouvé.
     */
    fun findDecoder(ctx: Context, excludeIp: String, onResult: (String?) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            ui.post { onResult(null) }
            return
        }
        var done = false
        var listener: NsdManager.DiscoveryListener? = null

        fun finish(ip: String?) {
            ui.post {
                if (!done) {
                    done = true
                    val l = listener
                    if (l != null) runCatching { nsd.stopServiceDiscovery(l) }
                    onResult(ip)
                }
            }
        }

        val created = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                finish(null)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}

                        override fun onServiceResolved(info: NsdServiceInfo?) {
                            val ip = info?.host?.hostAddress
                            if (ip != null && ip != excludeIp && !ip.contains(':')) finish(ip)
                        }
                    })
                }
            }
        }
        listener = created
        val started = runCatching {
            nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, created)
        }
        if (started.isFailure) {
            finish(null)
            return
        }
        ui.postDelayed({ finish(null) }, 7000)
    }
}
