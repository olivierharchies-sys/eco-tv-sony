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
        UPSCALED("Upscalé (HD / SD)"),
        GAME("Jeu (faible latence)"),
        NIGHT("Cinéma nuit (pièce sombre)"),
        SPORT("Sport"),
        DAY("Jour (pièce lumineuse)")
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
        return when (kind) {
            Kind.NATIVE_4K, Kind.UPSCALED -> listOf(
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
            // Mode Jeu de la TV : traitements d'image coupés pour réduire le délai d'affichage.
            Kind.GAME -> listOf(
                Want("pictureMode", prefer = listOf("game", "custom")),
                Want("contrast", 90),
                Want("color", 50),
                Want("sharpness", 50),
                Want("colorTemperature", prefer = listOf("expert1", "warm1", "neutral")),
                Want("autoLocalDimming", prefer = listOf("medium")),
                Want("xtendedDynamicRange", prefer = listOf("medium", "high")),
                Want("lightSensor", prefer = listOf("off")),
                Want("autoPictureMode", prefer = listOf("off"))
            )
            // Film le soir : contraste un peu plus bas, gradation locale et XDR doux, image chaude.
            Kind.NIGHT -> listOf(
                Want("pictureMode", prefer = listOf("cinemapro", "cinemahome", "custom")),
                Want("contrast", 75),
                Want("color", 50),
                Want("sharpness", 45),
                Want("colorTemperature", prefer = listOf("expert1", "warm1", "warm")),
                Want("autoLocalDimming", prefer = listOf("medium", "low")),
                Want("xtendedDynamicRange", prefer = listOf("low", "medium")),
                Want("lightSensor", prefer = listOf("off")),
                Want("autoPictureMode", prefer = listOf("off"))
            )
            // Sport : image nette et vive, mouvement fluide, température plus neutre.
            Kind.SPORT -> listOf(
                Want("pictureMode", prefer = listOf("sports", "standard", "custom")),
                Want("contrast", 90),
                Want("color", 55),
                Want("sharpness", 55),
                Want("colorTemperature", prefer = listOf("neutral", "expert1", "warm1")),
                Want("autoLocalDimming", prefer = listOf("high", "medium")),
                Want("xtendedDynamicRange", prefer = listOf("high")),
                Want("lightSensor", prefer = listOf("off")),
                Want("autoPictureMode", prefer = listOf("off"))
            )
            // Journée, volets ouverts : image plus lumineuse. Capteur de lumière activé pour suivre la pièce.
            Kind.DAY -> listOf(
                Want("pictureMode", prefer = listOf("standard", "vivid", "custom")),
                Want("contrast", 95),
                Want("color", 50),
                Want("sharpness", 50),
                Want("colorTemperature", prefer = listOf("neutral", "expert1", "warm1")),
                Want("autoLocalDimming", prefer = listOf("high", "medium")),
                Want("xtendedDynamicRange", prefer = listOf("high")),
                Want("lightSensor", prefer = listOf("on")),
                Want("autoPictureMode", prefer = listOf("off"))
            )
        }
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
            if (kind == Kind.GAME) {
                append("Pense à activer le mode jeu / « Game mode » de l'entrée HDMI de la console si la TV le propose. Valeurs de départ : ajuste selon ton goût.")
            } else if (kind == Kind.NIGHT || kind == Kind.SPORT || kind == Kind.DAY) {
                append(
                    "Valeurs de départ raisonnables, pas une calibration mesurée : ajuste dans « Tous les réglages ». " +
                        "Motionflow et Reality Creation se règlent sur la TV si l'API ne les expose pas."
                )
            } else append(
                "À régler sur la TV (hors de l'API, valeurs de la même calibration) : Reality Creation sur Manuel, " +
                    "réduction du bruit aléatoire et numérique sur Off, Smooth gradation sur Low, Motionflow sur Off, " +
                    "Gamma −2 et Adv. contrast enhancer sur Low (en Cinéma Maison) ou Off (en Cinéma Pro). " +
                    "Valeurs de départ issues de la calibration du X900F ; ajuste selon ton exemplaire et ta pièce."
            )
        }
    }

    // ---------------------------------------------------------------- édition réglage par réglage

    /** Un réglage d'image tel que la TV l'expose : liste de valeurs ([choices]) ou plage numérique. */
    class Item(
        val target: String,
        val current: String,
        val choices: List<String>,
        val min: Int?,
        val max: Int?,
        val step: Int
    ) {
        val numeric get() = min != null && max != null
    }

    /** Tous les réglages d'image que la TV accepte de lire (donc de modifier). */
    fun items(ctx: Context): Result<List<Item>> = runCatching {
        readSettings(ctx).getOrThrow().map { s ->
            val usable = s.cands.filter { it.available }
            val range = usable.firstOrNull { it.numeric }
            Item(
                target = s.target,
                current = s.current,
                choices = usable.filter { !it.numeric && it.value.isNotEmpty() }.map { it.value },
                min = range?.min?.roundToInt(),
                max = range?.max?.roundToInt(),
                step = range?.step?.takeIf { !it.isNaN() && it >= 1 }?.roundToInt() ?: 1
            )
        }
    }

    /** Modifie un seul réglage. */
    fun setOne(ctx: Context, target: String, value: String): Result<Unit> =
        set(ctx, listOf(setting(target, value))).map { }

    /** Compte rendu d'un refus : ce que la TV annonce pour ce réglage, son état, et si elle accepte la valeur actuelle. */
    fun diagnose(ctx: Context, target: String, tried: String, error: String): String = buildString {
        appendLine("Réglage : ${labelOf(target)} ($target)")
        appendLine("Valeur envoyée : $tried")
        appendLine("Réponse de la TV : $error")
        appendLine()
        val setting = readSettings(ctx).getOrNull()?.firstOrNull { it.target == target }
        if (setting == null) {
            appendLine("La TV ne liste pas ce réglage.")
        } else {
            appendLine("Valeur actuelle : ${setting.current}")
            appendLine(
                "Proposé par la TV : " + setting.cands.joinToString(", ") {
                    (if (it.numeric) "${it.min.roundToInt()}–${it.max.roundToInt()}" else it.value) +
                        if (it.available) "" else " (indisponible)"
                }
            )
            val same = set(ctx, listOf(setting(target, setting.current)))
            appendLine(
                "Renvoyer la valeur actuelle : " +
                    if (same.isSuccess) "accepté" else "refusé aussi (${same.exceptionOrNull()?.message})"
            )
        }
        appendLine()
        BraviaClient.rawCall(ctx, "system", "getPowerStatus", JSONArray()).fold(
            onSuccess = { appendLine("État : ${it.optJSONObject(0)?.optString("status")}") },
            onFailure = { appendLine("État : ${it.message}") }
        )
        BraviaClient.rawCall(ctx, "avContent", "getPlayingContentInfo", JSONArray()).fold(
            onSuccess = { appendLine("Contenu : ${clip(it.optJSONObject(0)?.toString().orEmpty(), 300)}") },
            onFailure = { appendLine("Contenu : ${it.message}") }
        )
    }

    private val LABELS = mapOf(
        "pictureMode" to "Mode d'image",
        "autoPictureMode" to "Mode d'image automatique",
        "brightness" to "Luminosité",
        "contrast" to "Contraste",
        "color" to "Couleur",
        "hue" to "Teinte",
        "sharpness" to "Netteté",
        "colorTemperature" to "Température de couleur",
        "autoLocalDimming" to "Gradation locale",
        "xtendedDynamicRange" to "XDR (plage dynamique étendue)",
        "lightSensor" to "Capteur de lumière",
        "hdrMode" to "Mode HDR",
        "colorSpace" to "Espace colorimétrique",
        "gamma" to "Gamma",
        "noiseReduction" to "Réduction du bruit",
        "mpegNoiseReduction" to "Réduction du bruit MPEG",
        "smoothGradation" to "Gradation lissée",
        "motionflow" to "Motionflow",
        "motionFlow" to "Motionflow",
        "clearness" to "Clarté",
        "realityCreation" to "Reality Creation"
    )

    /** Nom lisible d'un réglage ; sinon le nom de l'API découpé. */
    fun labelOf(target: String): String =
        LABELS[target] ?: target.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    private val VALUE_LABELS = mapOf(
        "on" to "Activé", "off" to "Désactivé", "low" to "Bas", "medium" to "Moyen", "high" to "Haut",
        "auto" to "Auto", "standard" to "Standard", "vivid" to "Vif", "custom" to "Personnalisé",
        "cinemaPro" to "Cinéma Pro", "cinemaHome" to "Cinéma Maison", "game" to "Jeu", "sports" to "Sport",
        "photo" to "Photo", "graphics" to "Graphisme", "animation" to "Animation", "neutral" to "Neutre"
    )

    fun valueLabel(value: String): String = VALUE_LABELS[value] ?: value

    // ---------------------------------------------------------------- réglages mémorisés

    /** Réglages d'image actuels de la TV (nom → valeur), pour les mémoriser. */
    fun capture(ctx: Context): Result<Map<String, String>> = runCatching {
        val map = LinkedHashMap<String, String>()
        readSettings(ctx).getOrThrow().forEach { s ->
            if (s.current.isNotEmpty()) map[s.target] = s.current
        }
        if (map.isEmpty()) error("La TV ne renvoie aucun réglage d'image")
        map
    }

    /** Entrée affichée par la TV (par exemple « extInput:hdmi?port=2 »), ou null si inconnue. */
    fun currentInput(ctx: Context): String? =
        BraviaClient.rawCall(ctx, "avContent", "getPlayingContentInfo", JSONArray())
            .getOrNull()?.optJSONObject(0)?.optString("uri")?.takeIf { it.isNotEmpty() }

    /** Rétablit des réglages mémorisés. Le mode d'image d'abord : en changer modifie les autres valeurs. */
    fun applyValues(ctx: Context, values: Map<String, String>): Result<String> = runCatching {
        val mode = values["pictureMode"]
        if (mode != null) {
            set(ctx, listOf(setting("pictureMode", mode))).getOrThrow()
            Thread.sleep(400)
        }
        val rest = values.filterKeys { it != "pictureMode" }
        val refused = ArrayList<String>()
        var applied = if (mode != null) 1 else 0
        if (rest.isNotEmpty()) {
            val all = set(ctx, rest.map { setting(it.key, it.value) })
            if (all.isSuccess) {
                applied += rest.size
            } else {
                // Un réglage refusé fait échouer l'ensemble : un par un, pour savoir lesquels passent.
                for ((target, value) in rest) {
                    if (set(ctx, listOf(setting(target, value))).isSuccess) applied++ else refused.add(target)
                }
            }
        }
        if (applied == 0) error("La TV a refusé les réglages")
        buildString {
            append("Appliqué : $applied réglage(s)")
            if (refused.isNotEmpty()) append("\nRefusé par la TV : ${refused.joinToString(", ")}")
        }
    }

    /**
     * À l'ouverture de la télécommande : si l'entrée de la TV a changé depuis la dernière fois et qu'un
     * emplacement lui est associé, l'applique. Renvoie le nom de l'emplacement appliqué, sinon null.
     */
    fun autoApply(ctx: Context): String? {
        val uri = currentInput(ctx) ?: return null
        if (PictureSlots.lastSeenInput(ctx) == PictureSlots.inputKey(uri)) return null
        PictureSlots.saveSeenInput(ctx, uri)
        val name = PictureSlots.autoFor(ctx, uri) ?: return null
        val values = PictureSlots.get(ctx, name) ?: return null
        return if (applyValues(ctx, values).isSuccess) name else null
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
