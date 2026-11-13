package com.example.braviaeco

import android.app.Activity
import android.content.Intent
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var ipField: EditText
    private lateinit var pskField: EditText
    private lateinit var status: TextView
    private lateinit var webField: EditText
    private lateinit var homeKeyField: EditText
    private lateinit var browserLabel: TextView
    private lateinit var decoderIpField: EditText
    private lateinit var decoderState: TextView
    private lateinit var tvKeysState: TextView
    private lateinit var edenwoodIpField: EditText
    private lateinit var edenwoodState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Icône de l'appli : droit sur la télécommande dès que la TV est configurée
        // (les réglages restent accessibles par la roue dentée).
        if (savedInstanceState == null &&
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true &&
            Prefs.ip(this).isNotEmpty()
        ) {
            startActivity(Intent(this, RemoteActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        ipField = findViewById(R.id.field_ip)
        pskField = findViewById(R.id.field_psk)
        status = findViewById(R.id.status)

        ipField.setText(Prefs.ip(this))
        pskField.setText(Prefs.psk(this))

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            save()
            show("Réglages enregistrés")
        }

        findViewById<Button>(R.id.btn_detect_tv).setOnClickListener { detectTv() }

        findViewById<Button>(R.id.btn_open_remote).setOnClickListener {
            save()
            startActivity(Intent(this, RemoteActivity::class.java))
        }

        findViewById<Button>(R.id.btn_read).setOnClickListener {
            save()
            run("Lecture…") {
                BraviaClient.getMode(this).fold(
                    onSuccess = { "Mode actuel de la TV : ${it.label}" },
                    onFailure = { "Échec : ${it.message}" }
                )
            }
        }

        addExtraSettings()
        addDeviceSettings()
        addEdenwoodCard()
        addPictureCard()
        addBackupCard()

        mapOf(
            R.id.test_off to EcoMode.OFF,
            R.id.test_low to EcoMode.LOW,
            R.id.test_high to EcoMode.HIGH,
            R.id.test_picture_off to EcoMode.PICTURE_OFF
        ).forEach { (id, mode) ->
            findViewById<Button>(id).setOnClickListener {
                save()
                run("Envoi : ${mode.label}…") {
                    BraviaClient.setMode(this, mode).fold(
                        onSuccess = { "OK : éco ${mode.label}" },
                        onFailure = { "Échec : ${it.message}" }
                    )
                }
            }
        }
    }

    private fun save() {
        Prefs.saveConnection(this, ipField.text.toString(), pskField.text.toString())
        if (::webField.isInitialized) {
            Prefs.saveWeb(this, webField.text.toString())
            Prefs.saveDeviceHomeKey(this, homeKeyField.text.toString())
        }
        if (::decoderIpField.isInitialized) {
            AtvPrefs.saveIp(this, decoderIpField.text.toString())
        }
        if (::edenwoodIpField.isInitialized) {
            EdenwoodPrefs.saveIp(this, edenwoodIpField.text.toString())
        }
    }

    private fun showBrowser() {
        val title = Prefs.webAppTitle(this)
        browserLabel.text = "Navigateur de la TV : " + (if (title.isEmpty()) "navigateur intégré (par défaut)" else title)
    }

    /** Carte « Bouton Web » et « Home appareil », ajoutée avant le texte d'aide. */
    private fun addExtraSettings() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        val pad = Ui.dp(this, 16)
        card.setPadding(pad, pad, pad, pad)

        fun title(text: String): TextView {
            val t = TextView(this)
            t.text = text
            t.setTextColor(Color.WHITE)
            t.textSize = 17f
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            return t
        }

        fun note(text: String): TextView {
            val t = TextView(this)
            t.text = text
            t.setTextColor(Ui.DIM)
            t.textSize = 12f
            t.setPadding(0, Ui.dp(this, 4), 0, 0)
            return t
        }

        fun field(hint: String, value: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.setSingleLine(true)
            e.setTextColor(Color.WHITE)
            e.setHintTextColor(Ui.DIM)
            return e
        }

        card.addView(title("Bouton Web"))
        card.addView(note("Page ouverte sur la TV quand tu touches « Web »."))
        webField = field("Adresse de la page (ex. https://www.google.com)", Prefs.webUrl(this))
        webField.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        card.addView(webField, fieldLp())

        browserLabel = TextView(this)
        browserLabel.setTextColor(Color.WHITE)
        browserLabel.textSize = 14f
        browserLabel.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4))
        card.addView(browserLabel)
        showBrowser()

        val pick = android.widget.Button(this)
        pick.text = "Choisir le navigateur de la TV"
        pick.isAllCaps = false
        pick.setTextColor(Color.WHITE)
        pick.setBackgroundResource(R.drawable.btn_surface)
        pick.setOnClickListener { save(); pickBrowser() }
        card.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))

        val test = android.widget.Button(this)
        test.text = "Tester le bouton Web"
        test.isAllCaps = false
        test.setTextColor(Color.WHITE)
        test.setBackgroundResource(R.drawable.btn_surface)
        test.setOnClickListener {
            save()
            run("Ouverture…", loud = true) {
                BraviaClient.openWeb(this).fold(
                    onSuccess = { "Page envoyée à la TV" },
                    onFailure = { "Échec : ${it.message}" }
                )
            }
        }
        val testLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48))
        testLp.topMargin = Ui.dp(this, 8)
        card.addView(test, testLp)

        val sep = TextView(this)
        sep.setPadding(0, Ui.dp(this, 14), 0, 0)
        card.addView(sep)

        card.addView(title("Home appareil"))
        card.addView(note(
            "Touche envoyée à l'appareil HDMI sélectionné (box, lecteur…) via HDMI-CEC. " +
                "Défaut : SyncMenu. Si la source est la TV, c'est le Home de la TV. " +
                "Les noms valides sont dans Plus > Toutes les touches."
        ))
        homeKeyField = field("Nom de la touche", Prefs.deviceHomeKey(this))
        card.addView(homeKeyField, fieldLp())

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 16)
        column.addView(card, column.childCount - 1, lp)
    }

    // ------------------------------------------------------------------ TV et décodeur sur le réseau

    /** Même espacement au-dessus de tous les champs de saisie des cartes. */
    private fun fieldLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 8)
        return lp
    }

    private fun cardText(text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(color)
        t.textSize = sizeSp
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        return t
    }

    private fun cardButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setBackgroundResource(R.drawable.btn_surface)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48))
        lp.topMargin = Ui.dp(this, 8)
        parent.addView(b, lp)
    }

    /** Message dans la zone d'état et en bulle, visible où que l'on soit dans la page. */
    private fun tell(text: String) {
        show(text)
        Ui.toast(this, text)
    }

    private fun showDecoderState() {
        decoderState.text = if (Decoder.wifi(this)) {
            "État : décodeur associé ✓ (touches envoyées en Wi-Fi direct)"
        } else {
            "État : décodeur non associé (touches relayées par la TV en HDMI-CEC)"
        }
        if (::tvKeysState.isInitialized) {
            tvKeysState.text = if (AtvDevice.TV.paired(this)) "État : TV associée ✓" else "État : TV non associée"
        }
        if (::edenwoodState.isInitialized) {
            edenwoodState.text =
                if (AtvDevice.EDENWOOD.paired(this)) "État : Edenwood associée ✓" else "État : Edenwood non associée"
        }
    }

    /** Carte « TV Edenwood » : adresse IP, association (code affiché sur la TV) et accès à sa télécommande bleue. */
    private fun addEdenwoodCard() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        val pad = Ui.dp(this, 16)
        card.setPadding(pad, pad, pad, pad)

        card.addView(cardText("TV Edenwood", 17f, Color.WHITE, true))
        card.addView(cardText(
            "Pilote la TV Edenwood (Android TV) en Wi-Fi, avec une télécommande bleue. Le bouton " +
                "« télécommande » en haut de la télécommande Sony y mène, et inversement. TV allumée et sur " +
                "le même Wi-Fi : saisis son adresse IP (ou touche Détecter), puis Associer et tape le code " +
                "affiché sur la TV. Association une seule fois.",
            12f, Ui.DIM, false
        ))
        edenwoodIpField = EditText(this)
        edenwoodIpField.hint = "Adresse IP de la Edenwood (ex. 192.168.1.70)"
        edenwoodIpField.setText(EdenwoodPrefs.ip(this))
        edenwoodIpField.setSingleLine(true)
        edenwoodIpField.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        edenwoodIpField.setTextColor(Color.WHITE)
        edenwoodIpField.setHintTextColor(Ui.DIM)
        card.addView(edenwoodIpField, fieldLp())

        edenwoodState = cardText("", 14f, Color.WHITE, false)
        card.addView(edenwoodState)
        showDecoderState()

        cardButton(card, "Détecter la TV Edenwood") { detectEdenwood() }
        cardButton(card, "Associer la TV Edenwood") { pair(AtvDevice.EDENWOOD) }
        cardButton(card, "Dissocier la TV Edenwood") {
            AtvDevice.EDENWOOD.savePaired(this, false)
            thread { AtvRemote.disconnect(AtvDevice.EDENWOOD) }
            showDecoderState()
        }
        cardButton(card, "Ouvrir la télécommande Edenwood") {
            save()
            Prefs.saveRemoteDevice(this, Prefs.DEVICE_EDENWOOD)
            startActivity(Intent(this, EdenwoodRemoteActivity::class.java))
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 16)
        column.addView(card, column.childCount - 1, lp)
    }

    private fun detectEdenwood() {
        save()
        tell("Recherche de la TV Edenwood…")
        // On écarte la Sony et le décodeur : seule la Edenwood doit rester.
        val others = setOf(AtvPrefs.ip(this)).filter { it.isNotEmpty() }.toSet()
        Discovery.findDecoder(this, Prefs.ip(this), others) { ip ->
            if (!isFinishing) {
                if (ip == null) {
                    tell("Aucune TV trouvée : saisis son adresse IP (Réglages de la TV > Réseau)")
                } else {
                    edenwoodIpField.setText(ip)
                    save()
                    tell("TV Edenwood trouvée : $ip")
                }
            }
        }
    }

    /** Cartes « Décodeur en Wi-Fi direct » et « Clavier dans les applis de la TV », ajoutées avant le texte d'aide. */
    private fun addDeviceSettings() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        val pad = Ui.dp(this, 16)
        card.setPadding(pad, pad, pad, pad)

        card.addView(cardText("Décodeur en Wi-Fi direct", 17f, Color.WHITE, true))
        card.addView(cardText(
            "Pilote le décodeur Android TV directement, sans passer par la TV ni le HDMI-CEC : " +
                "plus fiable, et il peut ouvrir ses applications. Association une seule fois, " +
                "avec le code affiché à l'écran (décodeur allumé, même Wi-Fi).",
            12f, Ui.DIM, false
        ))
        decoderIpField = EditText(this)
        decoderIpField.hint = "Adresse IP du décodeur (ex. 192.168.1.60)"
        decoderIpField.setText(AtvPrefs.ip(this))
        decoderIpField.setSingleLine(true)
        decoderIpField.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        decoderIpField.setTextColor(Color.WHITE)
        decoderIpField.setHintTextColor(Ui.DIM)
        card.addView(decoderIpField, fieldLp())

        decoderState = cardText("", 14f, Color.WHITE, false)
        card.addView(decoderState)
        showDecoderState()

        cardButton(card, "Détecter le décodeur") { detectDecoder() }
        cardButton(card, "Associer le décodeur") { pair(AtvDevice.DECODER) }
        cardButton(card, "Dissocier le décodeur") {
            AtvPrefs.savePaired(this, false)
            thread { AtvRemote.disconnect() }
            showDecoderState()
        }

        card.addView(cardText(
            "Évite la mise en veille automatique du décodeur : tant que la TV est allumée, l'appli lui " +
                "envoie une touche sans effet toutes les 5 minutes (décodeur associé en Wi-Fi direct " +
                "requis). Une notification discrète reste affichée tant que c'est activé.",
            12f, Ui.DIM, false
        ))
        val keepAwake = Button(this)
        keepAwake.isAllCaps = false
        keepAwake.setTextColor(Color.WHITE)
        keepAwake.setBackgroundResource(R.drawable.btn_surface)
        fun paintKeepAwake() {
            keepAwake.text = if (KeepAwake.enabled(this)) "Garder le décodeur éveillé : activé ✓" else "Garder le décodeur éveillé : désactivé"
        }
        paintKeepAwake()
        keepAwake.setOnClickListener {
            save()
            val on = !KeepAwake.enabled(this)
            KeepAwake.setEnabled(this, on)
            paintKeepAwake()
            if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8)
            }
            tell(
                if (!on) "Décodeur : veille automatique non bloquée"
                else if (Decoder.wifi(this)) "Décodeur gardé éveillé tant que la TV est allumée"
                else "Activé, mais associe d'abord le décodeur en Wi-Fi direct pour que ça marche"
            )
        }
        val keepLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48))
        keepLp.topMargin = Ui.dp(this, 8)
        card.addView(keepAwake, keepLp)

        val sep2 = TextView(this)
        sep2.setPadding(0, Ui.dp(this, 14), 0, 0)
        card.addView(sep2)

        card.addView(cardText("Clavier dans les applis de la TV", 17f, Color.WHITE, true))
        card.addView(cardText(
            "Certaines applis de la TV (Molotov…) ont leur propre clavier : le texte y est alors " +
                "envoyé lettre par lettre, sans majuscules ni accents. Association une seule fois, " +
                "avec le code affiché sur la TV (TV allumée, même Wi-Fi).",
            12f, Ui.DIM, false
        ))
        tvKeysState = cardText("", 14f, Color.WHITE, false)
        card.addView(tvKeysState)
        showDecoderState()

        cardButton(card, "Associer la TV") { pair(AtvDevice.TV) }
        cardButton(card, "Dissocier la TV") {
            AtvDevice.TV.savePaired(this, false)
            thread { AtvRemote.disconnect(AtvDevice.TV) }
            showDecoderState()
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 16)
        column.addView(card, column.childCount - 1, lp)
    }

    // ------------------------------------------------------------------ Image de la TV (4K / upscalé)

    private fun addPictureCard() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        val pad = Ui.dp(this, 16)
        card.setPadding(pad, pad, pad, pad)

        card.addView(cardText("Image : 4K ou upscalé", 17f, Color.WHITE, true))
        card.addView(cardText(
            "Détecte si l'image est en 4K natif ou upscalée (HD / SD) et applique les réglages d'image " +
                "adaptés (4K : Cinéma Pro ; upscalé : Cinéma Maison ; contraste, XDR, température de couleur…). " +
                "Si la TV ne donne pas la résolution, choisis toi-même. TV allumée, clé PSK renseignée.",
            12f, Ui.DIM, false
        ))

        cardButton(card, "Détecter et adapter l'image") {
            save()
            tell("Détection de l'image…")
            thread {
                val d = Picture.detect(this)
                runOnUiThread {
                    val kind = d.kind
                    if (kind == null) {
                        askPictureKind(d.reason)
                    } else {
                        applyPicture(kind, "${kind.label} (${d.reason})")
                    }
                }
            }
        }
        cardButton(card, "Appliquer le profil 4K natif") { save(); applyPicture(Picture.Kind.NATIVE_4K, "choix manuel") }
        cardButton(card, "Appliquer le profil upscalé") { save(); applyPicture(Picture.Kind.UPSCALED, "choix manuel") }
        cardButton(card, "Analyser la TV (lecture seule)") {
            save()
            tell("Analyse de la TV…")
            thread {
                val report = Picture.analyze(this)
                runOnUiThread { showReport("Analyse de la TV", report) }
            }
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 16)
        column.addView(card, column.childCount - 1, lp)
    }

    // ------------------------------------------------------------------ Sauvegarde et restauration

    private fun addBackupCard() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        val pad = Ui.dp(this, 16)
        card.setPadding(pad, pad, pad, pad)

        card.addView(cardText("Sauvegarde des réglages", 17f, Color.WHITE, true))
        card.addView(cardText(
            "Exporte tout (TV, décodeur, raccourcis, scènes, profils d'image) pour le restaurer sur un autre " +
                "téléphone. Attention : la sauvegarde contient la clé PSK et les clés d'association, " +
                "ne la partage avec personne.",
            12f, Ui.DIM, false
        ))
        cardButton(card, "Exporter") { exportSettings() }
        cardButton(card, "Importer") { importSettings() }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 16)
        column.addView(card, column.childCount - 1, lp)
    }

    private fun exportSettings() {
        val prefs = getSharedPreferences("bravia_eco", MODE_PRIVATE)
        val root = org.json.JSONObject()
        for ((key, value) in prefs.all) {
            val item = org.json.JSONObject()
            when (value) {
                is String -> item.put("t", "s").put("v", value)
                is Boolean -> item.put("t", "b").put("v", value)
                is Int -> item.put("t", "i").put("v", value)
                is Long -> item.put("t", "l").put("v", value)
                is Float -> item.put("t", "f").put("v", value.toDouble())
                is Set<*> -> item.put("t", "ss").put("v", org.json.JSONArray(value.map { it.toString() }))
                else -> {}
            }
            if (item.length() > 0) root.put(key, item)
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Sauvegarde Éco TV")
            .putExtra(Intent.EXTRA_TEXT, root.toString())
        startActivity(Intent.createChooser(send, "Exporter les réglages"))
    }

    private fun importSettings() {
        val edit = EditText(this)
        edit.hint = "Colle ici la sauvegarde"
        edit.minLines = 4
        edit.gravity = android.view.Gravity.TOP
        AlertDialog.Builder(this)
            .setTitle("Importer des réglages")
            .setMessage("Les réglages actuels de l'appli seront remplacés par ceux de la sauvegarde.")
            .setView(edit)
            .setPositiveButton("Importer") { _, _ -> restoreSettings(edit.text.toString()) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Lit toute la sauvegarde avant de toucher aux réglages : une sauvegarde illisible ne change rien. */
    private fun restoreSettings(text: String) {
        try {
            val root = org.json.JSONObject(text.trim())
            val ops = ArrayList<(android.content.SharedPreferences.Editor) -> Unit>()
            for (key in root.keys()) {
                val item = root.getJSONObject(key)
                when (item.getString("t")) {
                    "s" -> { val v = item.getString("v"); ops.add { it.putString(key, v) } }
                    "b" -> { val v = item.getBoolean("v"); ops.add { it.putBoolean(key, v) } }
                    "i" -> { val v = item.getInt("v"); ops.add { it.putInt(key, v) } }
                    "l" -> { val v = item.getLong("v"); ops.add { it.putLong(key, v) } }
                    "f" -> { val v = item.getDouble("v").toFloat(); ops.add { it.putFloat(key, v) } }
                    "ss" -> {
                        val arr = item.getJSONArray("v")
                        val v = (0 until arr.length()).map { arr.getString(it) }.toSet()
                        ops.add { it.putStringSet(key, v) }
                    }
                    else -> error("Type inconnu pour « $key »")
                }
            }
            if (ops.isEmpty()) error("Sauvegarde vide")
            val editor = getSharedPreferences("bravia_eco", MODE_PRIVATE).edit()
            editor.clear()
            ops.forEach { it(editor) }
            editor.apply()
            tell("Réglages importés : ${ops.size} éléments")
            recreate()
        } catch (e: Exception) {
            tell("Sauvegarde illisible : ${e.message}")
        }
    }

    /** La TV ne dit pas la résolution : l'utilisateur choisit le profil. */
    private fun askPictureKind(reason: String) {
        tell("Résolution inconnue : $reason")
        // Le dernier choix est proposé en premier (bouton principal), l'autre profil en second.
        val last = lastPictureKind()
        val first = last ?: Picture.Kind.NATIVE_4K
        val second = if (first == Picture.Kind.NATIVE_4K) Picture.Kind.UPSCALED else Picture.Kind.NATIVE_4K
        AlertDialog.Builder(this)
            .setTitle("Quel type d'image ?")
            .setMessage("Cette TV ne donne pas la résolution du signal ($reason). Choisis le profil à appliquer.")
            .setPositiveButton(if (last != null) "${first.label} (dernier choix)" else first.label) { _, _ ->
                applyPicture(first, "choix manuel")
            }
            .setNegativeButton(second.label) { _, _ -> applyPicture(second, "choix manuel") }
            .setNeutralButton("Annuler", null)
            .show()
    }

    private fun lastPictureKind(): Picture.Kind? = runCatching {
        val name = getSharedPreferences("bravia_eco", MODE_PRIVATE).getString("picture_kind", null)
        Picture.Kind.values().firstOrNull { it.name == name }
    }.getOrNull()

    private fun savePictureKind(kind: Picture.Kind) {
        getSharedPreferences("bravia_eco", MODE_PRIVATE).edit().putString("picture_kind", kind.name).apply()
    }

    private fun applyPicture(kind: Picture.Kind, why: String) {
        tell("Réglage de l'image : ${kind.label}…")
        thread {
            val result = Picture.adapt(this, kind)
            runOnUiThread {
                val body = result.getOrNull()
                if (body != null) {
                    savePictureKind(kind)
                    tell("Image réglée : ${kind.label}")
                    showReport("Profil ${kind.label}", "Détecté : $why\n\n$body")
                } else {
                    tell("Échec : ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    /** Compte rendu long : fenêtre défilante plutôt qu'une bulle. */
    private fun showReport(title: String, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.setTextIsSelectable(true)
        val p = Ui.dp(this, 20)
        tv.setPadding(p, p / 2, p, 0)
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun detectTv() {
        tell("Recherche de la TV…")
        thread {
            val result = Discovery.findTv()
            runOnUiThread {
                val ip = result.getOrNull()
                if (ip == null) {
                    tell("Échec : ${result.exceptionOrNull()?.message}")
                } else {
                    ipField.setText(ip)
                    save()
                    tell("TV trouvée : $ip")
                }
            }
        }
    }

    private fun detectDecoder() {
        save()
        tell("Recherche du décodeur…")
        Discovery.findDecoder(this, Prefs.ip(this)) { ip ->
            if (!isFinishing) {
                if (ip == null) {
                    tell("Aucun décodeur trouvé : saisis son adresse IP (Réglages du décodeur > Réseau)")
                } else {
                    decoderIpField.setText(ip)
                    save()
                    tell("Décodeur trouvé : $ip")
                }
            }
        }
    }

    private fun pair(device: AtvDevice) {
        save()
        tell(
            when (device) {
                AtvDevice.TV -> "Connexion à la TV…"
                AtvDevice.EDENWOOD -> "Connexion à la Edenwood…"
                AtvDevice.DECODER -> "Connexion au décodeur…"
            }
        )
        thread {
            val started = AtvRemote.startPairing(this, device)
            runOnUiThread {
                val pairing = started.getOrNull()
                if (pairing == null || isFinishing) {
                    pairing?.close()
                    tell("Échec : ${started.exceptionOrNull()?.message}")
                } else {
                    tell(if (device == AtvDevice.DECODER) "Saisis le code affiché à l'écran" else "Saisis le code affiché sur la TV")
                    askPairingCode(pairing, device)
                }
            }
        }
    }

    private fun askPairingCode(pairing: AtvRemote.Pairing, device: AtvDevice) {
        val edit = EditText(this)
        edit.hint = "Code à 6 caractères"
        edit.setSingleLine(true)
        edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.setTextColor(Color.WHITE)
        edit.setHintTextColor(Ui.DIM)
        val box = LinearLayout(this)
        val pad = Ui.dp(this, 20)
        box.setPadding(pad, Ui.dp(this, 8), pad, 0)
        box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (device == AtvDevice.DECODER) "Code affiché à l'écran" else "Code affiché sur la TV")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Valider") { _, _ ->
                val code = edit.text.toString()
                thread {
                    val done = pairing.finish(this, code)
                    runOnUiThread {
                        if (done.isSuccess) {
                            tell(
                                when (device) {
                                    AtvDevice.TV -> "TV associée ✓"
                                    AtvDevice.EDENWOOD -> "Edenwood associée ✓"
                                    AtvDevice.DECODER -> "Décodeur associé ✓"
                                }
                            )
                        } else {
                            tell("Échec : ${done.exceptionOrNull()?.message}")
                        }
                        showDecoderState()
                    }
                }
            }
            .setNegativeButton("Annuler") { _, _ -> thread { pairing.close() } }
            .show()
    }

    private fun pickBrowser() {
        tell("Lecture des applications…")
        thread {
            val result = BraviaClient.getApps(this)
            runOnUiThread {
                val apps = result.getOrNull()
                if (apps == null) {
                    tell("Échec : ${result.exceptionOrNull()?.message}")
                    return@runOnUiThread
                }
                show("")
                val labels = (listOf("Navigateur intégré (par défaut)") + apps.map { it.title }).toTypedArray()
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Navigateur de la TV")
                    .setItems(labels) { _, which ->
                        Prefs.saveWebApp(this, if (which == 0) null else apps[which - 1])
                        showBrowser()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
    }

    private fun show(text: String) {
        status.text = text
    }

    /** loud : le résultat s'affiche aussi en bulle, pour les boutons situés sous la zone d'état. */
    private fun run(progress: String, loud: Boolean = false, work: () -> String) {
        if (loud) tell(progress) else show(progress)
        thread {
            val message = work()
            runOnUiThread {
                if (loud) tell(message) else show(message)
                EcoWidgetProvider.refreshAll(this)
            }
        }
    }
}
