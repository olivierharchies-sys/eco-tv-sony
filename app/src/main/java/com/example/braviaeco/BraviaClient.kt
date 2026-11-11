package com.example.braviaeco

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Les 4 modes d'économie d'énergie exposés par l'API Bravia de Sony. */
enum class EcoMode(val api: String, val label: String) {
    OFF("off", "Off"),
    LOW("low", "Bas"),
    HIGH("high", "Haut"),
    PICTURE_OFF("pictureOff", "Image off");

    companion object {
        fun fromApi(value: String?): EcoMode? = values().firstOrNull { it.api == value }
    }
}

/** Une entrée externe de la TV (HDMI 1, HDMI 2, composite…). */
data class TvInput(
    val uri: String,
    val title: String,
    val label: String,
    val connected: Boolean
) {
    val display: String
        get() = if (label.isNotBlank() && !label.equals(title, ignoreCase = true)) "$title · $label" else title
}

/** Une application installée sur la TV (navigateur, Netflix, YouTube…). */
data class TvApp(val title: String, val uri: String)

/** Réglages enregistrés sur le téléphone. */
object Prefs {
    private const val FILE = "bravia_eco"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun ip(ctx: Context): String = sp(ctx).getString("ip", "") ?: ""
    fun psk(ctx: Context): String = sp(ctx).getString("psk", "") ?: ""

    fun saveConnection(ctx: Context, ip: String, psk: String) {
        val edit = sp(ctx).edit().putString("ip", ip.trim()).putString("psk", psk.trim())
        if (ip.trim() != this.ip(ctx)) edit.remove("mac")
        edit.apply()
    }

    /** Adresse MAC de la TV, lue quand elle est allumée : sert au réveil réseau (Wake-on-LAN). */
    fun mac(ctx: Context): String = sp(ctx).getString("mac", "") ?: ""

    fun saveMac(ctx: Context, mac: String) {
        sp(ctx).edit().putString("mac", mac).apply()
    }

    fun lastMode(ctx: Context): EcoMode? = EcoMode.fromApi(sp(ctx).getString("mode", null))

    fun saveMode(ctx: Context, mode: EcoMode) {
        sp(ctx).edit().putString("mode", mode.api).apply()
    }

    /** Dernier état connu de la TV : true = allumée, false = en veille, null = inconnu. */
    fun lastPower(ctx: Context): Boolean? = when (sp(ctx).getString("power", null)) {
        "on" -> true
        "off" -> false
        else -> null
    }

    fun savePower(ctx: Context, on: Boolean) {
        sp(ctx).edit().putString("power", if (on) "on" else "off").apply()
    }

    // --- Bouton Web : page à ouvrir et navigateur (application de la TV) choisi
    const val DEFAULT_WEB_URL = "https://www.google.com"
    const val DEFAULT_DEVICE_HOME = "SyncMenu"

    fun webUrl(ctx: Context): String = sp(ctx).getString("web_url", DEFAULT_WEB_URL) ?: DEFAULT_WEB_URL
    fun webAppUri(ctx: Context): String = sp(ctx).getString("web_app_uri", "") ?: ""
    fun webAppTitle(ctx: Context): String = sp(ctx).getString("web_app_title", "") ?: ""

    fun saveWeb(ctx: Context, url: String) {
        sp(ctx).edit().putString("web_url", url.trim()).apply()
    }

    fun saveWebApp(ctx: Context, app: TvApp?) {
        sp(ctx).edit()
            .putString("web_app_uri", app?.uri ?: "")
            .putString("web_app_title", app?.title ?: "")
            .apply()
    }

    // --- Bouton « Home appareil » : touche envoyée à l'appareil HDMI sélectionné (via HDMI-CEC)
    fun deviceHomeKey(ctx: Context): String =
        (sp(ctx).getString("device_home", DEFAULT_DEVICE_HOME) ?: DEFAULT_DEVICE_HOME).ifBlank { DEFAULT_DEVICE_HOME }

    fun saveDeviceHomeKey(ctx: Context, key: String) {
        sp(ctx).edit().putString("device_home", key.trim()).apply()
    }

    // --- Télécommande : appareil visé et type de pavé
    fun target(ctx: Context): Target =
        if (sp(ctx).getString("target", "tv") == "decoder") Target.DECODER else Target.TV

    fun saveTarget(ctx: Context, target: Target) {
        sp(ctx).edit().putString("target", if (target == Target.DECODER) "decoder" else "tv").apply()
    }

    // --- Télécommande affichée : classique ou « Zen » (gestes)
    const val STYLE_CLASSIC = "classic"
    const val STYLE_ZEN = "zen"

    fun remoteStyle(ctx: Context): String = sp(ctx).getString("remote_style", STYLE_CLASSIC) ?: STYLE_CLASSIC

    fun saveRemoteStyle(ctx: Context, style: String) {
        sp(ctx).edit().putString("remote_style", style).apply()
    }

    fun touchpad(ctx: Context): Boolean = sp(ctx).getBoolean("touchpad", false)

    fun saveTouchpad(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("touchpad", on).apply()
    }

    /** Source affichée quand une application de la TV est au premier plan. */
    const val SOURCE_APP = "Application"

    /** URI fictive renvoyée par la synchronisation dans ce même cas. */
    const val APP_URI = "app:"

    /** Dernière source choisie depuis l'appli (pour l'affichage). */
    fun lastSource(ctx: Context): String? = sp(ctx).getString("source", null)

    fun saveSource(ctx: Context, name: String) {
        sp(ctx).edit().putString("source", name).apply()
    }
}

/** Le clavier système de la TV n'est pas affiché : la TV refuse le texte envoyé d'un bloc. */
class NoTextFieldException : IllegalStateException("Aucun champ de saisie ouvert sur la TV")

/** Client minimal pour l'API Bravia (REST + IRCC). À appeler hors du thread principal. */
object BraviaClient {

    private const val IRCC_ACTION = "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\""

    /** Codes IRCC de la télécommande, lus une fois sur la TV (clé = IP pour invalider si elle change). */
    @Volatile
    private var codeCache: Pair<String, Map<String, String>>? = null

    // ---------------------------------------------------------------- économie d'énergie

    fun setMode(ctx: Context, mode: EcoMode): Result<Unit> = runCatching {
        val params = JSONArray().put(JSONObject().put("mode", mode.api))
        call(ctx, "system", "setPowerSavingMode", params)
        Prefs.saveMode(ctx, mode)
    }

    fun getMode(ctx: Context): Result<EcoMode> = runCatching {
        val result = call(ctx, "system", "getPowerSavingMode", JSONArray())
        val value = result.optJSONObject(0)?.optString("mode")
        val mode = EcoMode.fromApi(value) ?: error("Mode inconnu : $value")
        Prefs.saveMode(ctx, mode)
        mode
    }

    // ---------------------------------------------------------------- alimentation

    fun setPower(ctx: Context, on: Boolean): Result<Unit> = runCatching {
        call(ctx, "system", "setPowerStatus", JSONArray().put(JSONObject().put("status", on)))
        Prefs.savePower(ctx, on)
    }

    /** true si la TV est allumée. Échoue si elle est injoignable. */
    fun getPowerOn(ctx: Context): Result<Boolean> = runCatching {
        val result = call(ctx, "system", "getPowerStatus", JSONArray())
        val on = result.optJSONObject(0)?.optString("status") == "active"
        Prefs.savePower(ctx, on)
        if (on) rememberMac(ctx)
        on
    }

    /**
     * Bascule marche/arrêt : renvoie le nouvel état demandé (true = allumée).
     * Si la TV ne répond pas (veille profonde), envoie un réveil réseau puis réessaie.
     */
    fun togglePower(ctx: Context): Result<Boolean> = runCatching { toggle(ctx) }

    private fun powerParams(on: Boolean) = JSONArray().put(JSONObject().put("status", on))

    private fun toggle(ctx: Context): Boolean {
        val current: Boolean? = try {
            val result = call(ctx, "system", "getPowerStatus", JSONArray(), timeoutMs = 1500)
            result.optJSONObject(0)?.optString("status") == "active"
        } catch (e: Exception) {
            null
        }
        if (current != null) {
            if (current) rememberMac(ctx)
            val target = !current
            call(ctx, "system", "setPowerStatus", powerParams(target))
            Prefs.savePower(ctx, target)
            return target
        }

        return wake(ctx)
    }

    /** TV injoignable : réveil réseau, puis demande d'allumage. Renvoie true ou échoue. */
    private fun wake(ctx: Context): Boolean {
        if (Prefs.ip(ctx).isEmpty()) error("Adresse IP de la TV non configurée")
        if (!wakeOnLan(ctx)) error("TV injoignable : vérifie le Wi-Fi et l'adresse IP")
        for (attempt in 1..2) {
            Thread.sleep(1500)
            try {
                call(ctx, "system", "setPowerStatus", powerParams(true), timeoutMs = 1500)
                Prefs.savePower(ctx, true)
                return true
            } catch (e: Exception) {
                // pas encore réveillée
            }
        }
        error("Réveil réseau envoyé : la TV ne répond pas encore, réessaie")
    }

    /** Allume la TV si besoin. Renvoie true s'il a fallu l'allumer (elle démarre), false si elle l'était déjà. */
    fun powerOn(ctx: Context): Result<Boolean> = runCatching { ensureOn(ctx) }

    private fun ensureOn(ctx: Context): Boolean {
        val current: Boolean? = try {
            val result = call(ctx, "system", "getPowerStatus", JSONArray(), timeoutMs = 1500)
            result.optJSONObject(0)?.optString("status") == "active"
        } catch (e: Exception) {
            null
        }
        if (current == true) {
            Prefs.savePower(ctx, true)
            return false
        }
        if (current == false) {
            call(ctx, "system", "setPowerStatus", powerParams(true))
            Prefs.savePower(ctx, true)
            return true
        }
        return wake(ctx)
    }

    /** Mémorise l'adresse MAC de la TV (une seule fois) pour pouvoir la réveiller par le réseau. */
    private fun rememberMac(ctx: Context) {
        if (Prefs.mac(ctx).isNotEmpty()) return
        runCatching {
            var mac = call(ctx, "system", "getSystemInformation", JSONArray())
                .optJSONObject(0)?.optString("macAddr").orEmpty()
            if (!isMac(mac)) {
                val params = JSONArray().put(JSONObject().put("netif", ""))
                val nets = call(ctx, "system", "getNetworkSettings", params).optJSONArray(0) ?: JSONArray()
                val ip = Prefs.ip(ctx)
                for (i in 0 until nets.length()) {
                    val o = nets.optJSONObject(i) ?: continue
                    val hw = o.optString("hwAddr")
                    if (!isMac(hw)) continue
                    if (!isMac(mac) || o.optString("ipAddrV4") == ip) mac = hw
                }
            }
            if (isMac(mac)) Prefs.saveMac(ctx, mac)
        }
    }

    private fun isMac(value: String): Boolean =
        Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}").matches(value)

    /** Envoie le « paquet magique » Wake-on-LAN. false si l'adresse MAC n'est pas encore connue. */
    private fun wakeOnLan(ctx: Context): Boolean {
        val text = Prefs.mac(ctx)
        if (!isMac(text)) return false
        return runCatching {
            val parts = text.split(':', '-')
            val mac = ByteArray(6) { parts[it].toInt(16).toByte() }
            val packet = ByteArray(102)
            for (i in 0 until 6) packet[i] = 0xFF.toByte()
            for (i in 1..16) System.arraycopy(mac, 0, packet, i * 6, 6)

            val targets = LinkedHashSet<String>()
            targets.add("255.255.255.255")
            val ip = Prefs.ip(ctx)
            val dot = ip.lastIndexOf('.')
            if (dot > 0) targets.add(ip.substring(0, dot) + ".255")

            val socket = java.net.DatagramSocket()
            try {
                socket.broadcast = true
                for (target in targets) {
                    for (port in intArrayOf(9, 7)) {
                        runCatching {
                            val address = java.net.InetAddress.getByName(target)
                            socket.send(java.net.DatagramPacket(packet, packet.size, address, port))
                        }
                    }
                }
            } finally {
                socket.close()
            }
            true
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- clavier

    /** Remplace le texte du champ de saisie affiché sur la TV. */
    fun setText(ctx: Context, text: String): Result<Unit> = runCatching {
        try {
            call(ctx, "appControl", "setTextForm", JSONArray().put(text))
        } catch (e: IllegalStateException) {
            // Erreur 7 (Illegal State) : le clavier système de la TV n'est pas à l'écran
            // (aucun champ de saisie, ou application avec son propre clavier).
            if (e.message?.endsWith("(7)") == true) throw NoTextFieldException()
            throw e
        }
        Unit
    }

    // ---------------------------------------------------------------- sources

    fun getInputs(ctx: Context): Result<List<TvInput>> = runCatching {
        val result = call(ctx, "avContent", "getCurrentExternalInputsStatus", JSONArray())
        val arr = result.optJSONArray(0) ?: JSONArray()
        val list = ArrayList<TvInput>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isEmpty()) continue
            list.add(
                TvInput(
                    uri = uri,
                    title = o.optString("title", uri),
                    label = o.optString("label", ""),
                    connected = o.optBoolean("connection", true)
                )
            )
        }
        list
    }

    /**
     * URI de la source réellement affichée (ex. « extInput:hdmi?port=1 », « tv:dvbt »).
     * Échoue si la TV est en veille ou si une de ses applications est au premier plan.
     */
    fun currentInputUri(ctx: Context): Result<String> = runCatching {
        val o = call(ctx, "avContent", "getPlayingContentInfo", JSONArray(), timeoutMs = 2000).optJSONObject(0)
        val uri = o?.optString("uri").orEmpty()
        if (uri.isEmpty()) error("Source inconnue")
        uri
    }

    /**
     * Deux URI désignent la même entrée si elles sont identiques ou si ce sont deux entrées externes
     * sur le même port : la TV peut ajouter ou changer des paramètres (adresse logique HDMI-CEC…)
     * après un redémarrage, ce qui rendait la comparaison stricte fausse.
     */
    fun sameInput(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        if (!a.startsWith("extInput:", ignoreCase = true) || !b.startsWith("extInput:", ignoreCase = true)) return false
        val re = Regex("[?&]port=(\\d+)", RegexOption.IGNORE_CASE)
        val pa = re.find(a)?.groupValues?.get(1) ?: return false
        val pb = re.find(b)?.groupValues?.get(1) ?: return false
        return pa == pb
    }

    /**
     * Aligne la source mémorisée sur la source réelle (changée avec la vraie télécommande, par exemple).
     * Renvoie l'URI de la source réelle. À appeler TV allumée : si elle répond sans donner de source,
     * c'est qu'une de ses applications est au premier plan ([Prefs.APP_URI]).
     */
    fun syncSource(ctx: Context): Result<String> = runCatching {
        val read = currentInputUri(ctx)
        val failure = read.exceptionOrNull()
        if (failure is IllegalStateException && failure.message?.startsWith("TV :") == true) {
            Prefs.saveSource(ctx, Prefs.SOURCE_APP)
            return@runCatching Prefs.APP_URI
        }
        val uri = read.getOrThrow()
        val name: String? = if (!uri.startsWith("extInput:", ignoreCase = true)) {
            "TV"
        } else {
            getInputs(ctx).getOrNull()?.firstOrNull { it.uri.equals(uri, ignoreCase = true) }?.display
        }
        if (name != null) Prefs.saveSource(ctx, name)
        uri
    }

    fun setInput(ctx: Context, input: TvInput): Result<Unit> {
        val viaApi = runCatching {
            call(ctx, "avContent", "setPlayContent", JSONArray().put(JSONObject().put("uri", input.uri)))
            Unit
        }
        if (viaApi.isSuccess) return viaApi

        // Repli : la touche HDMI correspondante de la télécommande.
        val port = Regex("port=(\\d+)").find(input.uri)?.groupValues?.get(1)
        if (port != null && input.uri.contains("hdmi", ignoreCase = true)) {
            val viaKey = sendIrcc(ctx, "Hdmi$port")
            if (viaKey.isSuccess) return viaKey
        }
        return viaApi
    }

    // ---------------------------------------------------------------- Home, web, applications

    /** Home de la TV elle-même. */
    fun sendTvHome(ctx: Context): Result<Unit> = sendIrcc(ctx, "Home")

    /**
     * Home du périphérique sélectionné : si la source est la TV, c'est le Home de la TV ;
     * sinon la touche réglée dans l'appli (par défaut « SyncMenu », le menu de l'appareil HDMI-CEC).
     */
    fun sendDeviceHome(ctx: Context): Result<Unit> {
        // Source réelle lue sur la TV ; à défaut, la dernière source choisie dans l'appli.
        val uri = currentInputUri(ctx).getOrNull()
        val onTv = if (uri != null) !uri.startsWith("extInput:", ignoreCase = true) else Prefs.lastSource(ctx).let { it == "TV" || it == Prefs.SOURCE_APP }
        if (onTv) return sendTvHome(ctx)
        return sendIrcc(ctx, Prefs.deviceHomeKey(ctx))
    }

    fun getApps(ctx: Context): Result<List<TvApp>> = runCatching {
        val result = call(ctx, "appControl", "getApplicationList", JSONArray())
        val arr = result.optJSONArray(0) ?: JSONArray()
        val list = ArrayList<TvApp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isEmpty()) continue
            list.add(TvApp(o.optString("title", uri), uri))
        }
        list.sortedBy { it.title.lowercase() }
    }

    fun launchApp(ctx: Context, uri: String, data: String? = null): Result<Unit> = runCatching {
        val o = JSONObject().put("uri", uri)
        if (!data.isNullOrEmpty()) o.put("data", data)
        call(ctx, "appControl", "setActiveApp", JSONArray().put(o))
        Unit
    }

    /** Ouvre la page web choisie, dans le navigateur choisi (ou le navigateur intégré par défaut). */
    fun openWeb(ctx: Context): Result<Unit> {
        var url = Prefs.webUrl(ctx).trim()
        if (url.isEmpty()) return Result.failure(IllegalStateException("Aucune page web réglée"))
        if (!url.contains("://")) url = "https://$url"

        val appUri = Prefs.webAppUri(ctx)
        if (appUri.isNotEmpty()) return launchApp(ctx, appUri, url)

        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        return launchApp(ctx, "localapp://webappruntime?url=$encoded")
    }

    // ---------------------------------------------------------------- volume, infos, redémarrage

    /** Volume du haut-parleur : (volume, maximum, muet). */
    fun getVolume(ctx: Context): Result<Triple<Int, Int, Boolean>> = runCatching {
        val result = call(ctx, "audio", "getVolumeInformation", JSONArray())
        val arr = result.optJSONArray(0) ?: JSONArray()
        var chosen: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (chosen == null || o.optString("target") == "speaker") chosen = o
        }
        val o = chosen ?: error("Volume indisponible")
        Triple(o.optInt("volume"), o.optInt("maxVolume", 100), o.optBoolean("mute"))
    }

    fun setVolume(ctx: Context, volume: Int): Result<Unit> = runCatching {
        val o = JSONObject().put("target", "speaker").put("volume", volume.toString())
        call(ctx, "audio", "setAudioVolume", JSONArray().put(o))
        Unit
    }

    /** Informations système et réseau de la TV, mises en forme « clé : valeur ». */
    fun getInfo(ctx: Context): Result<String> = runCatching {
        val sb = StringBuilder()
        fun dump(title: String, obj: JSONObject?) {
            if (obj == null) return
            sb.append(title).append('\n')
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.opt(k)?.toString().orEmpty()
                if (v.isNotEmpty()) sb.append("  ").append(k).append(" : ").append(v).append('\n')
            }
            sb.append('\n')
        }
        dump("Système", call(ctx, "system", "getSystemInformation", JSONArray()).optJSONObject(0))
        runCatching {
            dump("Interface", call(ctx, "system", "getInterfaceInformation", JSONArray()).optJSONObject(0))
        }
        runCatching {
            dump("En cours de lecture", call(ctx, "avContent", "getPlayingContentInfo", JSONArray()).optJSONObject(0))
        }
        sb.toString().trim()
    }

    fun reboot(ctx: Context): Result<Unit> = runCatching {
        call(ctx, "system", "requestReboot", JSONArray())
        Unit
    }

    /** Noms de toutes les touches de télécommande connues de la TV. */
    fun keyNames(ctx: Context): Result<List<String>> = runCatching {
        val result = call(ctx, "system", "getRemoteControllerInfo", JSONArray())
        val list = result.optJSONArray(1) ?: error("Liste des touches indisponible")
        val names = ArrayList<String>()
        for (i in 0 until list.length()) {
            val n = list.optJSONObject(i)?.optString("name").orEmpty()
            if (n.isNotEmpty()) names.add(n)
        }
        names.sortBy { it.lowercase() }
        names
    }

    // ---------------------------------------------------------------- télécommande (IRCC)

    /** Envoie une touche. Plusieurs noms possibles : le premier connu de la TV est utilisé. */
    fun sendIrcc(ctx: Context, vararg names: String): Result<Unit> = runCatching {
        val codes = remoteCodes(ctx)
        val code = names.firstNotNullOfOrNull { codes[it.lowercase()] }
            ?: error("Touche « ${names.first()} » non supportée par cette TV")
        postIrcc(ctx, code)
    }

    private fun remoteCodes(ctx: Context): Map<String, String> {
        val ip = Prefs.ip(ctx)
        codeCache?.let { if (it.first == ip) return it.second }

        val result = call(ctx, "system", "getRemoteControllerInfo", JSONArray())
        val list = result.optJSONArray(1) ?: error("Liste des touches indisponible")
        val map = HashMap<String, String>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val value = o.optString("value")
            if (name.isNotEmpty() && value.isNotEmpty()) map[name.lowercase()] = value
        }
        codeCache = ip to map
        return map
    }

    private fun postIrcc(ctx: Context, code: String) {
        val ip = Prefs.ip(ctx)
        val psk = Prefs.psk(ctx)
        if (ip.isEmpty()) error("Adresse IP de la TV non configurée")

        val body = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\">" +
            "<IRCCCode>$code</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>"

        // Une touche n'est renvoyée que si la connexion elle-même a échoué (rien n'est parti) :
        // jamais de double appui.
        var attempt = 0
        while (true) {
            attempt++
            val conn = URL("http://$ip/sony/IRCC").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 2500
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=UTF-8")
                conn.setRequestProperty("SOAPACTION", IRCC_ACTION)
                if (psk.isNotEmpty()) conn.setRequestProperty("X-Auth-PSK", psk)
                try {
                    conn.connect()
                } catch (e: java.io.IOException) {
                    if (attempt < 2) {
                        Thread.sleep(250)
                        continue
                    }
                    error("TV injoignable (Wi-Fi du téléphone ? TV en veille ?)")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status == 403) error("Accès refusé : vérifie la clé PSK sur la TV")
                if (status !in 200..299) error("Erreur HTTP $status")
                conn.inputStream.use { it.readBytes() }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---------------------------------------------------------------- appel JSON générique

    /** Appel JSON-RPC brut, pour le diagnostic et les réglages d'image ([Picture]). */
    fun rawCall(
        ctx: Context,
        service: String,
        method: String,
        params: JSONArray,
        version: String = "1.0"
    ): Result<JSONArray> = runCatching { call(ctx, service, method, params, version) }

    private fun call(
        ctx: Context,
        service: String,
        method: String,
        params: JSONArray,
        version: String = "1.0",
        timeoutMs: Int = 4000
    ): JSONArray {
        val ip = Prefs.ip(ctx)
        val psk = Prefs.psk(ctx)
        if (ip.isEmpty()) error("Adresse IP de la TV non configurée")

        val body = JSONObject()
            .put("method", method)
            .put("id", 1)
            .put("params", params)
            .put("version", version)
            .toString()

        val conn = URL("http://$ip/sony/$service").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs + 2000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (psk.isNotEmpty()) conn.setRequestProperty("X-Auth-PSK", psk)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code == 403) error("Accès refusé : vérifie la clé PSK sur la TV")
            if (code !in 200..299) error("Erreur HTTP $code")

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (json.has("error")) {
                val err = json.getJSONArray("error")
                error("TV : ${err.optString(1, "erreur")} (${err.optInt(0)})")
            }
            return json.optJSONArray("result") ?: JSONArray()
        } finally {
            conn.disconnect()
        }
    }
}
