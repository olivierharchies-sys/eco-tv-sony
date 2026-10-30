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
 * marche/arrêt unique, un bouton Source, croix directionnelle, retour/accueil/options,
 * volume et chaînes, lecture, puis clavier et pavé numérique.
 */
class RemoteActivity : Activity() {

    private lateinit var actions: TvActions
    private lateinit var shortcuts: AppShortcuts
    private lateinit var power: PowerButton
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        actions = TvActions(this)
        actions.onStateChanged = { renderState() }
        shortcuts = AppShortcuts(this, actions)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(dp(16), dp(10), dp(16), dp(14))

        // --- Barre du haut : marche/arrêt, état, réglages
        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(56)))

        // --- Source (un seul bouton)
        val source = labeled(R.drawable.ic_input, "Source") { actions.showSourcePicker() }
        val sourceLp = LinearLayout.LayoutParams(MATCH, dp(52))
        sourceLp.topMargin = dp(10)
        root.addView(source, sourceLp)

        // --- Croix directionnelle (prend toute la place restante), avec deux boutons ronds
        //     en haut : à gauche les applications du décodeur, à droite celles de la TV.
        val dpad = DpadView(this) { key, repeat -> actions.sendKey(key, skipIfBusy = repeat) }
        val decoderBtn = RoundButton.create(
            this, R.drawable.ic_set_top_box, "Applications du décodeur",
            onClick = { shortcuts.showDecoderApps() },
            onLongClick = { shortcuts.showDecoderApps() }
        )
        val tvAppsBtn = RoundButton.create(
            this, R.drawable.ic_apps, "Applications de la TV",
            onClick = { shortcuts.showTvApps() },
            onLongClick = { shortcuts.pickTvApps() }
        )
        val dpadArea = DpadArea(this, dpad, decoderBtn, tvAppsBtn, dp(50))
        val dpadLp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        dpadLp.topMargin = dp(10)
        dpadLp.bottomMargin = dp(10)
        root.addView(dpadArea, dpadLp)

        // --- Retour / Home appareil / Home TV / Options
        root.addView(
            row(
                dp(60),
                captioned(R.drawable.ic_back, "Retour") { actions.sendKey("Return", "Back") },
                captioned(R.drawable.ic_devices_home, "Home appareil") { actions.deviceHome() },
                captioned(R.drawable.ic_home, "Home TV") { actions.tvHome() },
                captioned(R.drawable.ic_more, "Options") { actions.sendKey("Options") }
            )
        )

        // --- Volume / muet / chaînes
        val volumeRow = row(
            dp(104),
            rocker("VOL", "VolumeUp", "VolumeDown"),
            muteCell(),
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
            labeled(R.drawable.ic_dialpad, "Chiffres") { actions.showNumpad() }
        )
        (bottom.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        root.addView(bottom)

        // --- Web et toutes les autres commandes
        val extra = row(
            dp(48),
            labeled(R.drawable.ic_language, "Web") { actions.openWeb() },
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
        super.onDestroy()
        actions.close()
        shortcuts.close()
    }

    // ------------------------------------------------------------------ état affiché

    private fun renderState() {
        val on = Prefs.lastPower(this)
        power.on = on
        val state = when (on) {
            true -> "Allumée"
            false -> "En veille"
            null -> "État inconnu"
        }
        val source = Prefs.lastSource(this)
        subtitle.text = if (on == true && source != null) "$state · $source" else state
    }

    // ------------------------------------------------------------------ morceaux d'interface

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
        Ui.holdRepeat(plus) { repeat -> actions.sendKey(upKey, skipIfBusy = repeat) }

        val minus = icon(R.drawable.ic_remove, Ui.TEXT)
        minus.setPadding(dp(12), dp(12), dp(12), dp(12))
        minus.contentDescription = "$label moins"
        minus.background = Ui.rounded(this, Color.TRANSPARENT, 34)
        Ui.holdRepeat(minus) { repeat -> actions.sendKey(downKey, skipIfBusy = repeat) }

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

    private fun muteCell(): FrameLayout {
        val cell = FrameLayout(this)
        val mute = icon(R.drawable.ic_mute, Ui.TEXT)
        mute.setPadding(dp(18), dp(18), dp(18), dp(18))
        mute.contentDescription = "Muet"
        mute.background = Ui.rounded(this, Ui.SURFACE, 32)
        mute.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.sendKey("Mute")
        }
        val lp = FrameLayout.LayoutParams(dp(64), dp(64))
        lp.gravity = Gravity.CENTER
        cell.addView(mute, lp)
        return cell
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
                actions.sendKey(key)
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
        label.textSize = 15f
        label.setPadding(dp(10), 0, 0, 0)
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
    private val buttonSize: Int
) : ViewGroup(context) {

    init {
        addView(dpad)
        addView(leftBtn)
        addView(rightBtn)
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
    }
}
