package com.example.braviaeco

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.pm.PackageManager
import android.net.wifi.WifiManager

/**
 * Bascule automatique entre la télécommande Sony et la Edenwood selon la pièce où l'on se trouve.
 *
 * Le téléphone ne peut pas mesurer le signal d'une TV : on utilise celui des points d'accès du mesh.
 * Chaque point d'accès (identifié par son BSSID) est associé une fois à une TV, quand on se tient
 * près de lui ; ensuite le plus fort des deux groupes désigne la pièce. Android demande pour cela
 * la permission de localisation (et la localisation activée) pour lire les BSSID.
 */
object RoomSwitch {

    private const val UNKNOWN_BSSID = "02:00:00:00:00:00"

    /** Écart minimal (dB) entre les deux pièces pour trancher ; en dessous, on ne change rien. */
    private const val MARGIN_DB = 6

    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("room_auto", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("room_auto", on).remove("room_last").apply()
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    const val METHOD_WIFI = "wifi"
    const val METHOD_BT = "bt"

    /** Méthode choisie : bornes Wi-Fi du mesh ou Bluetooth des TV. */
    fun method(ctx: Context): String = sp(ctx).getString("room_method", METHOD_WIFI) ?: METHOD_WIFI

    fun setMethod(ctx: Context, m: String) {
        sp(ctx).edit().putString("room_method", m).remove("room_last").apply()
    }

    /** Repli : si le Wi-Fi (ou le Bluetooth) du téléphone est coupé, on utilise l'autre méthode. */
    fun fallback(ctx: Context): Boolean = sp(ctx).getBoolean("room_fallback", true)

    fun setFallback(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("room_fallback", on).remove("room_last").apply()
    }

    fun wifiOn(ctx: Context): Boolean = runCatching { wifi(ctx)?.isWifiEnabled == true }.getOrDefault(false)

    private fun wifiReady(ctx: Context) = wifiOn(ctx) && hasPermission(ctx) &&
        (accessPoints(ctx, Prefs.DEVICE_SONY).isNotEmpty() || accessPoints(ctx, Prefs.DEVICE_EDENWOOD).isNotEmpty())

    private fun btReady(ctx: Context) = BtRoom.isOn(ctx) && BtRoom.hasPermission(ctx) &&
        (BtRoom.associated(ctx, Prefs.DEVICE_SONY) || BtRoom.associated(ctx, Prefs.DEVICE_EDENWOOD))

    /** Méthode réellement utilisée maintenant (null : aucune possible). */
    fun effectiveMethod(ctx: Context): String? {
        val wifi = wifiReady(ctx)
        val bt = btReady(ctx)
        return when (method(ctx)) {
            METHOD_BT -> if (bt) METHOD_BT else if (fallback(ctx) && !BtRoom.isOn(ctx) && wifi) METHOD_WIFI else null
            else -> if (wifi) METHOD_WIFI else if (fallback(ctx) && !wifiOn(ctx) && bt) METHOD_BT else null
        }
    }

    private fun key(device: String) = "room_aps_$device"

    /** Points d'accès (BSSID en majuscules) associés à une TV : [Prefs.DEVICE_SONY] ou [Prefs.DEVICE_EDENWOOD]. */
    fun accessPoints(ctx: Context, device: String): Set<String> =
        (sp(ctx).getString(key(device), "") ?: "").split(',').filter { it.isNotBlank() }.toSet()

    private fun saveAps(ctx: Context, device: String, aps: Set<String>) {
        sp(ctx).edit().putString(key(device), aps.joinToString(",")).apply()
    }

    /** Associe un point d'accès à une TV (et le retire de l'autre). */
    fun addAccessPoint(ctx: Context, device: String, bssid: String) {
        val other = if (device == Prefs.DEVICE_SONY) Prefs.DEVICE_EDENWOOD else Prefs.DEVICE_SONY
        saveAps(ctx, device, accessPoints(ctx, device) + bssid)
        saveAps(ctx, other, accessPoints(ctx, other) - bssid)
        sp(ctx).edit().remove("room_last").apply()
    }

    fun clearAccessPoints(ctx: Context) {
        sp(ctx).edit().remove(key(Prefs.DEVICE_SONY)).remove(key(Prefs.DEVICE_EDENWOOD)).remove("room_last").apply()
    }

    private fun wifi(ctx: Context): WifiManager? =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** Point d'accès auquel le téléphone est connecté (BSSID, force en dBm), ou null si illisible. */
    @Suppress("DEPRECATION")
    fun current(ctx: Context): Pair<String, Int>? {
        if (!hasPermission(ctx)) return null
        val info = runCatching { wifi(ctx)?.connectionInfo }.getOrNull() ?: return null
        val bssid = info.bssid?.uppercase() ?: return null
        if (bssid == UNKNOWN_BSSID) return null
        return bssid to info.rssi
    }

    /** TV de la pièce où l'on est (null : inconnu ou trop proche pour trancher). */
    @Suppress("DEPRECATION")
    private fun guess(ctx: Context): String? = when (effectiveMethod(ctx)) {
        METHOD_WIFI -> guessWifi(ctx)
        // Bluetooth : mesures du dernier scan, prises depuis le démarrage / réveil.
        METHOD_BT -> decide(BtRoom.level(ctx, Prefs.DEVICE_SONY, btSince()), BtRoom.level(ctx, Prefs.DEVICE_EDENWOOD, btSince()))
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun guessWifi(ctx: Context): String? {
        val sony = accessPoints(ctx, Prefs.DEVICE_SONY)
        val eden = accessPoints(ctx, Prefs.DEVICE_EDENWOOD)
        if (sony.isEmpty() && eden.isEmpty()) return null

        // Dernière mesure de chaque point d'accès visible ; la connexion en cours est la plus à jour.
        val levels = HashMap<String, Int>()
        runCatching {
            val wm = wifi(ctx)
            wm?.startScan() // limité par Android : sert surtout à rafraîchir pour le prochain appel
            wm?.scanResults?.forEach { r -> r.BSSID?.let { levels[it.uppercase()] = r.level } }
        }
        current(ctx)?.let { (bssid, level) -> levels[bssid] = level }

        val s = sony.mapNotNull { levels[it] }.maxOrNull()
        val e = eden.mapNotNull { levels[it] }.maxOrNull()
        return decide(s, e)
    }

    /** Pièce selon les deux signaux (dBm) ; null si rien vu ou trop proche pour trancher. */
    private fun decide(s: Int?, e: Int?): String? {
        return when {
            s == null && e == null -> null
            e == null -> Prefs.DEVICE_SONY
            s == null -> Prefs.DEVICE_EDENWOOD
            s - e >= MARGIN_DB -> Prefs.DEVICE_SONY
            e - s >= MARGIN_DB -> Prefs.DEVICE_EDENWOOD
            else -> null
        }
    }

    /**
     * Détection « armée » (démarrage de l'appli ou réveil du téléphone) : la TV la plus proche
     * est choisie d'office. Ensuite, pendant l'utilisation, on surveille en continu et on bascule
     * quand la pièce détectée change (confirmée par 2 mesures de suite) ; un choix fait à la main
     * reste respecté tant qu'on ne change pas de pièce.
     */
    @Volatile
    private var armed = true

    @Volatile
    private var armedAt = 0L

    private var screenWatch = false
    private var lastRoom: String? = null
    private var candidate: String? = null
    private var candidateCount = 0

    private const val CONFIRM = 2
    private const val WIFI_EVERY_MS = 10_000L
    private const val BT_PAUSE_MS = 5_000L
    private const val IDLE_EVERY_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private var watching: Activity? = null
    private var onSwitchCb: ((String) -> Unit)? = null

    private fun arm() {
        armed = true
        armedAt = SystemClock.elapsedRealtime()
        candidate = null
        BtRoom.forceNext()
    }

    /** Mesures Bluetooth à prendre en compte : dernier scan terminé, et pas d'avant le réveil. */
    private fun btSince(): Long = maxOf(armedAt, BtRoom.lastScanStart)

    /** Écoute l'extinction de l'écran (une seule fois par processus) : le réveil suivant réarme. */
    private fun watchScreen(ctx: Context) {
        if (screenWatch) return
        screenWatch = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) arm()
            }
        }
        runCatching {
            val app = ctx.applicationContext
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
        }
    }

    /** TV vers laquelle basculer maintenant, ou null. */
    fun pick(ctx: Context): String? {
        watchScreen(ctx)
        if (!enabled(ctx)) return null
        val method = effectiveMethod(ctx)
        val room = guess(ctx)
        if (armed) {
            // Bluetooth sans mesure récente : on attend la fin du scan.
            if (room != null || method != METHOD_BT) armed = false
            if (room != null) { lastRoom = room; candidate = null }
            return room
        }
        if (room == null) return null
        if (room == lastRoom) { candidate = null; return null }
        if (room == candidate) candidateCount++ else { candidate = room; candidateCount = 1 }
        if (candidateCount < CONFIRM) return null
        lastRoom = room
        candidate = null
        return room
    }

    /** Démarrage de l'appli (icône) : redétection de la TV à proximité. */
    fun startup(ctx: Context) {
        watchScreen(ctx)
        if (!enabled(ctx)) return
        arm()
        if (effectiveMethod(ctx) == METHOD_BT) Ui.toast(ctx, "Recherche de la TV à proximité…")
    }

    /**
     * Surveillance continue tant que la télécommande est à l'écran (arrêtée par [stop] dans onPause) :
     * Bluetooth = scan, pause de 5 s, scan… ; Wi-Fi = lecture toutes les 10 s.
     */
    fun follow(activity: Activity, onSwitch: (String) -> Unit) {
        watchScreen(activity)
        handler.removeCallbacksAndMessages(null)
        watching = activity
        onSwitchCb = onSwitch
        handler.post { tick(activity) }
    }

    fun stop(activity: Activity) {
        if (watching !== activity) return
        watching = null
        onSwitchCb = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun alive(activity: Activity) =
        watching === activity && !activity.isFinishing && !activity.isDestroyed

    private fun deliver(activity: Activity) {
        if (!alive(activity)) return
        pick(activity)?.let { room -> onSwitchCb?.invoke(room) }
    }

    private fun tick(activity: Activity) {
        if (!alive(activity)) return
        val method = if (enabled(activity)) effectiveMethod(activity) else null
        when (method) {
            METHOD_BT -> {
                BtRoom.forceNext()
                val started = BtRoom.refresh(activity) {
                    deliver(activity)
                    if (alive(activity)) handler.postDelayed({ tick(activity) }, BT_PAUSE_MS)
                }
                if (!started) handler.postDelayed({ tick(activity) }, BT_PAUSE_MS)
            }
            METHOD_WIFI -> {
                deliver(activity)
                handler.postDelayed({ tick(activity) }, WIFI_EVERY_MS)
            }
            else -> handler.postDelayed({ tick(activity) }, IDLE_EVERY_MS)
        }
    }
}
