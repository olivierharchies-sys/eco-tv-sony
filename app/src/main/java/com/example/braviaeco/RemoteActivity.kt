package com.example.braviaeco

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Télécommande sur un seul écran (sans défilement) :
 * marche/arrêt, choix TV / décodeur et source, croix directionnelle ou pavé tactile,
 * retour/accueil/options, volume et chaînes, lecture, puis clavier, chiffres, scènes.
 */
class RemoteActivity : Activity() {

    private lateinit var actions: TvActions
    private lateinit var shortcuts: AppShortcuts
    private lateinit var power: PowerButton
    private lateinit var subtitle: TextView
    private lateinit var scenes: SceneUi
    private lateinit var segTv: TextView
    private lateinit var segDecoder: TextView
    private lateinit var dpadView: View
    private lateinit var touchpad: View
    private var brightness: BrightnessControl? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        actions = TvActions(this)
        actions.onStateChanged = { renderState() }
        shortcuts = AppShortcuts(this, actions)
        scenes = SceneUi(this, actions)

        // Télécommande « Zen » choisie : on lui passe la main (widgets et icône arrivent ici).
        if (Prefs.remoteStyle(this) == Prefs.STYLE_ZEN) {
            startActivity(Intent(this, Remote2Activity::class.java))
            finish()
            return
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(dp(16), dp(10), dp(16), dp(14))

        // --- Barre du haut : marche/arrêt, état, réglages
        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(56)))

        // --- Appareil visé (TV ou décodeur) et bouton Source
        val source = labeled(R.drawable.ic_input, "Source") { actions.showSourcePicker() }
        val targetRow = LinearLayout(this)
        targetRow.orientation = LinearLayout.HORIZONTAL
        val switchLp = LinearLayout.LayoutParams(0, MATCH, 2f)
        switchLp.rightMargin = dp(8)
        targetRow.addView(buildTargetSwitch(), switchLp)
        targetRow.addView(source, LinearLayout.LayoutParams(0, MATCH, 1f))
        val sourceLp = LinearLayout.LayoutParams(MATCH, dp(52))
        sourceLp.topMargin = dp(10)
        root.addView(targetRow, sourceLp)

        // --- Croix directionnelle (prend toute la place restante), avec deux boutons ronds
        //     en haut : à gauche l'économie d'énergie (Haut / Bas), à droite le streaming de la TV.
        //     Au choix : croix classique ou pavé tactile (bouton « Tactile »).
        dpadView = DpadView(
            this,
            onKey = { key, repeat -> actions.key(key, repeat = repeat) },
            onHold = { key, start -> actions.hold(key, start) }
        )
        touchpad = TouchpadView(this) { key, repeat -> actions.key(key, repeat = repeat) }
        val padBox = FrameLayout(this)
        padBox.addView(dpadView, FrameLayout.LayoutParams(MATCH, MATCH))
        padBox.addView(touchpad, FrameLayout.LayoutParams(MATCH, MATCH))
        applyPadMode()
        val decoderBtn = RoundButton.create(
            this, R.drawable.ic_eco, "Économie d'énergie : Haut / Bas",
            onClick = { actions.toggleEco() },
            onLongClick = { shortcuts.showDecoderApps() }
        )
        val tvAppsBtn = RoundButton.create(
            this, R.drawable.ic_apps, "Applications de streaming",
            onClick = { shortcuts.showStreamingApps() },
            onLongClick = { shortcuts.showTvApps() }
        )
        // En bas : à gauche les actions rapides (écran éteint, minuteur), à droite l'image (profils mémorisés).
        val quickMenu = QuickMenu(this)
        val pictureMenu = PictureMenu(this)
        val quickBtn = RoundButton.create(
            this, R.drawable.ic_timer, "Actions rapides : écran éteint, minuteur",
            onClick = { quickMenu.show() },
            onLongClick = { quickMenu.show() }
        )
        val pictureBtn = RoundButton.create(
            this, R.drawable.ic_tune, "Image : profils mémorisés (appui long : menu)",
            onClick = { pictureMenu.onTap() },
            onLongClick = { pictureMenu.onLongPress() }
        )
        val dpadArea = DpadArea(this, padBox, decoderBtn, tvAppsBtn, dp(50), quickBtn, pictureBtn)
        val dpadLp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        dpadLp.topMargin = dp(10)
        dpadLp.bottomMargin = dp(10)
        root.addView(dpadArea, dpadLp)

        // --- Retour / Home (appareil visé) / Options
        root.addView(
            row(
                dp(60),
                captioned(R.drawable.ic_back, "Retour") { actions.key("Return", "Back") },
                captioned(R.drawable.ic_home, "Home") {
                    // Accueil de l'appareil visé par le sélecteur TV / Décodeur.
                    if (Prefs.target(this) == Target.DECODER) actions.deviceHome() else actions.tvHome()
                },
                captioned(R.drawable.ic_more, "Options") { actions.key("Options") }
            )
        )

        // --- Volume / muet / chaînes
        val volumeRow = row(
            dp(104),
            rocker("VOL", "VolumeUp", "VolumeDown"),
            brightnessRocker(),
            rocker("CH", "ChannelUp", "ChannelDown")
        )
        (volumeRow.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(volumeRow)

        // --- Lecture
        root.addView(mediaBar(), LinearLayout.LayoutParams(MATCH, dp(48)).also { it.topMargin = dp(10) })

        // --- Clavier et pavé numérique
        val bottom = row(
            dp(48),
            labeled(R.drawable.ic_keyboard, "Clavier") { actions.showKeyboard() },
            labeled(R.drawable.ic_dialpad, "Chiffres") { actions.showNumpad() },
            labeled(R.drawable.ic_touch, "Tactile") {
                Prefs.saveTouchpad(this, !Prefs.touchpad(this))
                applyPadMode()
            }
        )
        (bottom.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(bottom)

        // --- Web et toutes les autres commandes
        val extra = row(
            dp(48),
            labeled(R.drawable.ic_language, "Web") { actions.openWeb() },
            labeled(R.drawable.ic_scene, "Scènes") { scenes.show() },
            labeled(R.drawable.ic_apps, "Plus") { actions.openMore() }
        )
        (extra.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(extra)

        setContentView(root)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        renderState()
        actions.refreshPower()
    }

    override fun onDestroy() {
        brightness?.close()
        super.onDestroy()
        actions.close()
        shortcuts.close()
    }

    // ------------------------------------------------------------------ touches volume du téléphone

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val key = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "VolumeUp"
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "VolumeDown"
            else -> return super.onKeyDown(keyCode, event)
        }
        actions.sendKey(key, skipIfBusy = event.repeatCount > 0)
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
        val on = Prefs.lastPower(this)
        power.on = on
        val target = Prefs.target(this)
        paintSegment(segTv, target == Target.TV)
        paintSegment(segDecoder, target == Target.DECODER)
        val state = when (on) {
            true -> "Allumée"
            false -> "En veille"
            null -> "État inconnu"
        }
        val source = Prefs.lastSource(this)
        subtitle.text = if (on == true && source != null) "$state · $source" else state
    }

    // ------------------------------------------------------------------ morceaux d'interface

    /** Sélecteur à deux positions : la croix, les chaînes et la lecture visent la TV ou le décodeur. */
    private fun buildTargetSwitch(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.background = Ui.rounded(this, Ui.SURFACE, 26)
        box.setPadding(dp(4), dp(4), dp(4), dp(4))
        segTv = segment("TV", Target.TV)
        segDecoder = segment("Décodeur", Target.DECODER)
        box.addView(segTv, LinearLayout.LayoutParams(0, MATCH, 1f))
        box.addView(segDecoder, LinearLayout.LayoutParams(0, MATCH, 1f))
        return box
    }

    private fun segment(text: String, target: Target): TextView {
        val t = TextView(this)
        t.text = text
        t.gravity = Gravity.CENTER
        t.textSize = 15f
        t.maxLines = 1
        t.isClickable = true
        t.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.selectTarget(target)
        }
        return t
    }

    private fun paintSegment(view: TextView, selected: Boolean) {
        view.background = if (selected) Ui.rounded(this, Ui.ACCENT, 22) else null
        view.setTextColor(if (selected) Ui.BG else Ui.TEXT)
    }

    /** Affiche la croix directionnelle ou le pavé tactile, selon le réglage. */
    private fun applyPadMode() {
        val touch = Prefs.touchpad(this)
        dpadView.visibility = if (touch) View.GONE else View.VISIBLE
        touchpad.visibility = if (touch) View.VISIBLE else View.GONE
    }

    private fun buildTopBar(): LinearLayout {
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

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(14), 0, dp(8), 0)

        val title = TextView(this)
        title.text = "Sony Bravia"
        title.setTextColor(Ui.TEXT)
        title.textSize = 17f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        texts.addView(title)

        subtitle = TextView(this)
        subtitle.setTextColor(Ui.DIM)
        subtitle.textSize = 13f
        texts.addView(subtitle)

        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Muet : à gauche du bouton de changement de télécommande.
        val mute = icon(R.drawable.ic_mute, Ui.DIM)
        mute.setPadding(dp(10), dp(10), dp(10), dp(10))
        mute.contentDescription = "Muet"
        mute.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        mute.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.sendKey("Mute")
        }
        bar.addView(mute, LinearLayout.LayoutParams(dp(44), dp(44)))

        val swap = icon(R.drawable.ic_swap, Ui.DIM)
        swap.setPadding(dp(10), dp(10), dp(10), dp(10))
        swap.contentDescription = "Télécommande Zen"
        swap.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        swap.setOnClickListener {
            Prefs.saveRemoteStyle(this, Prefs.STYLE_ZEN)
            startActivity(Intent(this, Remote2Activity::class.java))
            finish()
        }
        bar.addView(swap, LinearLayout.LayoutParams(dp(44), dp(44)))

        val gear = icon(R.drawable.ic_settings, Ui.DIM)
        gear.setPadding(dp(10), dp(10), dp(10), dp(10))
        gear.contentDescription = "Réglages"
        gear.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        gear.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        bar.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))

        return bar
    }

    /** Double bouton + / − avec un libellé au milieu. Reste appuyé = répétition. */
    private fun rocker(label: String, upKey: String, downKey: String): LinearLayout =
        rocker(label, { repeat -> actions.key(upKey, repeat = repeat) }, { repeat -> actions.key(downKey, repeat = repeat) })

    private fun rocker(label: String, onUp: (Boolean) -> Unit, onDown: (Boolean) -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = Ui.rounded(this, Ui.SURFACE, 34)

        val plus = icon(R.drawable.ic_add, Ui.TEXT)
        plus.setPadding(dp(12), dp(12), dp(12), dp(12))
        plus.contentDescription = "$label plus"
        plus.background = Ui.rounded(this, Color.TRANSPARENT, 34)
        Ui.holdRepeat(plus) { repeat -> onUp(repeat) }

        val minus = icon(R.drawable.ic_remove, Ui.TEXT)
        minus.setPadding(dp(12), dp(12), dp(12), dp(12))
        minus.contentDescription = "$label moins"
        minus.background = Ui.rounded(this, Color.TRANSPARENT, 34)
        Ui.holdRepeat(minus) { repeat -> onDown(repeat) }

        val text = TextView(this)
        text.text = label
        text.setTextColor(Ui.DIM)
        text.textSize = 11f
        text.gravity = Gravity.CENTER
        // Appui long sur « VOL » : curseur de volume.
        if (label == "VOL") {
            text.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                actions.showVolume()
                true
            }
        }

        box.addView(plus, LinearLayout.LayoutParams(MATCH, 0, 1f))
        box.addView(text, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(minus, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return box
    }

    /** Double bouton + / − de la luminosité de l'image, comme celui du volume. */
    private fun brightnessRocker(): LinearLayout {
        lateinit var label: TextView
        val control = BrightnessControl(this) { label.text = "LUM $it" }
        brightness = control
        val box = rocker("LUM", { _ -> control.nudge(1) }, { _ -> control.nudge(-1) })
        label = box.getChildAt(1) as TextView
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

    private fun iconButton(res: Int, description: String, onClick: () -> Unit): ImageView {
        val v = icon(res, Ui.TEXT)
        v.setPadding(dp(14), dp(14), dp(14), dp(14))
        v.contentDescription = description
        v.background = Ui.rounded(this, Ui.SURFACE, 20)
        v.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
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
    }
}

/**
 * Zone de la croix directionnelle : la croix (carrée, centrée) et deux boutons ronds
 * placés au-dessus de ses coins haut-gauche et haut-droite, de part et d'autre de la flèche « haut ».
 */
@android.annotation.SuppressLint("ViewConstructor")
class DpadArea(
    context: android.content.Context,
    private val dpad: View,
    private val leftBtn: View,
    private val rightBtn: View,
    private val buttonSize: Int,
    private val bottomLeftBtn: View? = null,
    private val bottomRightBtn: View? = null
) : ViewGroup(context) {

    init {
        addView(dpad)
        addView(leftBtn)
        addView(rightBtn)
        bottomLeftBtn?.let { addView(it) }
        bottomRightBtn?.let { addView(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (h > 0) minOf(w, h) else w
        val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        dpad.measure(exact, exact)
        val bs = MeasureSpec.makeMeasureSpec(buttonSize, MeasureSpec.EXACTLY)
        leftBtn.measure(bs, bs)
        rightBtn.measure(bs, bs)
        bottomLeftBtn?.measure(bs, bs)
        bottomRightBtn?.measure(bs, bs)
        setMeasuredDimension(w, if (h > 0) h else side)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val side = dpad.measuredWidth
        val dl = (w - side) / 2
        val dt = (h - side) / 2
        dpad.layout(dl, dt, dl + side, dt + side)

        // Centre des boutons sur les coins du carré de la croix, décalé vers l'extérieur
        // s'il y a de la place, pour ne jamais toucher le cercle.
        val half = buttonSize / 2
        val out = minOf(dl, half)
        val top = maxOf(0, dt - half / 2)
        val lx = maxOf(0, dl - out)
        val rx = minOf(w - buttonSize, dl + side + out - buttonSize)
        leftBtn.layout(lx, top, lx + buttonSize, top + buttonSize)
        rightBtn.layout(rx, top, rx + buttonSize, top + buttonSize)

        // Boutons du bas : symétriques de ceux du haut, sous les coins bas-gauche et bas-droit.
        val bottomY = minOf(h, dt + side + half / 2) - buttonSize
        bottomLeftBtn?.layout(lx, bottomY, lx + buttonSize, bottomY + buttonSize)
        bottomRightBtn?.layout(rx, bottomY, rx + buttonSize, bottomY + buttonSize)
    }
}
