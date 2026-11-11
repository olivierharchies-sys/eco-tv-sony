package com.example.braviaeco

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Analyse de la TV et adaptation de l'image selon le contenu : 4K natif ou upscalé (HD / SD).
 *
 * Seuls les réglages exposés par l'API Bravia (service « video ») sont modifiables ici : mode d'image,
 * luminosité, contraste, couleur, netteté, température de couleur, gradation locale, XDR…
 * Reality Creation, réduction du bruit et Motionflow n'y figurent pas : ils se règlent sur la TV.
 * Tout est à appeler hors du thread principal.
 */
object Picture {

    enum class Kind(val label: String) {
        NATIVE_4K("4K natif"),
        UPSCALED("Upscalé (HD / SD)")
    }

    /** [kind] est null quand la TV ne permet pas de savoir : l'utilisateur choisit alors. */
    class Detection(val kind: Kind?, val reason: String)

    private class Cand(val value: String, val min: Double, val max: Double, val step: Double, val available: Boolean) {
        val numeric get() = !min.isNaN() && !max.isNaN()
    }

    private class Setting(val target: String, val current: String, val cands: List<Cand>)

    /** Réglage voulu : un nombre, ou une liste de mots cherchés (dans l'ordre) dans les valeurs proposées. */
    private class Want(val target: String, val number: Int? = null, val prefer: List<String> = emptyList())

    /**
     * Base : les deux colonnes de la calibration du Sony X900F (même dalle que le KD-65XF9005)
     * publiée par Sound & Vision. 4K natif (souvent HDR) : Cinéma Pro, contraste 90, XDR High.
     * Upscalé (HD / SD, SDR) : Cinéma Maison, contraste 85, XDR Off.
     * La luminosité n'est pas touchée : son échelle (0–50 sur ce modèle) diffère de celle de la source.
     */
    private fun wants(kind: Kind): List<Want> {
        val native4k = kind == Kind.NATIVE_4K
        return listOf(
            Want(
                "pictureMode",
                prefer = if (native4k) listOf("cinemapro", "cinemahome", "custom") else listOf("cinemahome", "cinemapro", "custom")
            ),
            Want("contrast", if (native4k) 90 else 85),
            Want("color", 50),
            Want("sharpness", 50),
            Want("colorTemperature", prefer = listOf("expert1", "warm1", "warm")),
            Want("autoLocalDimming", prefer = listOf("medium")),
            Want("xtendedDynamicRange", prefer = if (native4k) listOf("high") else listOf("off")),
            Want("lightSensor", prefer = listOf("off")),
            Want("autoPictureMode", prefer = listOf("off"))
        )
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- détection

    private val RE_4K = Regex("(?i)\\b(2160p?|3840|4k|uhd)\\b")
    private val RE_HD = Regex("(?i)\\b(1080[pi]?|720p?|576[pi]?|480[pi]?|full ?hd)\\b")

    /** Devine si l'image est en 4K natif ou upscalée, d'après ce que la TV dit du contenu en cours. */
    fun detect(ctx: Context): Detection {
        val read = BraviaClient.rawCall(ctx, "avContent", "getPlayingContentInfo", JSONArray())
        val failure = read.exceptionOrNull()
        if (failure != null) return Detection(null, failure.message ?: "TV injoignable")
        val info = read.getOrNull()?.optJSONObject(0)
        val text = info?.toString().orEmpty()
        val uri = info?.optString("uri").orEmpty()
        return when {
            uri.startsWith("tv:", ignoreCase = true) ->
                Detection(Kind.UPSCALED, "tuner de la TV (diffusion HD ou SD)")
            RE_4K.containsMatchIn(text) -> Detection(Kind.NATIVE_4K, "la TV annonce du 4K")
            RE_HD.containsMatchIn(text) -> Detection(Kind.UPSCALED, "la TV annonce une résolution inférieure au 4K")
            else -> Detection(null, "la TV ne donne pas la résolution du signal")
        }
    }

    // ---------------------------------------------------------------- lecture des réglages

    private fun readSettings(ctx: Context): Result<List<Setting>> = runCatching {
        val params = JSONArray().put(JSONObject().put("target", ""))
        val result = BraviaClient.rawCall(ctx, "video", "getPictureQualitySettings", params).getOrThrow()
        val arr = result.optJSONArray(0) ?: JSONArray()
        val list = ArrayList<Setting>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val target = o.optString("target")
            if (target.isEmpty()) continue
            val cands = ArrayList<Cand>()
            val ca = o.optJSONArray("candidate") ?: JSONArray()
            for (j in 0 until ca.length()) {
                val c = ca.optJSONObject(j) ?: continue
                cands.add(
                    Cand(
                        value = c.optString("value"),
                        min = c.optDouble("min", Double.NaN),
                        max = c.optDouble("max", Double.NaN),
                        step = c.optDouble("step", Double.NaN),
                        available = c.optBoolean("isAvailable", true)
                    )
                )
            }
            list.add(Setting(target, o.optString("currentValue"), cands))
        }
        if (list.isEmpty()) error("La TV ne renvoie aucun réglage d'image")
        list
    }

    // ---------------------------------------------------------------- adaptation

    /** Valeur à envoyer pour ce réglage voulu, ou null si la TV ne la propose pas. */
    private fun choose(want: Want, setting: Setting): String? {
        val usable = setting.cands.filter { it.available }
        if (want.number != null) {
            val range = usable.firstOrNull { it.numeric } ?: return null
            var v = want.number.toDouble().coerceIn(range.min, range.max)
            if (!range.step.isNaN() && range.step > 0) {
                v = range.min + ((v - range.min) / range.step).roundToInt() * range.step
            }
            return v.roundToInt().toString()
        }
        for (token in want.prefer) {
            val hit = usable.firstOrNull { it.value.isNotEmpty() && norm(it.value).contains(token) }
            if (hit != null) return hit.value
        }
        return null
    }

    private fun setting(target: String, value: String): JSONObject =
        JSONObject().put("target", target).put("value", value)

    private fun set(ctx: Context, items: List<JSONObject>): Result<JSONArray> {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return BraviaClient.rawCall(ctx, "video", "setPictureQualitySettings", JSONArray().put(JSONObject().put("settings", arr)))
    }

    /** Applique le profil du [kind] et renvoie un compte rendu lisible. Échoue si la TV ne gère pas ces réglages. */
    fun adapt(ctx: Context, kind: Kind): Result<String> = runCatching {
        val current = readSettings(ctx).getOrElse {
            error("Cette TV ne permet pas de lire les réglages d'image par l'API (${it.message}). Utilise « Analyser la TV » pour le détail.")
        }
        val byTarget = current.associateBy { it.target }

        val chosen = ArrayList<Pair<Setting, String>>()
        val skipped = ArrayList<String>()
        for (w in wants(kind)) {
            val s = byTarget[w.target]
            val v = if (s != null) choose(w, s) else null
            if (s == null || v == null) skipped.add(w.target) else chosen.add(s to v)
        }
        if (chosen.isEmpty()) error("Aucun des réglages d'image prévus n'est proposé par cette TV")

        val applied = ArrayList<String>()
        val refused = ArrayList<String>()
        val all = set(ctx, chosen.map { setting(it.first.target, it.second) })
        if (all.isSuccess) {
            chosen.forEach { applied.add("${it.first.target} : ${it.first.current} → ${it.second}") }
        } else {
            // Un réglage refusé fait échouer l'ensemble : on les envoie un par un pour savoir lesquels passent.
            for ((s, v) in chosen) {
                val one = set(ctx, listOf(setting(s.target, v)))
                if (one.isSuccess) applied.add("${s.target} : ${s.current} → $v")
                else refused.add("${s.target} (${one.exceptionOrNull()?.message})")
            }
            if (applied.isEmpty()) error("La TV a refusé les réglages : ${all.exceptionOrNull()?.message}")
        }

        buildString {
            appendLine("Appliqué (${applied.size}) :")
            applied.forEach { appendLine("• $it") }
            if (refused.isNotEmpty()) {
                appendLine()
                appendLine("Refusé par la TV :")
                refused.forEach { appendLine("• $it") }
            }
            if (skipped.isNotEmpty()) {
                appendLine()
                appendLine("Non proposé par cette TV : ${skipped.joinToString(", ")}")
            }
            appendLine()
            append(
                "À régler sur la TV (hors de l'API, valeurs de la même calibration) : Reality Creation sur Manuel, " +
                    "réduction du bruit aléatoire et numérique sur Off, Smooth gradation sur Low, Motionflow sur Off, " +
                    "Gamma −2 et Adv. contrast enhancer sur Low (en Cinéma Maison) ou Off (en Cinéma Pro). " +
                    "Valeurs de départ issues de la calibration du X900F ; ajuste selon ton exemplaire et ta pièce."
            )
        }
    }

    // ---------------------------------------------------------------- diagnostic

    private fun clip(s: String, max: Int = 600): String = if (s.length <= max) s else s.substring(0, max) + "…"

    /** Compte rendu en lecture seule : ce que la TV sait dire de son image et ce que l'API accepte. */
    fun analyze(ctx: Context): String = buildString {
        appendLine("Modèle :")
        BraviaClient.rawCall(ctx, "system", "getSystemInformation", JSONArray()).fold(
            onSuccess = { appendLine(clip(it.optJSONObject(0)?.toString().orEmpty())) },
            onFailure = { appendLine("Échec : ${it.message}") }
        )

        appendLine()
        appendLine("Contenu en cours :")
        BraviaClient.rawCall(ctx, "avContent", "getPlayingContentInfo", JSONArray()).fold(
            onSuccess = { appendLine(clip(it.optJSONObject(0)?.toString().orEmpty())) },
            onFailure = { appendLine("Échec : ${it.message}") }
        )
        val d = detect(ctx)
        appendLine("Détection : " + (d.kind?.label ?: "indéterminée") + " (${d.reason})")

        appendLine()
        appendLine("Réglages d'image lisibles :")
        readSettings(ctx).fold(
            onSuccess = { list ->
                list.forEach { s ->
                    val shown = s.cands.filter { it.available }.joinToString(", ") {
                        if (it.numeric) "${it.min.roundToInt()}–${it.max.roundToInt()}" else it.value
                    }
                    appendLine("• ${s.target} = ${s.current}  [${clip(shown, 120)}]")
                }
            },
            onFailure = { appendLine("Non pris en charge : ${it.message}") }
        )

        appendLine()
        appendLine("API prises en charge :")
        val params = JSONArray().put(JSONObject().put("services", JSONArray()))
        BraviaClient.rawCall(ctx, "guide", "getSupportedApiInfo", params).fold(
            onSuccess = { res ->
                val services = res.optJSONArray(0) ?: JSONArray()
                if (services.length() == 0) appendLine("Aucune information")
                for (i in 0 until services.length()) {
                    val sv = services.optJSONObject(i) ?: continue
                    val apis = sv.optJSONArray("apis") ?: JSONArray()
                    val names = (0 until apis.length()).mapNotNull { apis.optJSONObject(it)?.optString("name") }
                    val name = sv.optString("service")
                    if (name == "video") appendLine("• video : ${names.joinToString(", ")}")
                    else appendLine("• $name (${names.size})")
                }
            },
            onFailure = { appendLine("Échec : ${it.message}") }
        )
    }
}
