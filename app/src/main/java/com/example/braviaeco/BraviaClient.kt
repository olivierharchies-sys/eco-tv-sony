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

/** Réglages enregistrés sur le téléphone. */
object Prefs {
    private const val FILE = "bravia_eco"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun ip(ctx: Context): String = sp(ctx).getString("ip", "") ?: ""
    fun psk(ctx: Context): String = sp(ctx).getString("psk", "") ?: ""

    fun saveConnection(ctx: Context, ip: String, psk: String) {
        sp(ctx).edit().putString("ip", ip.trim()).putString("psk", psk.trim()).apply()
    }

    fun lastMode(ctx: Context): EcoMode? = EcoMode.fromApi(sp(ctx).getString("mode", null))

    fun saveMode(ctx: Context, mode: EcoMode) {
        sp(ctx).edit().putString("mode", mode.api).apply()
    }
}

/** Client minimal pour l'API REST Bravia (service "system"). À appeler hors du thread principal. */
object BraviaClient {

    fun setMode(ctx: Context, mode: EcoMode): Result<Unit> = runCatching {
        val params = JSONArray().put(JSONObject().put("mode", mode.api))
        call(ctx, "setPowerSavingMode", params)
        Prefs.saveMode(ctx, mode)
    }

    fun getMode(ctx: Context): Result<EcoMode> = runCatching {
        val result = call(ctx, "getPowerSavingMode", JSONArray())
        val value = result.optJSONObject(0)?.optString("mode")
        val mode = EcoMode.fromApi(value) ?: error("Mode inconnu : $value")
        Prefs.saveMode(ctx, mode)
        mode
    }

    private fun call(ctx: Context, method: String, params: JSONArray): JSONArray {
        val ip = Prefs.ip(ctx)
        val psk = Prefs.psk(ctx)
        if (ip.isEmpty()) error("Adresse IP de la TV non configurée")

        val body = JSONObject()
            .put("method", method)
            .put("id", 1)
            .put("params", params)
            .put("version", "1.0")
            .toString()

        val conn = URL("http://$ip/sony/system").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
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
