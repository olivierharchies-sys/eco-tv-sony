package com.example.braviaeco

import android.content.Context

/** Réglages enregistrés sur le téléphone. */
object Prefs {

    private fun sp(ctx: Context) = AppPrefs.sp(ctx)

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

    // --- Télécommande affichée : Sony (verte) ou Edenwood (bleue)
    const val DEVICE_SONY = "sony"
    const val DEVICE_EDENWOOD = "edenwood"

    fun remoteDevice(ctx: Context): String = sp(ctx).getString("remote_device", DEVICE_SONY) ?: DEVICE_SONY

    fun saveRemoteDevice(ctx: Context, device: String) {
        sp(ctx).edit().putString("remote_device", device).apply()
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
