package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Raccourci vers une application du décodeur : un nom et la suite de touches qui l'ouvre. */
data class DecoderApp(val name: String, val keys: List<String>)

/** Listes choisies par l'utilisateur, enregistrées sur le téléphone. */
object ShortcutPrefs {
    private const val FILE = "bravia_eco"
    const val WAIT = "WAIT"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Applications de la TV (choisies dans la liste lue sur la TV)
    fun tvApps(ctx: Context): List<TvApp> {
        val arr = runCatching { JSONArray(sp(ctx).getString("tv_apps", "[]")) }.getOrElse { JSONArray() }
        val list = ArrayList<TvApp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uri = o.optString("uri")
            if (uri.isNotEmpty()) list.add(TvApp(o.optString("title", uri), uri))
        }
        return list
    }

    fun saveTvApps(ctx: Context, apps: List<TvApp>) {
        val arr = JSONArray()
        for (a in apps) arr.put(JSONObject().put("title", a.title).put("uri", a.uri))
        sp(ctx).edit().putString("tv_apps", arr.toString()).apply()
    }

    // --- Applications du décodeur (nom + suite de touches envoyées via HDMI-CEC)
    fun decoderApps(ctx: Context): List<DecoderApp> {
        val arr = runCatching { JSONArray(sp(ctx).getString("decoder_apps", "[]")) }.getOrElse { JSONArray() }
        val list = ArrayList<DecoderApp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val keysArr = o.optJSONArray("keys") ?: JSONArray()
            val keys = (0 until keysArr.length()).map { keysArr.optString(it) }.filter { it.isNotEmpty() }
            list.add(DecoderApp(o.optString("name", "?"), keys))
        }
        return list
    }

    fun saveDecoderApps(ctx: Context, apps: List<DecoderApp>) {
        val arr = JSONArray()
        for (a in apps) {
            val keys = JSONArray()
            a.keys.forEach { keys.put(it) }
            arr.put(JSONObject().put("name", a.name).put("keys", keys))
        }
        sp(ctx).edit().putString("decoder_apps", arr.toString()).apply()
    }

    /** Entrée HDMI du décodeur (choisie une fois) : on y bascule avant d'envoyer les touches. */
    fun decoderInputUri(ctx: Context): String = sp(ctx).getString("decoder_input_uri", "") ?: ""
    fun decoderInputName(ctx: Context): String = sp(ctx).getString("decoder_input_name", "") ?: ""

    fun saveDecoderInput(ctx: Context, uri: String, name: String) {
        sp(ctx).edit().putString("decoder_input_uri", uri).putString("decoder_input_name", name).apply()
    }
}

/**
 * Les deux menus d'applications de la télécommande :
 * - [showTvApps] : applications de la TV, choisies dans la liste lue sur la TV, lancées via l'API ;
 * - [showDecoderApps] : raccourcis du décodeur, chacun défini par une suite de touches
 *   (envoyées en Wi-Fi direct si le décodeur est associé, sinon relayées par la TV en HDMI-CEC).
 */
class AppShortcuts(private val act: Activity, private val actions: TvActions) {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    fun close() {
        io.shutdown()
    }

    private fun bg(work: () -> Unit) {
        runCatching { io.execute(Runnable { work() }) }
    }

    /** Liste lue une fois par ouverture de la télécommande. */
    private var streamingCache: List<TvApp>? = null

    private fun dialog() = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)

    // ================================================================== TV

    fun showTvApps() {
        val apps = ShortcutPrefs.tvApps(act)
        if (apps.isEmpty()) {
            pickTvApps()
            return
        }
        val labels = (apps.map { it.title } + "✎  Choisir les applications…").toTypedArray()
        dialog()
            .setTitle("Applications de la TV")
            .setItems(labels) { _, which ->
                if (which == apps.size) {
                    pickTvApps()
                } else {
                    val app = apps[which]
                    actions.runIo { BraviaClient.launchApp(act, app.uri) }
                }
            }
            .show()
    }

    /** Applications de streaming installées sur la TV (reconnues par leur nom), lancées via l'API. */
    fun showStreamingApps() {
        val cached = streamingCache
        if (cached != null) {
            showStreamingList(cached)
            return
        }
        Ui.toast(act, "Lecture des applications de la TV…")
        bg {
            val result = BraviaClient.getApps(act)
            ui.post {
                if (act.isFinishing) return@post
                val all = result.getOrElse {
                    Ui.toast(act, it.message ?: "Applications indisponibles (TV allumée ?)")
                    return@post
                }
                val found = all.filter { app ->
                    val title = app.title.lowercase()
                    STREAMING.any { title.contains(it) }
                }.distinctBy { it.uri }.sortedBy { it.title.lowercase() }
                if (found.isEmpty()) {
                    Ui.toast(act, "Aucune application de streaming reconnue : appui long pour ta liste")
                    return@post
                }
                streamingCache = found
                showStreamingList(found)
            }
        }
    }

    private fun showStreamingList(apps: List<TvApp>) {
        dialog()
            .setTitle("Streaming")
            .setItems(apps.map { it.title }.toTypedArray()) { _, which ->
                val app = apps[which]
                actions.runIo { BraviaClient.launchApp(act, app.uri) }
            }
            .show()
    }

    /** Lit la liste des applications installées sur la TV et laisse cocher celles à garder. */
    fun pickTvApps() {
        Ui.toast(act, "Lecture des applications de la TV…")
        bg {
            val result = BraviaClient.getApps(act)
            ui.post {
                if (act.isFinishing) return@post
                val all = result.getOrElse {
                    Ui.toast(act, it.message ?: "Applications indisponibles (TV allumée ?)")
                    return@post
                }
                if (all.isEmpty()) {
                    Ui.toast(act, "Aucune application trouvée sur la TV")
                    return@post
                }
                val saved = ShortcutPrefs.tvApps(act)
                val savedUris = saved.map { it.uri }.toSet()
                val checked = BooleanArray(all.size) { all[it].uri in savedUris }

                dialog()
                    .setTitle("Applications à afficher")
                    .setMultiChoiceItems(all.map { it.title }.toTypedArray(), checked) { _, i, isChecked ->
                        checked[i] = isChecked
                    }
                    .setPositiveButton("Enregistrer") { _, _ ->
                        // Garde l'ordre déjà choisi, ajoute les nouvelles à la fin.
                        val keep = all.filterIndexed { i, _ -> checked[i] }
                        val keepUris = keep.map { it.uri }.toSet()
                        val ordered = saved.filter { it.uri in keepUris } +
                            keep.filter { it.uri !in savedUris }
                        ShortcutPrefs.saveTvApps(act, ordered)
                        if (ordered.isNotEmpty()) showTvApps()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
    }

    // ================================================================== décodeur

    fun showDecoderApps() {
        val apps = ShortcutPrefs.decoderApps(act)
        if (apps.isEmpty()) {
            manageDecoderApps()
            return
        }
        val labels = (apps.map { it.name } + "✎  Gérer les applications…").toTypedArray()
        dialog()
            .setTitle("Applications du décodeur")
            .setItems(labels) { _, which ->
                if (which == apps.size) manageDecoderApps() else runDecoderApp(apps[which])
            }
            .show()
    }

    /** Prépare la TV (allumée, bonne entrée HDMI) puis envoie les touches au décodeur. */
    private fun runDecoderApp(app: DecoderApp) {
        actions.runIo {
            val r = Decoder.run(act, app.keys)
            actions.notifyStateChanged()
            r
        }
    }

    /** Liste des raccourcis : en ajouter, en modifier, en supprimer, régler l'entrée HDMI. */
    private fun manageDecoderApps() {
        val apps = ShortcutPrefs.decoderApps(act)
        val inputName = ShortcutPrefs.decoderInputName(act).ifEmpty { "non réglée" }
        val labels = ArrayList<String>()
        labels.add("＋  Ajouter une application")
        apps.forEach { labels.add("✎  ${it.name}") }
        labels.add("Entrée HDMI du décodeur : $inputName")

        dialog()
            .setTitle("Gérer le décodeur")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> editDecoderApp(null)
                    which <= apps.size -> editDecoderApp(which - 1)
                    else -> pickDecoderInput()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun pickDecoderInput() {
        bg {
            val result = BraviaClient.getInputs(act)
            ui.post {
                if (act.isFinishing) return@post
                val inputs = result.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: (1..4).map { TvInput("extInput:hdmi?port=$it", "HDMI $it", "", true) }
                val labels = (listOf("Aucune (ne pas changer de source)") + inputs.map { it.display }).toTypedArray()
                dialog()
                    .setTitle("Entrée HDMI du décodeur")
                    .setItems(labels) { _, which ->
                        if (which == 0) {
                            ShortcutPrefs.saveDecoderInput(act, "", "")
                        } else {
                            val input = inputs[which - 1]
                            ShortcutPrefs.saveDecoderInput(act, input.uri, input.display)
                        }
                        manageDecoderApps()
                    }
                    .show()
            }
        }
    }

    /** Éditeur d'un raccourci : nom + suite de touches, avec bouton « Tester ». */
    private fun editDecoderApp(index: Int?) {
        val all = ShortcutPrefs.decoderApps(act).toMutableList()
        val original = index?.let { all[it] }
        val keys = ArrayList(original?.keys ?: emptyList())

        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, 20)
        box.setPadding(pad, Ui.dp(act, 8), pad, 0)

        val name = EditText(act)
        name.hint = "Nom (ex. Netflix, Replay, Guide…)"
        name.setSingleLine(true)
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.setTextColor(Ui.TEXT)
        name.setHintTextColor(Ui.DIM)
        name.setText(original?.name ?: "")
        box.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val caption = TextView(act)
        caption.text = "Touches envoyées au décodeur, dans l'ordre :"
        caption.setTextColor(Ui.DIM)
        caption.textSize = 13f
        caption.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 6))
        box.addView(caption)

        val sequence = TextView(act)
        sequence.setTextColor(Ui.TEXT)
        sequence.textSize = 15f
        sequence.background = Ui.rounded(act, Ui.SURFACE_HI, 12)
        sequence.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10))
        sequence.minHeight = Ui.dp(act, 48)
        box.addView(sequence, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun render() {
            sequence.text = if (keys.isEmpty()) "(aucune touche)" else keys.joinToString("  →  ") { keyLabel(it) }
        }
        render()

        val buttons = LinearLayout(act)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.setPadding(0, Ui.dp(act, 10), 0, 0)
        val gap = Ui.dp(act, 4)
        fun addButton(text: String, onClick: () -> Unit) {
            val b = Ui.pill(act, text, 13f)
            b.background = Ui.rounded(act, Ui.SURFACE_HI, 16)
            b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, Ui.dp(act, 42), 1f)
            lp.setMargins(gap, 0, gap, 0)
            buttons.addView(b, lp)
        }
        addButton("＋ Touche") { pickKey { keys.add(it); render() } }
        addButton("⌫ Effacer") { if (keys.isNotEmpty()) { keys.removeAt(keys.size - 1); render() } }
        addButton("▶ Tester") {
            if (keys.isEmpty()) Ui.toast(act, "Ajoute d'abord des touches")
            else runDecoderApp(DecoderApp(name.text.toString(), ArrayList(keys)))
        }
        box.addView(buttons)

        val hint = TextView(act)
        hint.text = "Astuce : commence par « Home appareil » pour partir toujours du même écran, " +
            "et ajoute une « Pause 1 s » si le décodeur est lent."
        hint.setTextColor(Ui.DIM)
        hint.textSize = 12f
        hint.setPadding(0, Ui.dp(act, 10), 0, 0)
        box.addView(hint)

        val builder = dialog()
            .setTitle(if (original == null) "Nouvelle application" else "Modifier")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty() || keys.isEmpty()) {
                    Ui.toast(act, "Il faut un nom et au moins une touche")
                    return@setPositiveButton
                }
                val app = DecoderApp(n, ArrayList(keys))
                if (index == null) all.add(app) else all[index] = app
                ShortcutPrefs.saveDecoderApps(act, all)
                showDecoderApps()
            }
            .setNegativeButton("Annuler", null)
        if (index != null) {
            builder.setNeutralButton("Supprimer") { _, _ ->
                all.removeAt(index)
                ShortcutPrefs.saveDecoderApps(act, all)
                manageDecoderApps()
            }
        }
        builder.show()
    }

    private fun pickKey(onPick: (String) -> Unit) {
        val deviceHome = Prefs.deviceHomeKey(act)
        val choices = KEY_CHOICES.map { (label, key) -> label to (if (key == DEVICE_HOME) deviceHome else key) }
        dialog()
            .setTitle("Ajouter une touche")
            .setItems(choices.map { it.first }.toTypedArray()) { _, which ->
                val key = choices[which].second
                if (key == APP_PICK) pickDecoderLink(onPick) else onPick(key)
            }
            .show()
    }

    /** Étape « ouvrir une application du décodeur » (Wi-Fi direct uniquement). */
    private fun pickDecoderLink(onPick: (String) -> Unit) {
        if (!Decoder.wifi(act)) {
            Ui.toast(act, "Associe d'abord le décodeur en Wi-Fi (Réglages de l'appli)")
            return
        }
        val known = Decoder.KNOWN_APPS
        val labels = (known.map { it.first } + "Autre (nom de paquet ou lien)…").toTypedArray()
        dialog()
            .setTitle("Application du décodeur")
            .setItems(labels) { _, which ->
                if (which < known.size) {
                    onPick(Decoder.APP_PREFIX + known[which].second)
                } else {
                    val edit = EditText(act)
                    edit.hint = "ex. com.netflix.ninja ou https://…"
                    edit.setSingleLine(true)
                    edit.setTextColor(Ui.TEXT)
                    edit.setHintTextColor(Ui.DIM)
                    val box = LinearLayout(act)
                    val pad = Ui.dp(act, 20)
                    box.setPadding(pad, Ui.dp(act, 8), pad, 0)
                    box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    dialog()
                        .setTitle("Paquet ou lien")
                        .setView(box)
                        .setPositiveButton("OK") { _, _ ->
                            val text = edit.text.toString().trim()
                            if (text.isNotEmpty()) onPick(Decoder.APP_PREFIX + text)
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            }
            .show()
    }

    private fun keyLabel(key: String): String {
        if (key.startsWith(Decoder.APP_PREFIX)) return "▶ " + Decoder.appLabel(key.substring(Decoder.APP_PREFIX.length))
        if (key == Prefs.deviceHomeKey(act)) return "Home"
        return KEY_CHOICES.firstOrNull { it.second == key }?.first ?: key
    }

    private companion object {
        const val DEVICE_HOME = "__device_home__"
        const val APP_PICK = "__app__"

        /** Touches relayées au décodeur par la TV (HDMI-CEC). */
        val KEY_CHOICES = listOf(
            "▶ Ouvrir une appli (Wi-Fi)…" to APP_PICK,
            "Home appareil" to DEVICE_HOME,
            "Haut" to "Up",
            "Bas" to "Down",
            "Gauche" to "Left",
            "Droite" to "Right",
            "OK" to "Confirm",
            "Retour" to "Return",
            "Options" to "Options",
            "Pause 1 s" to ShortcutPrefs.WAIT,
            "Rouge" to "Red",
            "Vert" to "Green",
            "Jaune" to "Yellow",
            "Bleu" to "Blue",
            "Lecture" to "Play",
            "Pause (lecture)" to "Pause",
            "Chaîne +" to "ChannelUp",
            "Chaîne −" to "ChannelDown",
            "0" to "Num0", "1" to "Num1", "2" to "Num2", "3" to "Num3", "4" to "Num4",
            "5" to "Num5", "6" to "Num6", "7" to "Num7", "8" to "Num8", "9" to "Num9"
        )
    }
}

/** Petit bouton rond (icône) placé dans les coins de la croix directionnelle. */
object RoundButton {
    fun create(act: Activity, iconRes: Int, description: String, onClick: () -> Unit, onLongClick: () -> Unit): android.widget.ImageView {
        val v = android.widget.ImageView(act)
        v.setImageResource(iconRes)
        v.setColorFilter(Ui.TEXT)
        v.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val p = Ui.dp(act, 13)
        v.setPadding(p, p, p, p)
        v.background = Ui.rounded(act, Ui.SURFACE, 100)
        v.contentDescription = description
        v.isClickable = true
        v.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        v.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongClick()
            true
        }
        return v
    }
}

/** Mots reconnus dans le nom d'une application de streaming de la TV. */
private val STREAMING = listOf(
    "netflix", "youtube", "prime video", "disney", "molotov", "apple tv", "canal", "auvio",
    "rtl play", "rtlplay", "vrt", "streamz", "arte", "twitch", "hbo", "paramount", "pluto",
    "rakuten", "dazn", "plex", "kodi", "vlc", "spotify", "deezer", "orange", "pickx", "voo",
    "tf1", "france.tv", "francetv", "6play", "m6+", "crunchyroll", "mubi", "tubi"
)
