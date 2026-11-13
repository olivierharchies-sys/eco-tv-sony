package com.example.braviaeco

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Télécommande de la TV Edenwood (Android TV), en bleu pour la distinguer de la Sony (verte).
 * Les touches partent en Wi-Fi par le protocole Android TV ([AtvRemote], appareil [AtvDevice.EDENWOOD]).
 * Le bouton « télécommande » de la barre du haut ramène à la télécommande Sony.
 */
class EdenwoodRemoteActivity : Activity() {

    private val device = AtvDevice.EDENWOOD
    private lateinit var actions: TvActions
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.useBlueTheme(true)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        actions = TvActions(this)
        // Toutes les touches (pavé numérique, clavier compris) visent la Edenwood, jamais la Sony.
        actions.customKey = { names, repeat -> sendNames(names, repeat) }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(dp(16), dp(10), dp(16), dp(14))

        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(56)))

        // --- Source (touche « entrée » de la TV) et pavé numérique
        val sourceRow = row(
            dp(52),
            labeled(R.drawable.ic_input, "Source") { actions.key("Input") },
            labeled(R.drawable.ic_dialpad, "Chiffres") { actions.showNumpad(favorites = false) }
        )
        (sourceRow.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        root.addView(sourceRow)

        // --- Croix directionnelle ; en haut : réglages de la TV (gauche) et guide des programmes (droite)
        val dpad = DpadView(
            this,
            onKey = { key, repeat -> actions.key(key, repeat = repeat) },
            onHold = { key, start -> hold(key, start) }
        )
        val settingsBtn = RoundButton.create(
            this, R.drawable.ic_settings, "Réglages de la TV",
            onClick = { actions.key("Settings") },
            onLongClick = { actions.key("Settings") }
        )
        val guideBtn = RoundButton.create(
            this, R.drawable.ic_tv, "Guide des programmes",
            onClick = { actions.key("Guide") },
            onLongClick = { actions.key("Guide") }
        )
        val dpadArea = DpadArea(this, dpad, settingsBtn, guideBtn, dp(50))
        val dpadLp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        dpadLp.topMargin = dp(10)
        dpadLp.bottomMargin = dp(10)
        root.addView(dpadArea, dpadLp)

        // --- Retour / Home / Options
        root.addView(
            row(
                dp(60),
                captioned(R.drawable.ic_back, "Retour") { actions.key("Return") },
                captioned(R.drawable.ic_home, "Home") { actions.key("Home") },
                captioned(R.drawable.ic_more, "Options") { actions.key("Options") }
            )
        )

        // --- Volume / muet / chaînes
        val volumeRow = row(
            dp(104),
            rocker("VOL", "VolumeUp", "VolumeDown"),
            captioned(R.drawable.ic_mute, "Muet") { actions.key("Mute") },
            rocker("CH", "ChannelUp", "ChannelDown")
        )
        (volumeRow.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(volumeRow)

        // --- Lecture
        root.addView(mediaBar(), LinearLayout.LayoutParams(MATCH, dp(48)).also { it.topMargin = dp(10) })

        // --- Clavier
        val bottom = row(dp(48), labeled(R.drawable.ic_keyboard, "Clavier") { actions.showKeyboard(device) })
        (bottom.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(bottom)

        setContentView(root)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        if (RoomSwitch.pick(this) == Prefs.DEVICE_SONY) {
            Prefs.saveRemoteDevice(this, Prefs.DEVICE_SONY)
            startActivity(Intent(this, RemoteActivity::class.java))
            finish()
            return
        }
        Ui.useBlueTheme(true)
        renderState()
        // Ouvre la connexion d'avance : la première touche ne paie pas la poignée de main TLS.
        if (device.paired(this) && device.ip(this).isNotEmpty()) {
            thread { AtvRemote.warmUp(this, device) }
        }
    }

    override fun onPause() {
        // Les autres écrans (réglages…) gardent leur palette verte.
        Ui.useBlueTheme(false)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.close()
    }

    // ------------------------------------------------------------------ envoi

    /** Envoie les touches dans l'ordre vers la Edenwood (noms de la télécommande Sony, convertis en codes Android). */
    private fun sendNames(names: Array<out String>, repeat: Boolean) {
        actions.runIo(repeat) {
            var result: Result<Unit> = Result.success(Unit)
            for (name in names) {
                val code = AtvRemote.keyCode(name)
                    ?: return@runIo Result.failure<Unit>(IllegalArgumentException("Touche inconnue : $name"))
                result = AtvRemote.sendKey(this, code, device)
                if (result.isFailure) break
            }
            result
        }
    }

    /**
     * Marche/arrêt. TV joignable : touche Power (bascule veille / marche). Injoignable (veille profonde) :
     * paquet de réveil réseau si l'adresse MAC est connue, sinon on explique où la saisir.
     */
    private fun power() {
        actions.runIo {
            val ip = device.ip(this)
            if (WakeOnLan.reachable(ip, REMOTE_PORT)) {
                return@runIo AtvRemote.sendKey(this, AtvRemote.keyCode("power") ?: 26, device)
            }
            val mac = EdenwoodPrefs.mac(this)
            if (WakeOnLan.normalize(mac) == null) {
                return@runIo Result.failure<Unit>(
                    IllegalStateException("Edenwood injoignable. Pour l'allumer : saisis son adresse MAC dans Réglages > TV Edenwood")
                )
            }
            WakeOnLan.send(mac, ip)
            Ui.toast(this, "Réveil de la Edenwood envoyé…")
            Result.success(Unit)
        }
    }

    /** Appui maintenu sur une flèche : vrai appui long Android TV plutôt que des appuis courts répétés. */
    private fun hold(name: String, start: Boolean): Boolean {
        val code = AtvRemote.keyCode(name) ?: return false
        actions.runIo {
            AtvRemote.sendKey(
                this, code, device,
                if (start) AtvRemote.PRESS_START_LONG else AtvRemote.PRESS_END_LONG
            )
        }
        return true
    }

    // ------------------------------------------------------------------ touches volume du téléphone

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val key = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "VolumeUp"
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "VolumeDown"
            else -> return super.onKeyDown(keyCode, event)
        }
        actions.key(key, repeat = event.repeatCount > 0)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------ état affiché

    private fun renderState() {
        subtitle.text = when {
            device.ip(this).isEmpty() -> "Adresse IP à saisir (Réglages)"
            !device.paired(this) -> "Non associée (Réglages)"
            else -> "Android TV · ${device.ip(this)}"
        }
    }

    // ------------------------------------------------------------------ morceaux d'interface

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        val power = PowerButton(this)
        power.contentDescription = "Marche / Arrêt"
        power.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            power()
        }
        bar.addView(power, LinearLayout.LayoutParams(dp(56), dp(56)))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(14), 0, dp(8), 0)
        texts.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        val title = TextView(this)
        title.text = "Edenwood"
        title.setTextColor(Ui.ACCENT)
        title.textSize = 17f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        texts.addView(title)

        subtitle = TextView(this)
        subtitle.setTextColor(Ui.DIM)
        subtitle.textSize = 13f
        subtitle.maxLines = 1
        texts.addView(subtitle)

        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Passe à la télécommande Sony (le bouton est vert : c'est la couleur de l'autre télécommande).
        val switch = icon(R.drawable.ic_switch_remote, Color.parseColor("#34C77B"))
        switch.setPadding(dp(10), dp(10), dp(10), dp(10))
        switch.contentDescription = "Télécommande Sony"
        switch.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        switch.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Prefs.saveRemoteDevice(this, Prefs.DEVICE_SONY)
            startActivity(Intent(this, RemoteActivity::class.java))
            finish()
        }
        bar.addView(switch, LinearLayout.LayoutParams(dp(44), dp(44)))

        val gear = icon(R.drawable.ic_settings, Ui.DIM)
        gear.setPadding(dp(10), dp(10), dp(10), dp(10))
        gear.contentDescription = "Réglages"
        gear.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        gear.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        bar.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))

        return bar
    }

    /** Double bouton + / − avec un libellé au milieu. Reste appuyé = répétition. */
    private fun rocker(label: String, upKey: String, downKey: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(this, Ui.SURFACE, 34)

        val plus = icon(R.drawable.ic_add, Ui.TEXT)
        plus.setPadding(dp(12), dp(12), dp(12), dp(12))
        plus.contentDescription = "$label plus"
        plus.background = Ui.rounded(this, Color.TRANSPARENT, 34)
        Ui.holdRepeat(plus) { repeat -> actions.key(upKey, repeat = repeat) }

        val minus = icon(R.drawable.ic_remove, Ui.TEXT)
        minus.setPadding(dp(12), dp(12), dp(12), dp(12))
        minus.contentDescription = "$label moins"
        minus.background = Ui.rounded(this, Color.TRANSPARENT, 34)
        Ui.holdRepeat(minus) { repeat -> actions.key(downKey, repeat = repeat) }

        val text = TextView(this)
        text.text = label
        text.setTextColor(Ui.DIM)
        text.textSize = 11f
        text.gravity = Gravity.CENTER

        box.addView(plus, LinearLayout.LayoutParams(MATCH, 0, 1f))
        box.addView(text, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(minus, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return box
    }

    private fun mediaBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = Ui.rounded(this, Ui.SURFACE, 24)

        val keys = listOf(
            Triple(R.drawable.ic_prev, "Précédent", "Prev"),
            Triple(R.drawable.ic_rewind, "Retour rapide", "Rewind"),
            Triple(R.drawable.ic_play, "Lecture", "Play"),
            Triple(R.drawable.ic_pause, "Pause", "Pause"),
            Triple(R.drawable.ic_forward, "Avance rapide", "Forward"),
            Triple(R.drawable.ic_next, "Suivant", "Next")
        )
        for ((res, description, key) in keys) {
            val b = icon(res, Ui.TEXT)
            b.setPadding(dp(11), dp(11), dp(11), dp(11))
            b.contentDescription = description
            b.background = Ui.rounded(this, Color.TRANSPARENT, 24)
            b.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                actions.key(key)
            }
            bar.addView(b, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        return bar
    }

    // ------------------------------------------------------------------ fabriques

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun icon(res: Int, tint: Int): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(tint)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        return v
    }

    /** Bouton carré : icône avec un petit libellé dessous. */
    private fun captioned(res: Int, text: String, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.background = Ui.rounded(this, Ui.SURFACE, 20)
        box.isClickable = true
        box.contentDescription = text
        box.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        box.addView(icon(res, Ui.TEXT), LinearLayout.LayoutParams(dp(24), dp(24)))

        val label = TextView(this)
        label.text = text
        label.setTextColor(Ui.DIM)
        label.textSize = 10f
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        box.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    /** Bouton « pilule » : icône + libellé. */
    private fun labeled(res: Int, text: String, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER
        box.background = Ui.rounded(this, Ui.SURFACE, 26)
        box.isClickable = true
        box.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }

        box.addView(icon(res, Ui.TEXT), LinearLayout.LayoutParams(dp(22), dp(22)))

        val label = TextView(this)
        label.text = text
        label.setTextColor(Ui.TEXT)
        label.textSize = 14f
        label.maxLines = 1
        label.setPadding(dp(8), 0, 0, 0)
        box.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    /** Ligne de hauteur fixe dont les enfants se partagent la largeur. */
    private fun row(height: Int, vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        val gap = dp(4)
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
            lp.setMargins(gap, 0, gap, 0)
            r.addView(v, lp)
        }
        r.layoutParams = LinearLayout.LayoutParams(MATCH, height)
        return r
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Port de la télécommande Android TV (voir [AtvRemote]). */
        const val REMOTE_PORT = 6466
    }
}
