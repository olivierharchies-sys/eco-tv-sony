package com.example.braviaeco

import android.content.Context

/** Réglages du décodeur Android TV piloté en Wi-Fi direct. */
object AtvPrefs {
    private fun sp(ctx: Context) = AppPrefs.sp(ctx)

    fun ip(ctx: Context): String = sp(ctx).getString("atv_ip", "") ?: ""
    fun paired(ctx: Context): Boolean = sp(ctx).getBoolean("atv_paired", false)

    fun saveIp(ctx: Context, ip: String) {
        sp(ctx).edit().putString("atv_ip", ip.trim()).apply()
    }

    fun savePaired(ctx: Context, paired: Boolean) {
        AtvDevice.DECODER.savePaired(ctx, paired)
    }
}

/** Réglages de la TV Edenwood (Android TV) pilotée en Wi-Fi. */
object EdenwoodPrefs {
    private fun sp(ctx: Context) = AppPrefs.sp(ctx)

    fun ip(ctx: Context): String = sp(ctx).getString("edenwood_ip", "") ?: ""

    fun saveIp(ctx: Context, ip: String) {
        sp(ctx).edit().putString("edenwood_ip", ip.trim()).apply()
    }

    /** Adresse MAC (AA:BB:CC:DD:EE:FF) pour le réveil réseau ; vide si inconnue. */
    fun mac(ctx: Context): String = sp(ctx).getString("edenwood_mac", "") ?: ""

    fun saveMac(ctx: Context, mac: String) {
        sp(ctx).edit().putString("edenwood_mac", mac).apply()
    }
}

/** Appareil Android TV piloté : le décodeur, la TV Sony (clavier de ses applications) ou la TV Edenwood. */
enum class AtvDevice(val label: String, private val pairedKey: String) {
    DECODER("Décodeur", "atv_paired"),
    TV("TV", "atv_tv_paired"),
    EDENWOOD("Edenwood", "atv_edenwood_paired");

    private fun sp(ctx: Context) = AppPrefs.sp(ctx)

    fun ip(ctx: Context): String = when (this) {
        TV -> Prefs.ip(ctx)
        DECODER -> AtvPrefs.ip(ctx)
        EDENWOOD -> EdenwoodPrefs.ip(ctx)
    }
    fun paired(ctx: Context): Boolean = sp(ctx).getBoolean(pairedKey, false)

    /**
     * Enregistre l'état d'association. Dans les deux cas l'empreinte du certificat de l'appareil est
     * oubliée : elle est relue à la première connexion qui suit une (nouvelle) association.
     */
    fun savePaired(ctx: Context, paired: Boolean) {
        sp(ctx).edit().putBoolean(pairedKey, paired).remove(pinKey).apply()
    }

    private val pinKey: String get() = "atv_pin_" + name.lowercase()

    /** Empreinte SHA-256 (hex) du certificat de l'appareil, vue lors de la première connexion ; vide si inconnue. */
    fun pin(ctx: Context): String = sp(ctx).getString(pinKey, "") ?: ""

    fun savePin(ctx: Context, fingerprint: String) {
        sp(ctx).edit().putString(pinKey, fingerprint).apply()
    }
}
