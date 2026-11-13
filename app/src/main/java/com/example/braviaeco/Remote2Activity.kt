package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Télécommande « Zen » : on regarde la TV, pas le téléphone.
 * Une grande surface de gestes (navigation, volume, chaînes), quatre gros boutons,
 * et une rangée de raccourcis qui réunit scènes, chaînes favorites et applications.
 */
class Remote2Activity : Activity() {

    private lateinit var actions: TvActions
    private lateinit var scenes: SceneUi
    private lateinit var power: PowerButton
    private lateinit var targetLabel: TextView
    private lateinit var stateLabel: TextView
    private lateinit var tileRows: List<LinearLayout>
    private var playing = true

    private class Tile(val label: String, val color: Int, val action: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.useBlueTheme(false)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        actions = TvActions(this)
        actions.onStateChanged = { renderState() }
        scenes = SceneUi(this, actions)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(dp(16), dp(10), dp(16), dp(14))

        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, dp(56)))

        // --- Surface de gestes : prend toute la place restante
        val pad = GesturePadView(this) { action ->
            if (action == GesturePadView.NUMPAD) actions.showNumpad() else actions.key(action)
        }
        val padLp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        padLp.topMargin = dp(12)
        root.addView(pad, padLp)

        // --- Quatre gros boutons
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        addBig(buttons, R.drawable.ic_back, "Retour", { actions.key("Return", "Back") }, null)
        addBig(buttons, R.drawable.ic_home, "Accueil", { home(false) }, { home(true) })
        addBig(buttons, R.drawable.ic_play, "Lecture / Pause", { playPause() }, null)
        addBig(buttons, R.drawable.ic_more, "Plus", { showMore() }, null)
        val buttonsLp = LinearLayout.LayoutParams(MATCH, dp(64))
        buttonsLp.topMargin = dp(12)
        root.addView(buttons, buttonsLp)

        // --- Raccourcis : scènes, chaînes favorites, applications
        val caption = TextView(this)
        caption.text = "Raccourcis"
        caption.setTextColor(Ui.DIM)
        caption.textSize = 12f
        caption.setPadding(dp(6), dp(12), 0, dp(4))
        root.addView(caption)

        val rowA = LinearLayout(this)
        rowA.orientation = LinearLayout.HORIZONTAL
        val rowB = LinearLayout(this)
        rowB.orientation = LinearLayout.HORIZONTAL
        tileRows = listOf(rowA, rowB)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(rowA, LinearLayout.LayoutParams(WRAP, dp(50)))
        val rowBLp = LinearLayout.LayoutParams(WRAP, dp(50))
        rowBLp.topMargin = dp(6)
        column.addView(rowB, rowBLp)
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        if (RoomSwitch.pick(this) == Prefs.DEVICE_EDENWOOD) {
            Prefs.saveRemoteDevice(this, Prefs.DEVICE_EDENWOOD)
            startActivity(Intent(this, EdenwoodRemoteActivity::class.java))
            finish()
            return
        }
        renderState()
        buildTiles()
        actions.refreshPower()
        if (KeepAwake.enabled(this)) KeepAwake.apply(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.close()
    }

    // ------------------------------------------------------------------ touches volume du téléphone

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "VolumeUp"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "VolumeDown"
            else -> return super.onKeyDown(keyCode, event)
        }
        actions.sendKey(key, skipIfBusy = event.repeatCount > 0)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------ état affiché

    private fun renderState() {
        val on = Prefs.lastPower(this)
        power.on = on
        val decoder = Prefs.target(this) == Target.DECODER
        targetLabel.text = when {
            !decoder -> "TV  ⇄"
            Decoder.wifi(this) -> "Décodeur · Wi-Fi  ⇄"
            else -> "Décodeur  ⇄"
        }
        val state = when (on) {
            true -> "Allumée"
            false -> "En veille"
            null -> "État inconnu"
        }
        val source = Prefs.lastSource(this)
        stateLabel.text = if (on == true && source != null) "$state · $source" else state
    }

    // ------------------------------------------------------------------ actions

    /** Accueil de l'appareil visé ; other = celui de l'autre appareil (appui long). */
    private fun home(other: Boolean) {
        val decoder = Prefs.target(this) == Target.DECODER
        if (decoder != other) actions.deviceHome() else actions.tvHome()
    }

    private fun playPause() {
        if (Prefs.target(this) == Target.DECODER && Decoder.wifi(this)) {
            actions.key("PlayPause")
        } else {
            // La TV n'a pas de touche unique : on alterne Pause et Lecture.
            actions.key(if (playing) "Pause" else "Play")
            playing = !playing
        }
    }

    private fun toggleTarget() {
        val next = if (Prefs.target(this) == Target.DECODER) Target.TV else Target.DECODER
        actions.selectTarget(next)
    }

    private fun showMore() {
        val labels = arrayOf(
            "Source", "Clavier", "Pavé numérique", "Volume précis", "Scènes et minuterie",
            "Chaînes favorites", "Page web", "Toutes les commandes", "Réglages", "Télécommande classique"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Plus")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> actions.showSourcePicker()
                    1 -> actions.showKeyboard()
                    2 -> actions.showNumpad()
                    3 -> actions.showVolume()
                    4 -> scenes.show()
                    5 -> Favorites.show(this, actions)
                    6 -> actions.openWeb()
                    7 -> actions.openMore()
                    8 -> startActivity(Intent(this, MainActivity::class.java))
                    else -> switchToClassic()
                }
            }
            .show()
    }

    private fun switchToClassic() {
        Prefs.saveRemoteStyle(this, Prefs.STYLE_CLASSIC)
        startActivity(Intent(this, RemoteActivity::class.java))
        finish()
    }

    // ------------------------------------------------------------------ raccourcis

    /** Reconstruit les tuiles : scènes, chaînes favorites, raccourcis du décodeur, applis de la TV. */
    private fun buildTiles() {
        val tiles = ArrayList<Tile>()
        for (scene in ScenePrefs.scenes(this)) {
            tiles.add(Tile("▶ " + scene.name, Ui.ACCENT) {
                Ui.toast(this, "Scène « ${scene.name} »…")
                launch { SceneRunner.run(this, scene) }
            })
        }
        for (channel in ChannelPrefs.channels(this)) {
            tiles.add(Tile("${channel.number} · ${channel.name}", Ui.TEXT) {
                launch { Decoder.zap(this, channel.number) }
            })
        }
        for (app in ShortcutPrefs.decoderApps(this)) {
            tiles.add(Tile(app.name, Ui.TEXT) {
                launch { Decoder.run(this, app.keys) }
            })
        }
        for (app in ShortcutPrefs.tvApps(this)) {
            tiles.add(Tile(app.title, Ui.TEXT) {
                launch { BraviaClient.launchApp(this, app.uri) }
            })
        }
        if (tiles.isEmpty()) {
            tiles.add(Tile("＋ Ajouter des raccourcis", Ui.DIM) { actions.openMore() })
        }

        tileRows.forEach { it.removeAllViews() }
        // Une seule ligne tant qu'il y a peu de raccourcis, deux lignes ensuite.
        val twoRows = tiles.size > 4
        for ((i, tile) in tiles.withIndex()) {
            val view = TextView(this)
            view.text = tile.label
            view.setTextColor(tile.color)
            view.textSize = 13f
            view.gravity = Gravity.CENTER
            view.maxLines = 2
            view.ellipsize = TextUtils.TruncateAt.END
            view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            view.setPadding(dp(8), 0, dp(8), 0)
            view.background = Ui.rounded(this, Ui.SURFACE, 16)
            view.isClickable = true
            view.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tile.action()
            }
            val lp = LinearLayout.LayoutParams(dp(108), MATCH)
            lp.rightMargin = dp(6)
            val row = if (twoRows) tileRows[i % 2] else tileRows[0]
            row.addView(view, lp)
        }
        tileRows[1].visibility = if (twoRows) View.VISIBLE else View.GONE
    }

    /** Lance un raccourci hors du thread principal, puis rafraîchit l'état affiché. */
    private fun launch(work: () -> Result<*>) {
        actions.runIo {
            val r = work()
            actions.notifyStateChanged()
            r
        }
    }

    // ------------------------------------------------------------------ morceaux d'interface

    private fun buildHeader(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        power = PowerButton(this)
        power.contentDescription = "Marche / Arrêt"
        power.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.togglePower()
        }
        bar.addView(power, LinearLayout.LayoutParams(dp(56), dp(56)))

        // Appareil visé (toucher pour basculer TV / décodeur) et état de la TV.
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.CENTER_VERTICAL
        texts.setPadding(dp(14), 0, dp(8), 0)
        texts.background = Ui.rounded(this, Color.TRANSPARENT, 18)
        texts.isClickable = true
        texts.contentDescription = "Basculer entre la TV et le décodeur"
        texts.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleTarget()
        }

        targetLabel = TextView(this)
        targetLabel.setTextColor(Ui.ACCENT)
        targetLabel.textSize = 17f
        targetLabel.maxLines = 1
        targetLabel.setTypeface(targetLabel.typeface, Typeface.BOLD)
        texts.addView(targetLabel)

        stateLabel = TextView(this)
        stateLabel.setTextColor(Ui.DIM)
        stateLabel.textSize = 13f
        stateLabel.maxLines = 1
        texts.addView(stateLabel)

        bar.addView(texts, LinearLayout.LayoutParams(0, MATCH, 1f))

        // Passe à la télécommande Edenwood (bleue).
        val edenwood = ImageView(this)
        edenwood.setImageResource(R.drawable.ic_switch_remote)
        edenwood.setColorFilter(Color.parseColor("#3D8BFF"))
        edenwood.scaleType = ImageView.ScaleType.FIT_CENTER
        edenwood.setPadding(dp(10), dp(10), dp(10), dp(10))
        edenwood.contentDescription = "Télécommande Edenwood"
        edenwood.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        edenwood.setOnClickListener {
            Prefs.saveRemoteDevice(this, Prefs.DEVICE_EDENWOOD)
            startActivity(Intent(this, EdenwoodRemoteActivity::class.java))
            finish()
        }
        bar.addView(edenwood, LinearLayout.LayoutParams(dp(44), dp(44)))

        val swap = ImageView(this)
        swap.setImageResource(R.drawable.ic_swap)
        swap.setColorFilter(Ui.DIM)
        swap.scaleType = ImageView.ScaleType.FIT_CENTER
        swap.setPadding(dp(10), dp(10), dp(10), dp(10))
        swap.contentDescription = "Télécommande classique"
        swap.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        swap.setOnClickListener { switchToClassic() }
        bar.addView(swap, LinearLayout.LayoutParams(dp(44), dp(44)))

        return bar
    }

    /** Gros bouton : icône et libellé ; appui long facultatif. */
    private fun addBig(parent: LinearLayout, res: Int, text: String, onClick: () -> Unit, onLong: (() -> Unit)?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.background = Ui.rounded(this, Ui.SURFACE, 22)
        box.isClickable = true
        box.contentDescription = text
        box.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        if (onLong != null) {
            box.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLong()
                true
            }
        }

        val icon = ImageView(this)
        icon.setImageResource(res)
        icon.setColorFilter(Ui.TEXT)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        box.addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))

        val label = TextView(this)
        label.text = text
        label.setTextColor(Ui.DIM)
        label.textSize = 10f
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        box.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))

        val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
        lp.setMargins(dp(4), 0, dp(4), 0)
        parent.addView(box, lp)
    }

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
