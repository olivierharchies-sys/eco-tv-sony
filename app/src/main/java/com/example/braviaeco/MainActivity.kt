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
    }

    /** Carte « Détection de la TV » et « Décodeur en Wi-Fi direct », ajoutée avant le texte d'aide. */
    private fun addDeviceSettings() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        val pad = Ui.dp(this, 16)
        card.setPadding(pad, pad, pad, pad)

        card.addView(cardText("Détection de la TV", 17f, Color.WHITE, true))
        card.addView(cardText("Cherche la TV Sony sur le Wi-Fi et remplit son adresse IP (TV allumée).", 12f, Ui.DIM, false))
        cardButton(card, "Détecter la TV") { detectTv() }

        val sep = TextView(this)
        sep.setPadding(0, Ui.dp(this, 14), 0, 0)
        card.addView(sep)

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
        tell(if (device == AtvDevice.TV) "Connexion à la TV…" else "Connexion au décodeur…")
        thread {
            val started = AtvRemote.startPairing(this, device)
            runOnUiThread {
                val pairing = started.getOrNull()
                if (pairing == null || isFinishing) {
                    pairing?.close()
                    tell("Échec : ${started.exceptionOrNull()?.message}")
                } else {
                    tell(if (device == AtvDevice.TV) "Saisis le code affiché sur la TV" else "Saisis le code affiché à l'écran")
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
            .setTitle(if (device == AtvDevice.TV) "Code affiché sur la TV" else "Code affiché à l'écran")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Valider") { _, _ ->
                val code = edit.text.toString()
                thread {
                    val done = pairing.finish(this, code)
                    runOnUiThread {
                        if (done.isSuccess) {
                            tell(if (device == AtvDevice.TV) "TV associée ✓" else "Décodeur associé ✓")
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
