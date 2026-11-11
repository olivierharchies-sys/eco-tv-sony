package com.example.braviaeco

import android.content.Context

/** Appareil visé par la croix, les chaînes, la lecture… */
enum class Target { TV, DECODER }

/**
 * Le décodeur : piloté en Wi-Fi direct s'il est associé ([AtvRemote]),
 * sinon par la TV qui relaie les touches en HDMI-CEC. Tout est à appeler hors du thread principal.
 */
object Decoder {

    const val APP_PREFIX = "APP:"

    private const val KEY_HOME = 3
    private const val SWITCH_DELAY_MS = 2500L
    private const val WAKE_DELAY_MS = 6000L
    private const val KEY_GAP_CEC_MS = 450L
    private const val KEY_GAP_WIFI_MS = 250L
    private const val APP_DELAY_MS = 3000L
    private const val WAIT_STEP_MS = 1000L

    /** Applications courantes du décodeur (nom → paquet Android TV). */
    val KNOWN_APPS: List<Pair<String, String>> = listOf(
        "Netflix" to "com.netflix.ninja",
        "YouTube" to "com.google.android.youtube.tv",
        "Prime Video" to "com.amazon.amazonvideo.livingroom",
        "Disney+" to "com.disney.disneyplus"
    )

    fun appLabel(link: String): String = KNOWN_APPS.firstOrNull { it.second == link }?.first ?: link

    /** true si le décodeur est associé en Wi-Fi direct. */
    fun wifi(ctx: Context): Boolean = AtvPrefs.paired(ctx) && AtvPrefs.ip(ctx).isNotEmpty()

    /** Envoie une touche au décodeur (noms de la télécommande Sony ; le premier connu est utilisé). */
    fun sendKey(ctx: Context, vararg names: String): Result<Unit> {
        if (wifi(ctx)) {
            val first = names.first()
            val code: Int? =
                if (first.equals(Prefs.deviceHomeKey(ctx), ignoreCase = true)) KEY_HOME else AtvRemote.keyCode(first)
            if (code != null) return AtvRemote.sendKey(ctx, code)
        }
        return BraviaClient.sendIrcc(ctx, *names)
    }

    /** Accueil du décodeur. */
    fun home(ctx: Context): Result<Unit> =
        if (wifi(ctx)) AtvRemote.sendKey(ctx, KEY_HOME) else BraviaClient.sendDeviceHome(ctx)

    /** TV allumée et réglée sur l'entrée HDMI du décodeur. Renvoie true si quelque chose a changé. */
    private fun prepare(ctx: Context): Boolean {
        var changed = false
        if (BraviaClient.powerOn(ctx).getOrThrow()) {
            changed = true
            Thread.sleep(WAKE_DELAY_MS)
        }
        val inputUri = ShortcutPrefs.decoderInputUri(ctx)
        if (inputUri.isNotEmpty()) {
            val inputName = ShortcutPrefs.decoderInputName(ctx)
            // Source réelle lue sur la TV ; dans le doute, on bascule sur l'entrée du décodeur.
            val current = BraviaClient.currentInputUri(ctx).getOrNull()
            if (current == null || !BraviaClient.sameInput(current, inputUri)) {
                BraviaClient.setInput(ctx, TvInput(inputUri, inputName, "", true)).getOrThrow()
                Prefs.saveSource(ctx, inputName)
                changed = true
                Thread.sleep(SWITCH_DELAY_MS)
            } else if (Prefs.lastSource(ctx) != inputName) {
                Prefs.saveSource(ctx, inputName)
                changed = true
            }
        }
        if (changed) {
            PowerWidgetProvider.refreshAll(ctx)
            SourceWidgetProvider.refreshAll(ctx)
        }
        return changed
    }

    /** Prépare la TV puis envoie la suite de touches (et d'ouvertures d'applications) au décodeur. */
    fun run(ctx: Context, keys: List<String>): Result<Unit> = runCatching {
        val direct = wifi(ctx)
        if (direct) {
            // En Wi-Fi direct, une TV injoignable n'empêche pas de piloter le décodeur.
            runCatching { prepare(ctx) }
        } else {
            prepare(ctx)
        }
        val gap = if (direct) KEY_GAP_WIFI_MS else KEY_GAP_CEC_MS
        for ((i, key) in keys.withIndex()) {
            val last = i == keys.size - 1
            if (key == ShortcutPrefs.WAIT) {
                Thread.sleep(WAIT_STEP_MS)
            } else if (key.startsWith(APP_PREFIX)) {
                if (!direct) error("« Ouvrir une appli » demande le décodeur associé en Wi-Fi (Réglages)")
                AtvRemote.launch(ctx, key.substring(APP_PREFIX.length)).getOrThrow()
                if (!last) Thread.sleep(APP_DELAY_MS)
            } else {
                sendKey(ctx, key).getOrThrow()
                if (!last) Thread.sleep(gap)
            }
        }
    }

    /** Zappe sur une chaîne du décodeur en composant son numéro. */
    fun zap(ctx: Context, number: Int): Result<Unit> =
        run(ctx, number.toString().map { "Num$it" })
}
