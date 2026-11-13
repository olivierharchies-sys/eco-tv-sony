package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Télécommande de la TV Edenwood (Android TV), en bleu pour la distinguer de la Sony (verte).
 * Même disposition que la Sony, sans le décodeur. Les touches partent en Wi-Fi par le protocole
 * Android TV ([AtvRemote], appareil [AtvDevice.EDENWOOD]). Ce que seule l'API Sony permet est
 * remplacé par un équivalent : Éco → réglages TV, Image → guide, LUM → muet, Plus → touches couleur/infos.
 */
class EdenwoodRemoteActivity : Activity() {

    private val device = AtvDevice.EDENWOOD
    private lateinit var actions: TvActions
    private lateinit var power: PowerButton
    private lateinit var subtitle: TextView
    private lateinit var dpadView: View
    private lateinit var touchpad: View
    private var volLabel: TextView? = null
    private var muteBtn: ImageView? = null

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

        // --- Barre du haut : marche/arrêt, état, historique, muet, Sony, réglages
        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(56)))

        // --- Source (pas de décodeur : le bouton prend toute la ligne)
        val sourceRow = row(dp(52), labeled(R.drawable.ic_input, "Source") { actions.key("Input") })
        (sourceRow.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        root.addView(sourceRow)

        // --- Croix ou pavé tactile ; en haut : réglages TV (à la place d'Éco) et streaming ;
        //     en bas : minuteur et guide des programmes (à la place d'Image).
        dpadView = DpadView(
            this,
            onKey = { key, repeat -> actions.key(key, repeat = repeat) },
            onHold = { key, start -> hold(key, start) }
        )
        touchpad = TouchpadView(this) { key, repeat -> actions.key(key, repeat = repeat) }
        val padBox = FrameLayout(this)
        padBox.addView(dpadView, FrameLayout.LayoutParams(MATCH, MATCH))
        padBox.addView(touchpad, FrameLayout.LayoutParams(MATCH, MATCH))
        applyPadMode()
        val settingsBtn = RoundButton.create(
            this, R.drawable.ic_settings, "Réglages de la TV",
            onClick = { actions.key("Settings") },
            onLongClick = { actions.key("Settings") }
        )
        val appsBtn = RoundButton.create(
            this, R.drawable.ic_apps, "Applications de streaming (appui long : gérer)",
            onClick = { showApps() },
            onLongClick = { manageApps() }
        )
        val timerBtn = RoundButton.create(
            this, R.drawable.ic_timer, "Minuteur d'extinction",
            onClick = { timerDialog() },
            onLongClick = { timerDialog() }
        )
        // Pas de bouton en bas à droite (le guide reste dans « Plus »).
        val dpadArea = DpadArea(this, padBox, settingsBtn, appsBtn, dp(50), timerBtn, null)
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

        // --- Volume / muet (à la place de LUM) / chaînes
        val volumeRow = row(
            dp(104),
            rocker("VOL", "VolumeUp", "VolumeDown").also { volLabel = it.getChildAt(1) as TextView },
            captioned(R.drawable.ic_mute, "Muet") { actions.key("Mute") },
            rocker("CH", "ChannelUp", "ChannelDown")
        )
        (volumeRow.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(volumeRow)

        // --- Lecture
        root.addView(mediaBar(), LinearLayout.LayoutParams(MATCH, dp(48)).also { it.topMargin = dp(10) })

        // --- Clavier, chiffres, tactile
        val bottom = row(
            dp(48),
            labeled(R.drawable.ic_keyboard, "Clavier") { actions.showKeyboard(device) },
            labeled(R.drawable.ic_dialpad, "Chiffres") { actions.showNumpad(favorites = false) },
            labeled(R.drawable.ic_touch, "Tactile") {
                Prefs.saveTouchpad(this, !Prefs.touchpad(this))
                applyPadMode()
            }
        )
        (bottom.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(bottom)

        // --- Web, recherche (à la place des scènes), autres touches
        val extra = row(
            dp(48),
            labeled(R.drawable.ic_language, "Web") { openWeb() },
            labeled(R.drawable.ic_search, "Recherche") { actions.key("Search") },
            labeled(R.drawable.ic_apps, "Plus") { showMore() }
        )
        (extra.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(extra)

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
        RoomSwitch.follow(this) {
            if (it == Prefs.DEVICE_SONY) {
                Prefs.saveRemoteDevice(this, Prefs.DEVICE_SONY)
                startActivity(Intent(this, RemoteActivity::class.java))
                finish()
            }
        }
        Ui.useBlueTheme(true)
        // État marche/veille et volume annoncés par la TV : l'écran suit.
        AtvRemote.onStatus = { d -> if (d == device) runOnUiThread { if (!isFinishing) renderState() } }
        renderState()
        paintMute()
        // Ouvre la connexion d'avance : la première touche ne paie pas la poignée de main TLS.
        if (device.paired(this) && device.ip(this).isNotEmpty()) {
            thread { AtvRemote.warmUp(this, device) }
        }
        if (CallPause.enabled(this)) CallPause.apply(this)
        if (KeepAwake.enabled(this)) KeepAwake.apply(this)
    }

    override fun onPause() {
        RoomSwitch.stop(this)
        AtvRemote.onStatus = null
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
    private fun togglePower() {
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

    /** Lance une application (nom de paquet ou lien) sur la Edenwood et la garde dans les récentes. */
    private fun launchApp(app: EdenwoodApp) {
        EdenwoodApps.addRecent(this, app)
        actions.runIo { AtvRemote.launch(this, EdenwoodApps.linkFor(app), device) }
    }

    /** Page web réglée dans l'appli, ouverte dans le navigateur de la Edenwood. */
    private fun openWeb() {
        var url = Prefs.webUrl(this).trim()
        if (url.isEmpty()) { Ui.toast(this, "Aucune page web réglée"); return }
        if (!url.contains("://")) url = "https://$url"
        actions.runIo { AtvRemote.launch(this, url, device) }
    }

    // ------------------------------------------------------------------ fenêtres

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun showApps() {
        val apps = EdenwoodApps.list(this)
        lateinit var dlg: AlertDialog
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(12), dp(8), dp(12), dp(8))
        apps.forEach { app -> list.addView(appRow(app.title, EdenwoodApps.color(app)) { dlg.dismiss(); launchApp(app) }) }
        list.addView(appRow("Gérer la liste…", Ui.SURFACE, "✎") { dlg.dismiss(); manageApps() })
        val scroll = android.widget.ScrollView(this)
        scroll.addView(list)
        dlg = dialog().setTitle("Streaming").setView(scroll).setNegativeButton("Fermer", null).create()
        dlg.show()
    }

    /** Ligne d'application : pastille colorée avec l'initiale (la TV ne fournit pas les icônes) et nom. */
    private fun appRow(title: String, color: Int, mark: String? = null, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(8), dp(8), dp(8))
        row.isClickable = true
        row.background = Ui.rounded(this, Color.TRANSPARENT, 14)
        row.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        val badge = TextView(this)
        badge.text = mark ?: title.trim().take(1).uppercase()
        badge.gravity = Gravity.CENTER
        badge.textSize = 18f
        badge.setTextColor(Color.WHITE)
        badge.setTypeface(badge.typeface, android.graphics.Typeface.BOLD)
        badge.background = Ui.rounded(this, color, 22)
        row.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val label = TextView(this)
        label.text = title
        label.textSize = 16f
        label.setTextColor(Ui.TEXT)
        label.setPadding(dp(14), 0, 0, 0)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Ajouter une application (nom + paquet ou lien), en retirer une, ou revenir à la liste d'origine. */
    private fun manageApps() {
        val apps = EdenwoodApps.list(this)
        val labels = listOf("＋  Ajouter une application…") + apps.map { "✕  Retirer ${it.title}" } +
            "↺  Liste d'origine"
        dialog().setTitle("Applications Edenwood")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i == 0 -> addApp()
                    i <= apps.size -> EdenwoodApps.save(this, apps - apps[i - 1])
                    else -> EdenwoodApps.reset(this)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun addApp() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val name = EditText(this).apply { hint = "Nom (ex. Auvio)"; setSingleLine(true) }
        val link = EditText(this).apply { hint = "Lien ou paquet (ex. https://www.netflix.com/title)"; setSingleLine(true) }
        box.addView(name)
        box.addView(link)
        dialog().setTitle("Ajouter une application").setView(box)
            .setPositiveButton("Ajouter") { _, _ ->
                val t = name.text.toString().trim()
                val l = link.text.toString().trim()
                if (t.isNotEmpty() && l.isNotEmpty()) EdenwoodApps.save(this, EdenwoodApps.list(this) + EdenwoodApp(t, l))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Applications lancées récemment sur la Edenwood (à la place de l'historique des chaînes Sony). */
    private fun showRecent() {
        val recent = EdenwoodApps.recent(this)
        if (recent.isEmpty()) { Ui.toast(this, "Aucune application lancée récemment"); return }
        dialog().setTitle("Récemment")
            .setItems(recent.map { it.title }.toTypedArray()) { _, i -> launchApp(recent[i]) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Autres touches (à la place de l'écran « Plus » de l'API Sony). */
    private fun showMore() {
        val keys = listOf(
            "Rouge" to "Red", "Vert" to "Green", "Jaune" to "Yellow", "Bleu" to "Blue",
            "Infos" to "Info", "Guide" to "Guide", "Stop" to "Stop", "Réglages de la TV" to "Settings"
        )
        dialog().setTitle("Autres touches")
            .setItems(keys.map { it.first }.toTypedArray()) { _, i -> actions.key(keys[i].second) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun timerDialog() {
        val minutes = intArrayOf(15, 30, 45, 60, 90, 120)
        val labels = ArrayList<String>()
        minutes.forEach { labels.add("Éteindre la Edenwood dans $it min") }
        val remaining = SleepTimer.remainingMinutes(this)
        if (remaining != null) labels.add("Annuler le minuteur (reste $remaining min)")
        dialog().setTitle("Minuteur d'extinction")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < minutes.size) {
                    SleepTimer.set(this, minutes[i], Prefs.DEVICE_EDENWOOD)
                    Ui.toast(this, "La Edenwood s'éteindra dans ${minutes[i]} min")
                } else {
                    SleepTimer.cancel(this)
                    Ui.toast(this, "Minuteur annulé")
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
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
        val on = AtvRemote.powerState(device)
        power.on = on
        val state = when (on) {
            true -> "Allumée · "
            false -> "En veille · "
            null -> ""
        }
        subtitle.text = when {
            device.ip(this).isEmpty() -> "Adresse IP à saisir (Réglages)"
            !device.paired(this) -> "Non associée (Réglages)"
            else -> "$state${device.ip(this)}"
        }
        val vol = AtvRemote.volumeState(device)
        volLabel?.text = when {
            vol == null -> "VOL"
            vol.third -> "VOL muet"
            else -> "VOL ${vol.first}"
        }
    }

    /** Muet bleu = pause automatique pendant les appels activée. */
    private fun paintMute() {
        val on = CallPause.enabled(this)
        muteBtn?.setColorFilter(if (on) Ui.ACCENT else Ui.DIM)
        muteBtn?.contentDescription = if (on) "Muet (pause auto des appels activée)" else "Muet"
    }

    /** Affiche la croix directionnelle ou le pavé tactile, selon le réglage. */
    private fun applyPadMode() {
        val touch = Prefs.touchpad(this)
        dpadView.visibility = if (touch) View.GONE else View.VISIBLE
        touchpad.visibility = if (touch) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ morceaux d'interface

    private fun topIcon(res: Int, tint: Int, description: String): ImageView {
        val v = icon(res, tint)
        v.setPadding(dp(10), dp(10), dp(10), dp(10))
        v.contentDescription = description
        v.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        return v
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        power = PowerButton(this)
        power.contentDescription = "Marche / Arrêt"
        power.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            togglePower()
        }
        bar.addView(power, LinearLayout.LayoutParams(dp(56), dp(56)))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(14), 0, dp(8), 0)

        val title = TextView(this)
        title.text = "Edenwood"
        title.setTextColor(Ui.TEXT)
        title.textSize = 17f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        texts.addView(title)

        subtitle = TextView(this)
        subtitle.setTextColor(Ui.DIM)
        subtitle.textSize = 13f
        subtitle.maxLines = 1
        texts.addView(subtitle)

        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Historique : applications lancées récemment.
        val history = topIcon(R.drawable.ic_history, Ui.DIM, "Récemment")
        history.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showRecent()
        }
        bar.addView(history, LinearLayout.LayoutParams(dp(44), dp(44)))

        // Muet ; appui long : pause automatique pendant les appels.
        val mute = topIcon(R.drawable.ic_mute, Ui.DIM, "Muet")
        mute.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.key("Mute")
        }
        muteBtn = mute
        mute.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val on = !CallPause.enabled(this)
            CallPause.setEnabled(this, on)
            paintMute()
            if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
            }
            Ui.toast(this, if (on) "Pause auto pendant les appels : activée" else "Pause auto pendant les appels : désactivée")
            true
        }
        bar.addView(mute, LinearLayout.LayoutParams(dp(44), dp(44)))

        // Passe à la télécommande Sony (le bouton est vert : c'est la couleur de l'autre télécommande).
        val switch = topIcon(R.drawable.ic_switch_remote, Color.parseColor("#34C77B"), "Télécommande Sony")
        switch.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Prefs.saveRemoteDevice(this, Prefs.DEVICE_SONY)
            startActivity(Intent(this, RemoteActivity::class.java))
            finish()
        }
        bar.addView(switch, LinearLayout.LayoutParams(dp(44), dp(44)))

        val gear = topIcon(R.drawable.ic_settings, Ui.DIM, "Réglages")
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

/** Application de la Edenwood : nom affiché et nom de paquet (ou lien). */
data class EdenwoodApp(val title: String, val link: String)

/** Liste des applications de streaming de la Edenwood (modifiable) et applications récentes. */
object EdenwoodApps {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    /**
     * Liens d'ouverture reconnus par Android TV (le lien « market://launch » n'ouvre pas ces applis).
     * Les anciens noms de paquet enregistrés sont convertis par [linkFor].
     */
    private val DEFAULTS = listOf(
        EdenwoodApp("Netflix", "https://www.netflix.com/title"),
        EdenwoodApp("YouTube", "https://www.youtube.com"),
        EdenwoodApp("Prime Video", "https://app.primevideo.com"),
        EdenwoodApp("Disney+", "https://www.disneyplus.com"),
        EdenwoodApp("Spotify", "spotify://")
    )

    private val PACKAGE_LINKS = mapOf(
        "com.netflix.ninja" to "https://www.netflix.com/title",
        "com.google.android.youtube.tv" to "https://www.youtube.com",
        "com.amazon.amazonvideo.livingroom" to "https://app.primevideo.com",
        "com.disney.disneyplus" to "https://www.disneyplus.com",
        "com.spotify.tv.android" to "spotify://"
    )

    /** Lien à envoyer : paquet connu converti en lien, sinon tel quel. */
    fun linkFor(app: EdenwoodApp): String = PACKAGE_LINKS[app.link.trim()] ?: app.link

    private val COLORS = mapOf(
        "netflix" to 0xFFE50914.toInt(), "youtube" to 0xFFFF0000.toInt(), "prime" to 0xFF00A8E1.toInt(),
        "disney" to 0xFF113CCF.toInt(), "spotify" to 0xFF1DB954.toInt()
    )

    /** Couleur de la pastille : couleur de la marque si connue, sinon couleur stable tirée du nom. */
    fun color(app: EdenwoodApp): Int {
        val t = app.title.lowercase()
        COLORS.entries.firstOrNull { t.contains(it.key) }?.let { return it.value }
        val palette = intArrayOf(0xFF7E57C2.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFF5C6BC0.toInt(), 0xFFAD1457.toInt())
        return palette[(t.hashCode() and 0x7FFFFFFF) % palette.size]
    }

    private fun decode(raw: String?): List<EdenwoodApp> =
        (raw ?: "").split('\n').mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) null else EdenwoodApp(line.substring(0, i), line.substring(i + 1))
        }

    private fun encode(apps: List<EdenwoodApp>) = apps.joinToString("\n") { "${it.title.replace("|", " ")}|${it.link}" }

    fun list(ctx: Context): List<EdenwoodApp> =
        if (sp(ctx).contains("edenwood_apps")) decode(sp(ctx).getString("edenwood_apps", "")) else DEFAULTS

    fun save(ctx: Context, apps: List<EdenwoodApp>) {
        sp(ctx).edit().putString("edenwood_apps", encode(apps)).apply()
    }

    fun reset(ctx: Context) {
        sp(ctx).edit().remove("edenwood_apps").apply()
    }

    fun recent(ctx: Context): List<EdenwoodApp> = decode(sp(ctx).getString("edenwood_recent", ""))

    fun addRecent(ctx: Context, app: EdenwoodApp) {
        val list = (listOf(app) + recent(ctx).filter { it.link != app.link }).take(10)
        sp(ctx).edit().putString("edenwood_recent", encode(list)).apply()
    }
}
