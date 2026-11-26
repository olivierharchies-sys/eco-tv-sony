package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * Télécommande « Perso » : mêmes boutons et même style que la classique, mais chaque bloc se déplace.
 * « Modifier » : glisser un bloc pour le déplacer, toucher un bloc pour sa taille ou le masquer.
 * Positions mémorisées en fractions de la zone (indépendantes de la résolution).
 */
class CustomRemoteActivity : Activity() {

    private lateinit var actions: TvActions
    private lateinit var shortcuts: AppShortcuts
    private lateinit var canvas: FrameLayout
    private lateinit var editBar: LinearLayout
    private lateinit var editBtn: ImageView
    private lateinit var power: PowerButton
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private var segTv: TextView? = null
    private var segDecoder: TextView? = null
    private var chLabel: TextView? = null
    private var brightness: BrightnessControl? = null
    private val quickMenu by lazy { QuickMenu(this) }
    private val pictureMenu by lazy { PictureMenu(this) }
    private var editing = false
    private var design = 0

    private val cells = LinkedHashMap<String, Cell>()
    private lateinit var layout: MutableMap<String, CustomLayout.Item>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        design = Ui.useRemoteDesign(this, false)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        actions = TvActions(this)
        actions.onStateChanged = { renderState() }
        shortcuts = AppShortcuts(this, actions)
        layout = CustomLayout.load(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(dp(16), dp(10), dp(16), dp(14))
        root.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, dp(56)))

        editBar = buildEditBar()
        editBar.visibility = View.GONE
        val ebLp = LinearLayout.LayoutParams(MATCH, dp(40))
        ebLp.topMargin = dp(6)
        root.addView(editBar, ebLp)

        canvas = FrameLayout(this)
        val cLp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        cLp.topMargin = dp(10)
        root.addView(canvas, cLp)
        canvas.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) canvas.post { placeAll() }
        }

        buildCells()
        setContentView(root)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        if (RemoteDesign.current(this) != design) {
            recreate()
            return
        }
        Ui.useRemoteDesign(this, false)
        if (RoomSwitch.pick(this) == Prefs.DEVICE_EDENWOOD) {
            toEdenwood()
            return
        }
        RoomSwitch.follow(this) { if (it == Prefs.DEVICE_EDENWOOD) toEdenwood() }
        renderState()
        actions.refreshPower()
        actions.startSourceWatch()
        if (CallPause.enabled(this)) CallPause.apply(this)
        if (KeepAwake.enabled(this)) KeepAwake.apply(this)
    }

    override fun onPause() {
        RoomSwitch.stop(this)
        actions.stopSourceWatch()
        if (editing) setEditing(false)
        Ui.useBlueTheme(false)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.close()
        shortcuts.close()
        brightness?.close()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!Voice.handle(this, actions, requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun toEdenwood() {
        Prefs.saveRemoteDevice(this, Prefs.DEVICE_EDENWOOD)
        startActivity(Intent(this, EdenwoodRemoteActivity::class.java))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editing) setEditing(false) else @Suppress("DEPRECATION") super.onBackPressed()
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
        val target = Prefs.target(this)
        segTv?.let { paintSegment(it, target == Target.TV) }
        segDecoder?.let { paintSegment(it, target == Target.DECODER) }
        val state = when (on) {
            true -> "Allumée"
            false -> "En veille"
            null -> "État inconnu"
        }
        val source = Prefs.lastSource(this)
        if (target == Target.DECODER) {
            title.text = "Décodeur"
            title.setTextColor(Ui.ACCENT)
            val link = if (Decoder.wifi(this)) "Wi-Fi" else "HDMI-CEC"
            subtitle.text = "$link · TV ${state.lowercase()}"
        } else {
            title.text = "Sony Bravia"
            title.setTextColor(Ui.TEXT)
            subtitle.text = if (on == true && source != null) "$state · $source" else state
        }
    }

    // ------------------------------------------------------------------ barre du haut (fixe)

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        power = PowerButton(this)
        power.contentDescription = "Marche / Arrêt"
        power.setOnClickListener {
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.togglePower()
        }
        bar.addView(power, LinearLayout.LayoutParams(dp(56), dp(56)))

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(10), 0, dp(4), 0)
        title = TextView(this)
        title.textSize = 17f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.maxLines = 1
        texts.addView(title, LinearLayout.LayoutParams(MATCH, dp(24)))
        subtitle = TextView(this)
        subtitle.setTextColor(Ui.DIM)
        subtitle.textSize = 13f
        subtitle.maxLines = 1
        texts.addView(subtitle, LinearLayout.LayoutParams(MATCH, dp(18)))
        texts.isClickable = true
        texts.setOnClickListener {
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.selectTarget(if (Prefs.target(this) == Target.DECODER) Target.TV else Target.DECODER)
        }
        bar.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        val mute = barIcon(R.drawable.ic_mute, "Muet") { actions.sendKey("Mute") }
        bar.addView(mute, LinearLayout.LayoutParams(dp(40), dp(40)))

        editBtn = barIcon(R.drawable.ic_touch, "Modifier la disposition") { setEditing(!editing) }
        bar.addView(editBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val menu = barIcon(R.drawable.ic_settings, "Menu") { showMenu() }
        bar.addView(menu, LinearLayout.LayoutParams(dp(40), dp(40)))
        return bar
    }

    /** Icône de la barre du haut, comme sur la classique. */
    private fun barIcon(res: Int, desc: String, onClick: () -> Unit): ImageView {
        val v = icon(res, Ui.DIM)
        v.setPadding(dp(9), dp(9), dp(9), dp(9))
        v.contentDescription = desc
        v.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        v.setOnClickListener {
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        return v
    }

    private fun showMenu() {
        val items = arrayOf("Réglages", "Télécommande classique", "Télécommande Zen", "Télécommande Edenwood")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, MainActivity::class.java))
                    1 -> switchStyle(Prefs.STYLE_CLASSIC, RemoteActivity::class.java)
                    2 -> switchStyle(Prefs.STYLE_ZEN, Remote2Activity::class.java)
                    else -> toEdenwood()
                }
            }
            .show()
    }

    private fun switchStyle(style: String, cls: Class<*>) {
        Prefs.saveRemoteStyle(this, style)
        startActivity(Intent(this, cls))
        finish()
    }

    // ------------------------------------------------------------------ mode édition

    private fun buildEditBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        fun add(text: String, action: () -> Unit) {
            val b = Ui.pill(this, text, 12f)
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            bar.addView(b, lp)
        }
        add("+ Ajouter") { showAddDialog() }
        add("⇆ Miroir") { mirror() }
        add("Défaut") { confirmReset() }
        add("✓ Terminé") { setEditing(false) }
        return bar
    }

    private fun setEditing(on: Boolean) {
        editing = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        editBtn.background = Ui.rounded(this, if (on) Ui.ACCENT_DARK else Color.TRANSPARENT, 22)
        cells.values.forEach { it.showFrame(on) }
        if (on) Ui.toast(this, "Glisser un bloc pour le déplacer, le toucher pour ses options")
        else CustomLayout.save(this, layout)
    }

    private fun showAddDialog() {
        val hidden = CustomLayout.DEFS.filter { layout[it.id]?.visible != true }
        if (hidden.isEmpty()) {
            Ui.toast(this, "Tous les blocs sont déjà affichés")
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ajouter un bloc")
            .setItems(hidden.map { it.label }.toTypedArray()) { _, which ->
                val def = hidden[which]
                val item = layout[def.id] ?: CustomLayout.Item(def.id, 0.5f, 0.5f, 1f, true)
                item.visible = true
                item.cx = 0.5f
                item.cy = 0.5f
                layout[def.id] = item
                rebuild()
            }
            .show()
    }

    /** Inverse gauche / droite (droitier ⇄ gaucher). */
    private fun mirror() {
        layout.values.forEach { it.cx = 1f - it.cx }
        placeAll()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage("Revenir à la disposition par défaut ?")
            .setPositiveButton("Oui") { _, _ ->
                layout = CustomLayout.defaults()
                rebuild()
            }
            .setNegativeButton("Non", null)
            .show()
    }

    private fun showItemDialog(id: String) {
        val item = layout[id] ?: return
        val def = CustomLayout.def(id) ?: return
        val labels = CustomLayout.SCALES.map { "Taille : ${it.first}" } + "Masquer"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(def.label)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < CustomLayout.SCALES.size) item.scale = CustomLayout.SCALES[which].second
                else item.visible = false
                rebuild()
            }
            .show()
    }

    private fun rebuild() {
        buildCells()
        cells.values.forEach { it.showFrame(editing) }
        canvas.post { placeAll() }
        renderState()
    }

    // ------------------------------------------------------------------ blocs

    private fun buildCells() {
        canvas.removeAllViews()
        cells.clear()
        segTv = null
        segDecoder = null
        chLabel = null
        brightness?.close()
        brightness = null
        for (def in CustomLayout.DEFS) {
            val item = layout[def.id] ?: continue
            if (!item.visible) continue
            val content = buildContent(def.id) ?: continue
            val cell = Cell(this, def.id)
            cell.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
            val (w, h) = sizeOf(def, item)
            canvas.addView(cell, FrameLayout.LayoutParams(w, h))
            cells[def.id] = cell
        }
    }

    /** Taille en px : base × taille choisie, réduite si l'écran est plus petit que la référence (360 × 620 dp). */
    private fun sizeOf(def: CustomLayout.Def, item: CustomLayout.Item): Pair<Int, Int> {
        val cw = canvas.width
        val ch = canvas.height
        val fit = if (cw > 0 && ch > 0) min(1f, min(cw / dp(REF_W).toFloat(), ch / dp(REF_H).toFloat())) else 1f
        var w = dp(def.w) * item.scale * fit
        var h = dp(def.h) * item.scale * fit
        if (cw > 0 && ch > 0) {
            val f = min(1f, min(cw / w, ch / h))
            w *= f
            h *= f
        }
        return w.toInt() to h.toInt()
    }

    private fun placeAll() {
        val cw = canvas.width
        val ch = canvas.height
        if (cw == 0 || ch == 0) return
        for ((id, cell) in cells) {
            val item = layout[id] ?: continue
            val def = CustomLayout.def(id) ?: continue
            val (w, h) = sizeOf(def, item)
            val lp = cell.layoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                cell.layoutParams = lp
            }
            cell.x = (item.cx * cw - w / 2f).coerceIn(0f, (cw - w).toFloat().coerceAtLeast(0f))
            cell.y = (item.cy * ch - h / 2f).coerceIn(0f, (ch - h).toFloat().coerceAtLeast(0f))
        }
    }

    /** Fin d'un glisser : position mémorisée (centre, en fractions). */
    private fun dropped(id: String, cell: Cell) {
        val item = layout[id] ?: return
        val cw = canvas.width
        val ch = canvas.height
        if (cw == 0 || ch == 0) return
        item.cx = ((cell.x + cell.width / 2f) / cw).coerceIn(0f, 1f)
        item.cy = ((cell.y + cell.height / 2f) / ch).coerceIn(0f, 1f)
    }

    private fun buildContent(id: String): View? = when (id) {
        "target" -> buildTargetSwitch()
        "source" -> labeled(R.drawable.ic_input, "Source") { actions.showSourcePicker() }
        "dpad" -> DpadView(
            this,
            onKey = { key, repeat -> actions.key(key, repeat = repeat) },
            onHold = { key, start -> actions.hold(key, start) }
        )
        "eco" -> RoundButton.create(
            this, R.drawable.ic_eco, "Économie d'énergie : Haut / Bas",
            onClick = { actions.toggleEco() }, onLongClick = { shortcuts.showDecoderApps() }
        )
        "tvapps" -> RoundButton.create(
            this, R.drawable.ic_apps, "Applications de l'appareil visé",
            onClick = { shortcuts.showAppsForTarget() }, onLongClick = { shortcuts.manageAppsForTarget() }
        )
        "quick" -> RoundButton.create(
            this, R.drawable.ic_timer, "Actions rapides : écran éteint, minuteur",
            onClick = { quickMenu.show() }, onLongClick = { quickMenu.show() }
        )
        "picture" -> RoundButton.create(
            this, R.drawable.ic_tune, "Image : profils mémorisés (appui long : menu)",
            onClick = { pictureMenu.onTap() }, onLongClick = { pictureMenu.onLongPress() }
        )
        "back" -> captioned(R.drawable.ic_back, "Retour") { actions.key("Return", "Back") }
        "home" -> captioned(R.drawable.ic_home, "Home") {
            if (Prefs.target(this) == Target.DECODER) actions.deviceHome() else actions.tvHome()
        }
        "options" -> captioned(R.drawable.ic_more, "Options") { actions.key("Options") }
        "vol" -> rocker("VOL", { r -> actions.key("VolumeUp", repeat = r) }, { r -> actions.key("VolumeDown", repeat = r) })
        "lum" -> brightnessRocker()
        "ch" -> rocker("CH", { r -> actions.key("ChannelUp", repeat = r) }, { r -> actions.key("ChannelDown", repeat = r) })
            .also { chLabel = it.getChildAt(1) as TextView }
        "media" -> mediaBar()
        "keyboard" -> labeled(R.drawable.ic_keyboard, "Clavier") { actions.showKeyboard() }
        "numpad" -> labeled(R.drawable.ic_dialpad, "Chiffres") { actions.showNumpad() }
        "streoli" -> labeled(R.drawable.ic_play, "StreOli", { Voice.start(this, Voice.STREOLI) }) { actions.openStreOli() }
        "web" -> labeled(R.drawable.ic_language, "Web") { actions.openWeb() }
        "paste" -> labeled(R.drawable.ic_search, "Coller", { Voice.start(this, Voice.TV) }) { actions.pasteClipboard() }
        "more" -> labeled(R.drawable.ic_apps, "Plus") { actions.openMore() }
        else -> null
    }

    // ------------------------------------------------------------------ fabriques (style de la classique)

    private fun buildTargetSwitch(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.background = Ui.rounded(this, Ui.SURFACE, 26)
        box.setPadding(dp(4), dp(4), dp(4), dp(4))
        val tv = segment("TV", Target.TV)
        val dec = segment("Décodeur", Target.DECODER)
        segTv = tv
        segDecoder = dec
        box.addView(tv, LinearLayout.LayoutParams(0, MATCH, 1f))
        box.addView(dec, LinearLayout.LayoutParams(0, MATCH, 1f))
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
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            actions.selectTarget(target)
        }
        return t
    }

    private fun paintSegment(view: TextView, selected: Boolean) {
        view.background = if (selected) Ui.rounded(this, Ui.ACCENT, 22) else null
        view.setTextColor(if (selected) Ui.BG else Ui.TEXT)
    }

    private fun icon(res: Int, tint: Int): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(tint)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        return v
    }

    /** Double bouton + / − avec un libellé au milieu. Reste appuyé = répétition. */
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
        if (label == "VOL") {
            text.setOnLongClickListener {
                it.haptic(HapticFeedbackConstants.LONG_PRESS)
                actions.showVolume()
                true
            }
        }
        box.addView(plus, LinearLayout.LayoutParams(MATCH, 0, 1f))
        box.addView(text, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(minus, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return box
    }

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
                it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                actions.key(key)
            }
            bar.addView(b, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        return bar
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
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        box.addView(icon(res, Ui.TEXT), LinearLayout.LayoutParams(dp(24), dp(24)))
        val label = TextView(this)
        label.text = text
        label.setTextColor(Ui.DIM)
        label.textSize = 10f
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        box.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
        return box
    }

    /** Bouton « pilule » : icône + libellé. */
    private fun labeled(res: Int, text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER
        box.background = Ui.rounded(this, Ui.SURFACE, 26)
        box.isClickable = true
        box.setOnClickListener {
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        if (onLong != null) {
            box.setOnLongClickListener {
                it.haptic(HapticFeedbackConstants.LONG_PRESS)
                onLong()
                true
            }
        }
        box.addView(icon(res, Ui.TEXT), LinearLayout.LayoutParams(dp(22), dp(22)))
        val label = TextView(this)
        label.text = text
        label.setTextColor(Ui.TEXT)
        label.textSize = 14f
        label.maxLines = 1
        label.setPadding(dp(8), 0, 0, 0)
        box.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
        return box
    }

    private fun dp(value: Int): Int = Ui.dp(this, value)

    // ------------------------------------------------------------------ bloc déplaçable

    /** Conteneur d'un bloc : en mode édition, il capte les gestes (glisser = déplacer, toucher = options). */
    private inner class Cell(ctx: Context, val id: String) : FrameLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var moved = false

        init {
            val m = Ui.dp(ctx, 3)
            setPadding(m, m, m, m)
        }

        fun showFrame(on: Boolean) {
            foreground = if (on) {
                val g = GradientDrawable()
                g.setColor(Color.parseColor("#22FFFFFF"))
                g.cornerRadius = Ui.dp(context, 14).toFloat()
                g.setStroke(Ui.dp(context, 2), Ui.ACCENT, Ui.dp(context, 6).toFloat(), Ui.dp(context, 4).toFloat())
                g
            } else null
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = editing

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!editing) return super.onTouchEvent(ev)
            val box = parent as? ViewGroup ?: return true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = x
                    startY = y
                    moved = false
                    bringToFront()
                    box.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    if (moved) {
                        x = (startX + dx).coerceIn(0f, (box.width - width).toFloat().coerceAtLeast(0f))
                        y = (startY + dy).coerceIn(0f, (box.height - height).toFloat().coerceAtLeast(0f))
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) dropped(id, this) else showItemDialog(id)
                MotionEvent.ACTION_CANCEL -> if (moved) dropped(id, this)
            }
            return true
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** Zone de référence (dp) des tailles par défaut. */
        const val REF_W = 360
        const val REF_H = 620
    }
}

/** Disposition de la télécommande « Perso » (mémorisée dans les préférences). */
object CustomLayout {

    /** Bloc disponible : taille de base en dp et position par défaut (centre, en fractions). */
    class Def(val id: String, val label: String, val w: Int, val h: Int, val cx: Float, val cy: Float, val shown: Boolean = true)

    class Item(val id: String, var cx: Float, var cy: Float, var scale: Float, var visible: Boolean)

    val SCALES = listOf("Petit" to 0.75f, "Normal" to 1f, "Grand" to 1.25f, "Très grand" to 1.5f)

    /** Par défaut : même disposition que la télécommande classique. */
    val DEFS = listOf(
        Def("target", "Sélecteur TV / Décodeur", 236, 52, 0.33f, 0.042f),
        Def("source", "Source", 118, 52, 0.835f, 0.042f),
        Def("eco", "Économie d'énergie", 50, 50, 0.08f, 0.14f),
        Def("tvapps", "Applications", 50, 50, 0.92f, 0.14f),
        Def("dpad", "Croix directionnelle", 210, 210, 0.5f, 0.265f),
        Def("quick", "Actions rapides", 50, 50, 0.08f, 0.40f),
        Def("picture", "Image", 50, 50, 0.92f, 0.40f),
        Def("back", "Retour", 118, 60, 0.167f, 0.485f),
        Def("home", "Home", 118, 60, 0.5f, 0.485f),
        Def("options", "Options", 118, 60, 0.833f, 0.485f),
        Def("vol", "Volume", 118, 104, 0.167f, 0.625f),
        Def("lum", "Luminosité", 118, 104, 0.5f, 0.625f),
        Def("ch", "Chaînes", 118, 104, 0.833f, 0.625f),
        Def("media", "Lecture", 352, 48, 0.5f, 0.757f),
        Def("keyboard", "Clavier", 118, 48, 0.167f, 0.845f),
        Def("numpad", "Chiffres", 118, 48, 0.5f, 0.845f),
        Def("streoli", "StreOli", 118, 48, 0.833f, 0.845f),
        Def("web", "Web", 118, 48, 0.167f, 0.93f),
        Def("paste", "Coller", 118, 48, 0.5f, 0.93f),
        Def("more", "Plus", 118, 48, 0.833f, 0.93f)
    )

    fun def(id: String): Def? = DEFS.firstOrNull { it.id == id }

    private const val KEY = "custom_remote_layout"

    fun defaults(): MutableMap<String, Item> =
        DEFS.associateTo(LinkedHashMap<String, Item>()) { it.id to Item(it.id, it.cx, it.cy, 1f, it.shown) }

    /** Format : id:cx:cy:scale:visible;… — blocs inconnus ignorés, nouveaux blocs à leur place par défaut. */
    fun load(ctx: Context): MutableMap<String, Item> {
        val map = defaults()
        val raw = AppPrefs.sp(ctx).getString(KEY, "") ?: ""
        for (part in raw.split(';')) {
            val f = part.split(':')
            if (f.size != 5 || def(f[0]) == null) continue
            val cx = f[1].toFloatOrNull() ?: continue
            val cy = f[2].toFloatOrNull() ?: continue
            val sc = f[3].toFloatOrNull() ?: continue
            map[f[0]] = Item(f[0], cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f), sc.coerceIn(0.5f, 2f), f[4] == "1")
        }
        return map
    }

    fun save(ctx: Context, items: Map<String, Item>) {
        val raw = items.values.joinToString(";") {
            "${it.id}:${it.cx}:${it.cy}:${it.scale}:${if (it.visible) "1" else "0"}"
        }
        AppPrefs.sp(ctx).edit().putString(KEY, raw).apply()
    }
}
