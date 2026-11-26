package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Télécommandes « Perso » : mêmes boutons et même style que la classique, mais chaque bloc se déplace,
 * se redimensionne (largeur, hauteur ou les deux), se supprime ou s'ajoute (y compris des touches de la TV).
 * Plusieurs télécommandes perso peuvent coexister. En édition, un aimant aligne les blocs (guides).
 * Positions mémorisées en fractions de la zone, tailles en dp.
 */
class CustomRemoteActivity : Activity() {

    private lateinit var actions: TvActions
    private lateinit var shortcuts: AppShortcuts
    private lateinit var board: FrameLayout
    private lateinit var guides: GuideView
    private lateinit var editBar: LinearLayout
    private lateinit var editBtn: ImageView
    private var muteBtn: ImageView? = null
    private lateinit var snapBtn: TextView
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
    private var snap = true
    private var design = 0

    private val cells = LinkedHashMap<String, Cell>()
    private var remoteId = "1"
    private lateinit var layout: MutableMap<String, CustomLayout.Item>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        design = Ui.useRemoteDesign(this, false)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        actions = TvActions(this)
        actions.onStateChanged = { renderState() }
        shortcuts = AppShortcuts(this, actions)
        remoteId = CustomRemotes.current(this)
        layout = CustomLayout.load(this, remoteId)
        snap = AppPrefs.sp(this).getBoolean("custom_snap", true)

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

        board = FrameLayout(this)
        val cLp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        cLp.topMargin = dp(10)
        root.addView(board, cLp)
        board.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) board.post { placeAll() }
        }
        guides = GuideView(this)

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
        paintMute()
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
        // Toucher ou glisser sur le titre : bascule TV / décodeur (comme la classique).
        texts.contentDescription = "Basculer entre la TV et le décodeur"
        texts.isClickable = true
        texts.setOnClickListener {
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleTarget()
        }
        var downX = 0f
        var downY = 0f
        texts.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (abs(dx) > dp(40) && abs(dx) > abs(dy)) {
                        v.isPressed = false
                        v.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                        toggleTarget()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
        bar.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Muet ; appui long : pause automatique de la TV pendant un appel (vert = activée).
        val mute = barIcon(R.drawable.ic_mute, "Muet") { actions.sendKey("Mute") }
        mute.setOnLongClickListener {
            it.haptic(HapticFeedbackConstants.LONG_PRESS)
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
        muteBtn = mute
        bar.addView(mute, LinearLayout.LayoutParams(dp(38), dp(38)))

        // Passe à la télécommande Edenwood (couleur de l'autre télécommande).
        val edenwood = barIcon(R.drawable.ic_switch_remote, "Télécommande Edenwood") { toEdenwood() }
        edenwood.setColorFilter(RemoteDesign.accent(this, true))
        bar.addView(edenwood, LinearLayout.LayoutParams(dp(38), dp(38)))

        // Changer de télécommande (classique, Zen, autres perso).
        val swap = barIcon(R.drawable.ic_swap, "Changer de télécommande") {
            RemotePicker.show(this, remoteId) { id -> openRemote(id) }
        }
        bar.addView(swap, LinearLayout.LayoutParams(dp(38), dp(38)))

        editBtn = barIcon(R.drawable.ic_touch, "Modifier la disposition") { setEditing(!editing) }
        bar.addView(editBtn, LinearLayout.LayoutParams(dp(38), dp(38)))

        val menu = barIcon(R.drawable.ic_settings, "Menu") { showMenu() }
        // Appui long : changer de télécommande perso.
        menu.setOnLongClickListener {
            it.haptic(HapticFeedbackConstants.LONG_PRESS)
            pickRemote()
            true
        }
        bar.addView(menu, LinearLayout.LayoutParams(dp(38), dp(38)))
        return bar
    }

    /** Icône de la barre du haut, comme sur la classique. */
    private fun barIcon(res: Int, desc: String, onClick: () -> Unit): ImageView {
        val v = icon(res, Ui.DIM)
        v.setPadding(dp(8), dp(8), dp(8), dp(8))
        v.contentDescription = desc
        v.background = Ui.rounded(this, Color.TRANSPARENT, 22)
        v.setOnClickListener {
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        return v
    }

    private fun toggleTarget() {
        actions.selectTarget(if (Prefs.target(this) == Target.DECODER) Target.TV else Target.DECODER)
    }

    /** Muet vert = pause automatique pendant les appels activée. */
    private fun paintMute() {
        val on = CallPause.enabled(this)
        muteBtn?.setColorFilter(if (on) Ui.ACCENT else Ui.DIM)
        muteBtn?.contentDescription = if (on) "Muet (pause auto des appels activée)" else "Muet"
    }

    private fun showMenu() {
        val name = CustomRemotes.name(this, remoteId)
        val items = arrayOf(
            "Changer de télécommande perso", "Nouvelle télécommande perso", "Dupliquer « $name »",
            "Renommer « $name »", "Supprimer « $name »",
            "Réglages", "Télécommande classique", "Télécommande Zen", "Télécommande Edenwood"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickRemote()
                    1 -> askName("Nouvelle télécommande", "Perso ${CustomRemotes.ids(this).size + 1}") { n ->
                        openRemote(CustomRemotes.create(this, n, null))
                    }
                    2 -> askName("Dupliquer", "$name (copie)") { n ->
                        CustomLayout.save(this, remoteId, layout)
                        openRemote(CustomRemotes.create(this, n, remoteId))
                    }
                    3 -> askName("Renommer", name) { n ->
                        CustomRemotes.rename(this, remoteId, n)
                        Ui.toast(this, "Renommée : $n")
                    }
                    4 -> confirmDeleteRemote()
                    5 -> startActivity(Intent(this, MainActivity::class.java))
                    6 -> switchStyle(Prefs.STYLE_CLASSIC, RemoteActivity::class.java)
                    7 -> switchStyle(Prefs.STYLE_ZEN, Remote2Activity::class.java)
                    else -> toEdenwood()
                }
            }
            .show()
    }

    private fun pickRemote() {
        val ids = CustomRemotes.ids(this)
        val labels = ids.map { (if (it == remoteId) "● " else "   ") + CustomRemotes.name(this, it) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Télécommandes perso")
            .setItems(labels.toTypedArray()) { _, which -> openRemote(ids[which]) }
            .show()
    }

    /** Affiche une autre télécommande perso (la disposition en cours est enregistrée). */
    private fun openRemote(id: String) {
        CustomLayout.save(this, remoteId, layout)
        remoteId = id
        CustomRemotes.setCurrent(this, id)
        layout = CustomLayout.load(this, id)
        rebuild()
        Ui.toast(this, CustomRemotes.name(this, id))
    }

    private fun confirmDeleteRemote() {
        if (CustomRemotes.ids(this).size <= 1) {
            Ui.toast(this, "Il faut garder au moins une télécommande perso")
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage("Supprimer « ${CustomRemotes.name(this, remoteId)} » ?")
            .setPositiveButton("Supprimer") { _, _ ->
                CustomRemotes.delete(this, remoteId)
                remoteId = CustomRemotes.current(this)
                layout = CustomLayout.load(this, remoteId)
                rebuild()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun askName(titleText: String, initial: String, onOk: (String) -> Unit) {
        val field = EditText(this)
        field.setText(initial)
        field.setSelectAllOnFocus(true)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.setSingleLine(true)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(field)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(titleText)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = field.text.toString().trim().replace(Regex("[\n;:]"), " ")
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Annuler", null)
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
        fun add(text: String, action: () -> Unit): TextView {
            val b = Ui.pill(this, text, 12f)
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            bar.addView(b, lp)
            return b
        }
        add("+ Bloc") { showAddDialog() }
        add("⇆ Miroir") { mirror() }
        snapBtn = add("Aimant") { toggleSnap() }
        paintSnap()
        add("Défaut") { confirmReset() }
        add("✓ OK") { setEditing(false) }
        return bar
    }

    private fun toggleSnap() {
        snap = !snap
        AppPrefs.sp(this).edit().putBoolean("custom_snap", snap).apply()
        paintSnap()
        Ui.toast(this, if (snap) "Aide à l'alignement : activée" else "Aide à l'alignement : désactivée")
    }

    private fun paintSnap() {
        snapBtn.background = Ui.rounded(this, if (snap) Ui.ACCENT else Ui.SURFACE, 18)
        snapBtn.setTextColor(if (snap) Ui.BG else Ui.TEXT)
    }

    private fun setEditing(on: Boolean) {
        editing = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        editBtn.background = Ui.rounded(this, if (on) Ui.ACCENT_DARK else Color.TRANSPARENT, 22)
        cells.values.forEach { it.showFrame(on) }
        guides.clear()
        if (on) Ui.toast(this, "Glisser = déplacer · poignées = taille · toucher = options")
        else CustomLayout.save(this, remoteId, layout)
    }

    private fun showAddDialog() {
        val hidden = CustomLayout.DEFS.filter { layout[it.id]?.visible != true }
        val labels = hidden.map { it.label } + "Touche de la TV…"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ajouter un bloc")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == hidden.size) {
                    pickTvKey()
                    return@setItems
                }
                val def = hidden[which]
                val item = layout[def.id] ?: CustomLayout.Item(def.id, 0.5f, 0.5f, def.w.toFloat(), def.h.toFloat(), true)
                item.visible = true
                item.cx = 0.5f
                item.cy = 0.5f
                layout[def.id] = item
                rebuild()
            }
            .show()
    }

    /** Ajoute un bouton pour n'importe quelle touche connue de la TV (Netflix, sous-titres, guide…). */
    private fun pickTvKey() {
        Ui.toast(this, "Lecture des touches de la TV…")
        Bg.run {
            val result = BraviaClient.keyNames(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val names = result.getOrNull()
                if (names.isNullOrEmpty()) {
                    Ui.toast(this, "Touches indisponibles : ${result.exceptionOrNull()?.message ?: "TV injoignable"}")
                    return@runOnUiThread
                }
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Touche de la TV")
                    .setItems(names.toTypedArray()) { _, which ->
                        val id = CustomLayout.newKeyId(names[which], layout.keys)
                        val def = CustomLayout.def(id)!!
                        layout[id] = CustomLayout.Item(id, 0.5f, 0.5f, def.w.toFloat(), def.h.toFloat(), true)
                        rebuild()
                    }
                    .show()
            }
        }
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
        val labels = CustomLayout.SCALES.map { "Taille : ${it.first}" } + "Supprimer"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(def.label)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < CustomLayout.SCALES.size) {
                    val f = CustomLayout.SCALES[which].second
                    item.w = def.w * f
                    item.h = def.h * f
                } else if (CustomLayout.keyOf(id) != null) {
                    layout.remove(id) // touche ajoutée : supprimée pour de bon
                } else {
                    item.visible = false // bloc d'origine : réajoutable par « + Bloc »
                }
                rebuild()
            }
            .show()
    }

    private fun rebuild() {
        buildCells()
        cells.values.forEach { it.showFrame(editing) }
        board.post { placeAll() }
        renderState()
    }

    // ------------------------------------------------------------------ blocs

    private fun buildCells() {
        board.removeAllViews()
        cells.clear()
        segTv = null
        segDecoder = null
        chLabel = null
        brightness?.close()
        brightness = null
        for ((id, item) in layout) {
            if (!item.visible) continue
            val content = buildContent(id) ?: continue
            val cell = Cell(this, id)
            cell.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
            val (w, h) = sizeOf(item)
            board.addView(cell, FrameLayout.LayoutParams(w, h))
            cells[id] = cell
        }
        board.addView(guides, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Réduction si l'écran est plus petit que la référence (360 × 620 dp). */
    private fun fit(): Float {
        val cw = board.width
        val ch = board.height
        if (cw == 0 || ch == 0) return 1f
        return min(1f, min(cw / dp(REF_W).toFloat(), ch / dp(REF_H).toFloat()))
    }

    private val density get() = resources.displayMetrics.density

    private fun sizeOf(item: CustomLayout.Item): Pair<Int, Int> {
        val f = fit()
        var w = item.w * density * f
        var h = item.h * density * f
        val cw = board.width
        val ch = board.height
        if (cw > 0) w = min(w, cw.toFloat())
        if (ch > 0) h = min(h, ch.toFloat())
        return w.toInt() to h.toInt()
    }

    private fun placeAll() {
        val cw = board.width
        val ch = board.height
        if (cw == 0 || ch == 0) return
        for ((id, cell) in cells) {
            val item = layout[id] ?: continue
            val (w, h) = sizeOf(item)
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

    /** Fin d'un geste : position (centre, en fractions) et taille (dp) mémorisées. */
    private fun commit(id: String, cell: Cell, w: Int, h: Int) {
        val item = layout[id] ?: return
        val cw = board.width
        val ch = board.height
        if (cw == 0 || ch == 0) return
        item.cx = ((cell.x + w / 2f) / cw).coerceIn(0f, 1f)
        item.cy = ((cell.y + h / 2f) / ch).coerceIn(0f, 1f)
        val f = fit() * density
        item.w = w / f
        item.h = h / f
    }

    // ------------------------------------------------------------------ aide à l'alignement

    /** Lignes d'alignement des autres blocs : bords et centres, plus bords et centre de la zone. */
    private fun lines(except: Cell): Pair<List<Float>, List<Float>> {
        val xs = arrayListOf(0f, board.width / 2f, board.width.toFloat())
        val ys = arrayListOf(0f, board.height / 2f, board.height.toFloat())
        for (c in cells.values) {
            if (c === except) continue
            xs.add(c.x); xs.add(c.x + c.width / 2f); xs.add(c.x + c.width)
            ys.add(c.y); ys.add(c.y + c.height / 2f); ys.add(c.y + c.height)
        }
        return xs to ys
    }

    /** Aligne un segment [start, start+size] (bord gauche, centre ou bord droit) sur la ligne la plus proche. */
    private fun snapMove(start: Float, size: Float, lines: List<Float>): Pair<Float, Float?> {
        var best: Float? = null
        var bestStart = start
        var bestDist = dp(SNAP_DP).toFloat()
        for (off in floatArrayOf(0f, size / 2f, size)) {
            for (l in lines) {
                val d = abs(l - (start + off))
                if (d < bestDist) {
                    bestDist = d
                    best = l
                    bestStart = l - off
                }
            }
        }
        return bestStart to best
    }

    /** Aligne le bord d'arrivée (droit ou bas) d'un redimensionnement ; sinon même taille qu'un autre bloc. */
    private fun snapSize(start: Float, size: Float, lines: List<Float>, sizes: List<Int>): Pair<Float, Float?> {
        val thr = dp(SNAP_DP).toFloat()
        var best: Float? = null
        var bestSize = size
        var bestDist = thr
        for (l in lines) {
            val d = abs(l - (start + size))
            if (l > start && d < bestDist) {
                bestDist = d
                best = l
                bestSize = l - start
            }
        }
        if (best != null) return bestSize to best
        for (s in sizes) if (abs(s - size) < thr) return s.toFloat() to start + s
        return size to null
    }

    /** Guides pointillés dessinés pendant un geste. */
    private inner class GuideView(ctx: Context) : View(ctx) {
        private var xs: List<Float> = emptyList()
        private var ys: List<Float> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Ui.dp(ctx, 1).toFloat().coerceAtLeast(1f)
            paint.pathEffect = DashPathEffect(floatArrayOf(Ui.dp(ctx, 6).toFloat(), Ui.dp(ctx, 4).toFloat()), 0f)
            isClickable = false
        }

        fun show(x: List<Float>, y: List<Float>) {
            xs = x
            ys = y
            bringToFront()
            invalidate()
        }

        fun clear() = show(emptyList(), emptyList())

        override fun onDraw(c: Canvas) {
            paint.color = Ui.ACCENT
            for (x in xs) c.drawLine(x, 0f, x, height.toFloat(), paint)
            for (y in ys) c.drawLine(0f, y, width.toFloat(), y, paint)
        }
    }

    // ------------------------------------------------------------------ bloc modifiable

    /**
     * Conteneur d'un bloc. En édition, il capte les gestes : glisser = déplacer ; poignée droite = largeur ;
     * poignée basse = hauteur ; poignée d'angle = les deux ; toucher = options (taille, supprimer).
     */
    private inner class Cell(ctx: Context, val id: String) : FrameLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val grip = Ui.dp(ctx, 26).toFloat()
        private val knob = Ui.dp(ctx, 7).toFloat()
        private val minSize = Ui.dp(ctx, 32)
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var startW = 0
        private var startH = 0
        private var mode = NONE
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
            invalidate()
        }

        /** Poignées : milieu du bord droit (largeur), milieu du bas (hauteur), coin bas-droit (les deux). */
        override fun dispatchDraw(c: Canvas) {
            super.dispatchDraw(c)
            if (!editing) return
            val w = width.toFloat()
            val h = height.toFloat()
            knobPaint.color = Ui.ACCENT
            c.drawCircle(w - knob, h / 2f, knob * 0.75f, knobPaint)
            c.drawCircle(w / 2f, h - knob, knob * 0.75f, knobPaint)
            c.drawCircle(w - knob, h - knob, knob, knobPaint)
        }

        private fun hit(x: Float, y: Float): Int {
            val right = x > width - grip
            val bottom = y > height - grip
            return when {
                right && bottom -> RESIZE_BOTH
                right && abs(y - height / 2f) < grip -> RESIZE_W
                bottom && abs(x - width / 2f) < grip -> RESIZE_H
                else -> MOVE
            }
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
                    startW = width
                    startH = height
                    mode = hit(ev.x, ev.y)
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
                        if (mode == MOVE) move(box, dx, dy) else resize(box, dx, dy)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    guides.clear()
                    if (moved) commit(id, this, layoutParams.width, layoutParams.height)
                    else if (ev.actionMasked == MotionEvent.ACTION_UP) showItemDialog(id)
                    mode = NONE
                }
            }
            return true
        }

        private fun move(box: ViewGroup, dx: Float, dy: Float) {
            val maxX = (box.width - width).toFloat().coerceAtLeast(0f)
            val maxY = (box.height - height).toFloat().coerceAtLeast(0f)
            var nx = (startX + dx).coerceIn(0f, maxX)
            var ny = (startY + dy).coerceIn(0f, maxY)
            var gx: Float? = null
            var gy: Float? = null
            if (snap) {
                val (xs, ys) = lines(this)
                snapMove(nx, width.toFloat(), xs).let { nx = it.first.coerceIn(0f, maxX); gx = it.second }
                snapMove(ny, height.toFloat(), ys).let { ny = it.first.coerceIn(0f, maxY); gy = it.second }
            }
            x = nx
            y = ny
            guides.show(listOfNotNull(gx), listOfNotNull(gy))
        }

        private fun resize(box: ViewGroup, dx: Float, dy: Float) {
            var nw = if (mode == RESIZE_H) startW.toFloat() else startW + dx
            var nh = if (mode == RESIZE_W) startH.toFloat() else startH + dy
            nw = nw.coerceIn(minSize.toFloat(), max(minSize.toFloat(), box.width - x))
            nh = nh.coerceIn(minSize.toFloat(), max(minSize.toFloat(), box.height - y))
            var gx: Float? = null
            var gy: Float? = null
            if (snap) {
                val (xs, ys) = lines(this)
                val others = cells.values.filter { it !== this }
                if (mode != RESIZE_H) snapSize(x, nw, xs, others.map { it.width }).let { nw = it.first; gx = it.second }
                if (mode != RESIZE_W) snapSize(y, nh, ys, others.map { it.height }).let { nh = it.first; gy = it.second }
            }
            val lp = layoutParams
            lp.width = nw.toInt()
            lp.height = nh.toInt()
            layoutParams = lp
            guides.show(listOfNotNull(gx), listOfNotNull(gy))
        }
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
        else -> CustomLayout.keyOf(id)?.let { key ->
            labeled(R.drawable.ic_tune, key) { actions.key(key) }
        }
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

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** Zone de référence (dp) des tailles par défaut. */
        const val REF_W = 360
        const val REF_H = 620
        /** Distance (dp) en dessous de laquelle l'aimant aligne. */
        const val SNAP_DP = 8
        const val NONE = 0
        const val MOVE = 1
        const val RESIZE_W = 2
        const val RESIZE_H = 3
        const val RESIZE_BOTH = 4
    }
}

/** Choix de la télécommande : classique, Zen ou l'une des perso. */
object RemotePicker {

    /** Ouvre directement la télécommande perso (la dernière utilisée). */
    fun openCustom(act: Activity) {
        Prefs.saveRemoteStyle(act, Prefs.STYLE_CUSTOM)
        act.startActivity(Intent(act, CustomRemoteActivity::class.java))
        act.finish()
    }

    /**
     * Liste : Classique, Zen, puis chaque télécommande perso. [currentPerso] et [onPerso] : appelé depuis
     * une télécommande perso, pour changer de disposition sans relancer l'écran.
     */
    fun show(act: Activity, currentPerso: String? = null, onPerso: ((String) -> Unit)? = null) {
        val ids = CustomRemotes.ids(act)
        val labels = listOf("Classique", "Zen (gestes)") +
            ids.map { (if (it == currentPerso) "● " else "") + "Perso : " + CustomRemotes.name(act, it) }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Télécommande")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> go(act, Prefs.STYLE_CLASSIC, RemoteActivity::class.java)
                    1 -> go(act, Prefs.STYLE_ZEN, Remote2Activity::class.java)
                    else -> {
                        val id = ids[which - 2]
                        if (onPerso != null) {
                            onPerso(id)
                        } else {
                            CustomRemotes.setCurrent(act, id)
                            openCustom(act)
                        }
                    }
                }
            }
            .show()
    }

    private fun go(act: Activity, style: String, cls: Class<*>) {
        Prefs.saveRemoteStyle(act, style)
        act.startActivity(Intent(act, cls))
        act.finish()
    }
}

/** Liste des télécommandes perso (identifiant, nom) et télécommande affichée. */
object CustomRemotes {
    private const val IDS = "custom_remotes"
    private const val CURRENT = "custom_remote_current"
    private fun sp(ctx: Context) = AppPrefs.sp(ctx)

    /** Identifiants ; crée la première (en reprenant l'ancienne disposition unique) si besoin. */
    fun ids(ctx: Context): List<String> {
        val raw = sp(ctx).getString(IDS, null)
        if (raw.isNullOrBlank()) {
            val legacy = sp(ctx).getString("custom_remote_layout", null)
            val e = sp(ctx).edit().putString(IDS, "1").putString(nameKey("1"), "Perso").putString(CURRENT, "1")
            if (legacy != null) e.putString(CustomLayout.key("1"), legacy).remove("custom_remote_layout")
            e.apply()
            return listOf("1")
        }
        return raw.split(',').filter { it.isNotBlank() }
    }

    private fun nameKey(id: String) = "custom_remote_name_$id"

    fun name(ctx: Context, id: String): String = sp(ctx).getString(nameKey(id), null) ?: "Perso"

    fun rename(ctx: Context, id: String, name: String) {
        sp(ctx).edit().putString(nameKey(id), name).apply()
    }

    fun current(ctx: Context): String {
        val all = ids(ctx)
        val c = sp(ctx).getString(CURRENT, null)
        return if (c != null && c in all) c else all.first()
    }

    fun setCurrent(ctx: Context, id: String) {
        sp(ctx).edit().putString(CURRENT, id).apply()
    }

    /** Nouvelle télécommande (copie de [copyFrom], sinon disposition par défaut). Renvoie son identifiant. */
    fun create(ctx: Context, name: String, copyFrom: String?): String {
        val all = ids(ctx)
        val id = ((all.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        val e = sp(ctx).edit()
            .putString(IDS, (all + id).joinToString(","))
            .putString(nameKey(id), name)
        if (copyFrom != null) e.putString(CustomLayout.key(id), sp(ctx).getString(CustomLayout.key(copyFrom), "") ?: "")
        e.apply()
        return id
    }

    fun delete(ctx: Context, id: String) {
        val rest = ids(ctx) - id
        if (rest.isEmpty()) return
        sp(ctx).edit()
            .putString(IDS, rest.joinToString(","))
            .remove(nameKey(id))
            .remove(CustomLayout.key(id))
            .putString(CURRENT, rest.first())
            .apply()
    }
}

/** Disposition d'une télécommande perso (mémorisée dans les préférences). */
object CustomLayout {

    /** Bloc disponible : taille de base en dp et position par défaut (centre, en fractions). */
    class Def(val id: String, val label: String, val w: Int, val h: Int, val cx: Float, val cy: Float)

    /** w, h : taille en dp (avant réduction pour petit écran). */
    class Item(val id: String, var cx: Float, var cy: Float, var w: Float, var h: Float, var visible: Boolean)

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

    /** Touche de la TV ajoutée : identifiant « k.<Touche>.<n> ». */
    fun keyOf(id: String): String? =
        if (id.startsWith("k.")) id.removePrefix("k.").substringBeforeLast('.').ifEmpty { null } else null

    fun newKeyId(key: String, used: Collection<String>): String {
        var n = 1
        while ("k.$key.$n" in used) n++
        return "k.$key.$n"
    }

    fun def(id: String): Def? =
        DEFS.firstOrNull { it.id == id } ?: keyOf(id)?.let { Def(id, "Touche $it", 118, 48, 0.5f, 0.5f) }

    fun key(remote: String) = "custom_remote_layout_$remote"

    fun defaults(): MutableMap<String, Item> =
        DEFS.associateTo(LinkedHashMap<String, Item>()) { it.id to Item(it.id, it.cx, it.cy, it.w.toFloat(), it.h.toFloat(), true) }

    /**
     * Format : id:cx:cy:w:h:visible;… (ancien format id:cx:cy:échelle:visible accepté).
     * Blocs inconnus ignorés ; blocs d'origine absents remis à leur place par défaut.
     */
    fun load(ctx: Context, remote: String): MutableMap<String, Item> {
        val map = defaults()
        val raw = AppPrefs.sp(ctx).getString(key(remote), "") ?: ""
        for (part in raw.split(';')) {
            val f = part.split(':')
            val d = f.firstOrNull()?.let { def(it) } ?: continue
            val cx = f.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: continue
            val cy = f.getOrNull(2)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: continue
            val item = when (f.size) {
                5 -> {
                    val sc = f[3].toFloatOrNull()?.coerceIn(0.5f, 2f) ?: continue
                    Item(d.id, cx, cy, d.w * sc, d.h * sc, f[4] == "1")
                }
                6 -> {
                    val w = f[3].toFloatOrNull()?.coerceIn(24f, 800f) ?: continue
                    val h = f[4].toFloatOrNull()?.coerceIn(24f, 800f) ?: continue
                    Item(d.id, cx, cy, w, h, f[5] == "1")
                }
                else -> continue
            }
            map[d.id] = item
        }
        return map
    }

    fun save(ctx: Context, remote: String, items: Map<String, Item>) {
        val raw = items.values.joinToString(";") {
            "${it.id}:${it.cx}:${it.cy}:${it.w}:${it.h}:${if (it.visible) "1" else "0"}"
        }
        AppPrefs.sp(ctx).edit().putString(key(remote), raw).apply()
    }
}
