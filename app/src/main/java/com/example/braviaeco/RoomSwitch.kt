package com.example.braviaeco

import android.Manifest
import android.app.Activity
import android.content.Context
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
        METHOD_BT -> decide(BtRoom.level(ctx, Prefs.DEVICE_SONY), BtRoom.level(ctx, Prefs.DEVICE_EDENWOOD))
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
     * TV vers laquelle basculer, ou null s'il n'y a rien à changer. On ne répond que quand la pièce
     * détectée change : un choix fait à la main par le bouton « télécommande » reste donc respecté
     * tant qu'on ne change pas de pièce.
     */
    fun pick(ctx: Context): String? {
        if (!enabled(ctx)) return null
        val room = guess(ctx) ?: return null
        if (room == sp(ctx).getString("room_last", null)) return null
        sp(ctx).edit().putString("room_last", room).apply()
        return room
    }

    /**
     * En mode Bluetooth, lance un scan (~10 s) puis rappelle onSwitch si la pièce a changé.
     * Le premier [pick] d'onResume utilise la mesure précédente ; celle-ci corrige ensuite.
     */
    fun follow(activity: Activity, onSwitch: (String) -> Unit) {
        if (!enabled(activity) || effectiveMethod(activity) != METHOD_BT) return
        BtRoom.refresh(activity) {
            if (activity.isFinishing || activity.isDestroyed) return@refresh
            pick(activity)?.let(onSwitch)
        }
    }
}
