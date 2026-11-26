package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
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
    private lateinit var editBar: View
    private lateinit var editBtn: ImageView
    private var muteBtn: ImageView? = null
    private lateinit var snapBtn: TextView
    private lateinit var gridBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var power: PowerButton
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private var segTv: TextView? = null
    private var segDecoder: TextView? = null
    private var chLabel: TextView? = null
    private var dpad: DpadView? = null
    private var brightness: BrightnessControl? = null
    private val quickMenu by lazy { QuickMenu(this) }
    private val pictureMenu by lazy { PictureMenu(this) }
    private var editing = false
    private var snap = true
    private var grid = false
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
        actions.onStateChanged = {
            renderState()
            followLink()
        }
        shortcuts = AppShortcuts(this, actions)
        remoteId = CustomRemotes.current(this)
        layout = CustomLayout.load(this, remoteId, land)
        snap = AppPrefs.sp(this).getBoolean("custom_snap", true)
        grid = AppPrefs.sp(this).getBoolean("custom_grid", false)

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
        dpad?.badge = if (target == Target.DECODER) "DÉCODEUR" else "TV"
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

        // Verrou : appui long pour entrer en édition (évite les modifications accidentelles).
        editBtn = barIcon(R.drawable.ic_touch, "Modifier la disposition (appui long)") {
            if (editing) setEditing(false) else Ui.toast(this, "Appui long pour modifier la disposition")
        }
        editBtn.setOnLongClickListener {
            it.haptic(HapticFeedbackConstants.LONG_PRESS)
            setEditing(!editing)
            true
        }
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
            "Renommer « $name »", "Supprimer « $name »", "Exporter « $name »", "Importer une télécommande",
            "Lier aux appareils (TV / décodeur)…",
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
                        saveLayout()
                        openRemote(CustomRemotes.create(this, n, remoteId))
                    }
                    3 -> askName("Renommer", name) { n ->
                        CustomRemotes.rename(this, remoteId, n)
                        Ui.toast(this, "Renommée : $n")
                    }
                    4 -> confirmDeleteRemote()
                    5 -> exportRemote()
                    6 -> importRemote()
                    7 -> linkTargets()
                    8 -> startActivity(Intent(this, MainActivity::class.java))
                    9 -> switchStyle(Prefs.STYLE_CLASSIC, RemoteActivity::class.java)
                    10 -> switchStyle(Prefs.STYLE_ZEN, Remote2Activity::class.java)
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

    private val land: Boolean
        get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun saveLayout() = CustomLayout.save(this, remoteId, land, layout)

    /** Affiche une autre télécommande perso (la disposition en cours est enregistrée). */
    private fun openRemote(id: String) {
        saveLayout()
        remoteId = id
        CustomRemotes.setCurrent(this, id)
        layout = CustomLayout.load(this, id, land)
        undo.clear()
        redo.clear()
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
                layout = CustomLayout.load(this, remoteId, land)
                undo.clear()
                redo.clear()
                rebuild()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ------------------------------------------------------------------ export / import

    /** Texte à coller ailleurs (sauvegarde, autre téléphone) : copié et partagé. */
    private fun exportRemote() {
        saveLayout()
        val text = CustomLayout.export(CustomRemotes.name(this, remoteId), layout)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Télécommande perso", text))
        Ui.toast(this, "Disposition copiée")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, "Exporter la télécommande")) }
    }

    private fun importRemote() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val field = EditText(this)
        field.hint = "Coller le texte exporté"
        if (clip.startsWith(CustomLayout.EXPORT_TAG)) field.setText(clip)
        field.maxLines = 4
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(field)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Importer une télécommande")
            .setView(box)
            .setPositiveButton("Importer") { _, _ ->
                val parsed = CustomLayout.parseExport(field.text.toString().trim())
                if (parsed == null) {
                    Ui.toast(this, "Texte non reconnu")
                } else {
                    saveLayout()
                    openRemote(CustomRemotes.createFrom(this, parsed.first, parsed.second))
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ------------------------------------------------------------------ télécommande liée à l'appareil visé

    /** Choix : télécommande perso à afficher quand la cible passe sur la TV, puis sur le décodeur. */
    private fun linkTargets() {
        val ids = CustomRemotes.ids(this)
        val labels = (listOf("Aucune (ne pas changer)") + ids.map { CustomRemotes.name(this, it) }).toTypedArray()
        fun ask(target: Target, title: String, next: () -> Unit) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(labels) { _, which ->
                    CustomRemotes.setLink(this, target, if (which == 0) null else ids[which - 1])
                    next()
                }
                .show()
        }
        ask(Target.TV, "Cible TV : télécommande") {
            ask(Target.DECODER, "Cible décodeur : télécommande") {
                Ui.toast(this, "Liaison enregistrée")
                followLink()
            }
        }
    }

    /** Si une télécommande est liée à l'appareil visé, on l'affiche (jamais pendant l'édition). */
    private fun followLink() {
        if (editing) return
        val link = CustomRemotes.link(this, Prefs.target(this)) ?: return
        if (link != remoteId && link in CustomRemotes.ids(this)) openRemote(link)
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

    private fun buildEditBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        fun add(text: String, action: () -> Unit): TextView {
            val b = Ui.pill(this, text, 12f)
            b.setPadding(dp(12), 0, dp(12), 0)
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(WRAP, MATCH)
            lp.setMargins(dp(3), 0, dp(3), 0)
            bar.addView(b, lp)
            return b
        }
        add("✓ OK") { setEditing(false) }
        add("+ Bloc") { showAddDialog() }
        undoBtn = add("↶") { undoStep() }
        redoBtn = add("↷") { redoStep() }
        snapBtn = add("Aimant") { toggleSnap() }
        gridBtn = add("Grille") { toggleGrid() }
        add("⇆ Miroir") { mirror() }
        add("Défaut") { confirmReset() }
        paintToggles()
        val scroll = android.widget.HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(bar, ViewGroup.LayoutParams(WRAP, MATCH))
        return scroll
    }

    private fun toggleSnap() {
        snap = !snap
        AppPrefs.sp(this).edit().putBoolean("custom_snap", snap).apply()
        paintToggles()
        Ui.toast(this, if (snap) "Aide à l'alignement : activée" else "Aide à l'alignement : désactivée")
    }

    private fun toggleGrid() {
        grid = !grid
        AppPrefs.sp(this).edit().putBoolean("custom_grid", grid).apply()
        paintToggles()
        guides.invalidate()
    }

    private fun paintToggles() {
        for ((b, on) in listOf(snapBtn to snap, gridBtn to grid)) {
            b.background = Ui.rounded(this, if (on) Ui.ACCENT else Ui.SURFACE, 18)
            b.setTextColor(if (on) Ui.BG else Ui.TEXT)
        }
        undoBtn.alpha = if (undo.isEmpty()) 0.4f else 1f
        redoBtn.alpha = if (redo.isEmpty()) 0.4f else 1f
    }

    private fun setEditing(on: Boolean) {
        editing = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        editBtn.background = Ui.rounded(this, if (on) Ui.ACCENT_DARK else Color.TRANSPARENT, 22)
        cells.values.forEach { it.showFrame(on) }
        refreshFrames()
        guides.clear()
        if (on) {
            Ui.toast(this, "Glisser = déplacer · poignées = taille · toucher = options")
        } else {
            saveLayout()
            undo.clear()
            redo.clear()
            followLink()
        }
        paintToggles()
    }

    // ------------------------------------------------------------------ annuler / rétablir

    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    /** Mémorise l'état avant une modification (snapshot : état déjà encodé, sinon l'état actuel). */
    private fun pushUndo(snapshot: String = CustomLayout.encode(layout)) {
        if (undo.lastOrNull() == snapshot) return
        undo.addLast(snapshot)
        while (undo.size > UNDO_MAX) undo.removeFirst()
        redo.clear()
        paintToggles()
    }

    private fun undoStep() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(CustomLayout.encode(layout))
        layout = CustomLayout.decode(prev)
        rebuild()
        paintToggles()
    }

    private fun redoStep() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(CustomLayout.encode(layout))
        layout = CustomLayout.decode(next)
        rebuild()
        paintToggles()
    }

    // ------------------------------------------------------------------ ajout de blocs

    private fun showAddDialog() {
        val hidden = CustomLayout.DEFS.filter { layout[it.id]?.visible != true }
        val labels = hidden.map { it.label } + listOf("Touche de la TV…", "Macro (suite de touches)…", "Chaîne favorite…")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ajouter un bloc")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which - hidden.size) {
                    0 -> pickTvKey { key -> addExtra(CustomLayout.newKeyId(key, layout.keys), null) }
                    1 -> editMacro(emptyList()) { steps -> addExtra(CustomLayout.newId("m", layout.keys), steps.joinToString(",")) }
                    2 -> pickChannel { number, name -> addExtra(CustomLayout.newId("c", layout.keys), number.toString(), name) }
                    else -> {
                        pushUndo()
                        val def = hidden[which]
                        val item = layout[def.id] ?: CustomLayout.Item(def.id, 0.5f, 0.5f, def.w.toFloat(), def.h.toFloat(), true)
                        item.visible = true
                        item.cx = 0.5f
                        item.cy = 0.5f
                        layout[def.id] = item
                        rebuild(def.id)
                    }
                }
            }
            .show()
    }

    private fun addExtra(id: String, data: String?, label: String? = null) {
        pushUndo()
        val def = CustomLayout.def(id) ?: return
        layout[id] = CustomLayout.Item(id, 0.5f, 0.5f, def.w.toFloat(), def.h.toFloat(), true, label = label, data = data)
        rebuild(id)
    }

    private var keyCache: List<String>? = null

    /** Liste des touches connues de la TV (Netflix, sous-titres, guide…), lue une fois. */
    private fun pickTvKey(onKey: (String) -> Unit) {
        val cached = keyCache
        if (cached != null) {
            showKeyList(cached, onKey)
            return
        }
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
                keyCache = names
                showKeyList(names, onKey)
            }
        }
    }

    private fun showKeyList(names: List<String>, onKey: (String) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Touche de la TV")
            .setItems(names.toTypedArray()) { _, which -> onKey(names[which]) }
            .show()
    }

    /** Éditeur de macro : touches de la TV et pauses, dans l'ordre. */
    private fun editMacro(start: List<String>, onDone: (List<String>) -> Unit) {
        val steps = ArrayList(start)
        fun summary() = if (steps.isEmpty()) "(vide)" else steps.joinToString(" → ") { s ->
            CustomLayout.waitOf(s)?.let { "⏱ ${it / 1000.0} s" } ?: s
        }
        fun showSteps() {
            val items = arrayOf("+ Touche de la TV", "+ Pause 1 s", "+ Pause 3 s", "Retirer la dernière étape", "✓ Enregistrer")
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Macro : ${summary()}")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> pickTvKey { k -> steps.add(k); showSteps() }
                        1 -> { steps.add("~1000"); showSteps() }
                        2 -> { steps.add("~3000"); showSteps() }
                        3 -> { if (steps.isNotEmpty()) steps.removeAt(steps.size - 1); showSteps() }
                        else -> if (steps.isEmpty()) Ui.toast(this, "Macro vide") else onDone(steps)
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        showSteps()
    }

    /** Chaîne : une des favorites, ou un numéro saisi. */
    private fun pickChannel(onPick: (Int, String) -> Unit) {
        val favs = ChannelPrefs.channels(this)
        val labels = favs.map { "${it.number}  ·  ${it.name}" } + "Autre numéro…"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Chaîne")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < favs.size) {
                    onPick(favs[which].number, favs[which].name)
                } else {
                    val field = EditText(this)
                    field.inputType = InputType.TYPE_CLASS_NUMBER
                    field.hint = "Numéro"
                    val box = FrameLayout(this)
                    box.setPadding(dp(20), dp(8), dp(20), 0)
                    box.addView(field)
                    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Numéro de la chaîne")
                        .setView(box)
                        .setPositiveButton("OK") { _, _ ->
                            val n = field.text.toString().toIntOrNull()
                            if (n != null && n > 0) onPick(n, n.toString())
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            }
            .show()
    }

    /** Inverse gauche / droite (droitier ⇄ gaucher). */
    private fun mirror() {
        pushUndo()
        layout.values.forEach { it.cx = 1f - it.cx }
        placeAll()
        refreshFrames()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage("Revenir à la disposition par défaut ?")
            .setPositiveButton("Oui") { _, _ ->
                pushUndo()
                layout = CustomLayout.defaults()
                rebuild()
            }
            .setNegativeButton("Non", null)
            .show()
    }

    // ------------------------------------------------------------------ options d'un bloc

    private fun showItemDialog(id: String) {
        val item = layout[id] ?: return
        val def = CustomLayout.def(id) ?: return
        val extra = CustomLayout.isExtra(id)
        val opts = ArrayList<Pair<String, () -> Unit>>()
        for ((label, f) in CustomLayout.SCALES) {
            opts.add("Taille : $label" to {
                pushUndo()
                item.w = def.w * f
                item.h = def.h * f
                rebuild()
            })
        }
        if (extra) opts.add("Nom, icône et couleur…" to { customize(item) })
        if (id.startsWith("m.")) opts.add("Modifier les étapes…" to {
            editMacro(CustomLayout.steps(item)) { steps ->
                pushUndo()
                item.data = steps.joinToString(",")
            }
        })
        if (id.startsWith("c.")) opts.add("Changer de chaîne…" to {
            pickChannel { n, name ->
                pushUndo()
                item.data = n.toString()
                item.label = name
                rebuild()
            }
        })
        opts.add("Supprimer" to {
            pushUndo()
            if (extra) {
                layout.remove(id) // bloc ajouté : supprimé pour de bon
            } else {
                item.visible = false // bloc d'origine : réajoutable par « + Bloc »
            }
            rebuild()
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(item.label ?: def.label)
            .setItems(opts.map { it.first }.toTypedArray()) { _, which -> opts[which].second() }
            .show()
    }

    /** Nom, puis icône, puis couleur d'un bloc ajouté. */
    private fun customize(item: CustomLayout.Item) {
        val def = CustomLayout.def(item.id) ?: return
        askName("Nom du bouton", item.label ?: def.label) { name ->
            val icons = CustomLayout.ICONS
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Icône")
                .setItems(icons.map { it.first }.toTypedArray()) { _, i ->
                    val colors = CustomLayout.COLORS
                    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Couleur")
                        .setItems(colors.map { it.first }.toTypedArray()) { _, c ->
                            pushUndo()
                            item.label = name
                            item.icon = icons[i].first
                            item.color = colors[c].second
                            rebuild()
                        }
                        .show()
                }
                .show()
        }
    }

    // ------------------------------------------------------------------ exécution des blocs ajoutés

    private fun runMacro(item: CustomLayout.Item) {
        val steps = CustomLayout.steps(item)
        if (steps.isEmpty()) return
        actions.runIo {
            runCatching {
                for ((i, s) in steps.withIndex()) {
                    val wait = CustomLayout.waitOf(s)
                    if (wait != null) Thread.sleep(wait) else {
                        if (i > 0) Thread.sleep(MACRO_GAP_MS)
                        BraviaClient.sendIrcc(this, s).getOrThrow()
                    }
                }
            }
        }
    }

    private fun runChannel(item: CustomLayout.Item) {
        val n = item.data?.toIntOrNull() ?: return
        actions.runIo {
            val r = Decoder.zap(this, n)
            actions.notifyStateChanged()
            r
        }
    }

    /** placeNew : bloc qui vient d'être ajouté, posé à la place libre la plus proche du centre. */
    private fun rebuild(placeNew: String? = null) {
        buildCells()
        cells.values.forEach { it.showFrame(editing) }
        board.post {
            placeAll()
            placeNew?.let { id -> cells[id]?.let { placeFree(it) } }
            refreshFrames()
        }
        renderState()
    }

    // ------------------------------------------------------------------ blocs

    private fun buildCells() {
        board.removeAllViews()
        cells.clear()
        segTv = null
        segDecoder = null
        chLabel = null
        dpad = null
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
        val rw = if (land) REF_H else REF_W
        val rh = if (land) REF_W else REF_H
        return min(1f, min(cw / dp(rw).toFloat(), ch / dp(rh).toFloat()))
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
    private fun commit(id: String, cell: Cell, w: Int, h: Int) = commitAt(id, cell.x, cell.y, w, h)

    private fun commitAt(id: String, x: Float, y: Float, w: Int, h: Int) {
        val item = layout[id] ?: return
        val cw = board.width
        val ch = board.height
        if (cw == 0 || ch == 0) return
        item.cx = ((x + w / 2f) / cw).coerceIn(0f, 1f)
        item.cy = ((y + h / 2f) / ch).coerceIn(0f, 1f)
        val f = fit() * density
        item.w = w / f
        item.h = h / f
    }

    // ------------------------------------------------------------------ chevauchements

    private fun rectOf(c: Cell): RectF {
        val lp = c.layoutParams
        return RectF(c.x, c.y, c.x + lp.width, c.y + lp.height)
    }

    /** Chevauchement réel (1 px de tolérance : des blocs bord à bord ne se chevauchent pas). */
    private fun overlaps(a: RectF, b: RectF): Boolean =
        a.left + 1f < b.right && b.left + 1f < a.right && a.top + 1f < b.bottom && b.top + 1f < a.bottom

    /** Cadre rouge sur chaque bloc qui en chevauche un autre. */
    private fun refreshFrames() {
        if (!editing) return
        val list = cells.values.toList()
        val rects = list.map { rectOf(it) }
        val bad = BooleanArray(list.size)
        for (i in list.indices) for (j in i + 1 until list.size) {
            if (overlaps(rects[i], rects[j])) { bad[i] = true; bad[j] = true }
        }
        list.forEachIndexed { i, c -> c.showFrame(true, bad[i]) }
    }

    /**
     * Après un geste : les blocs chevauchés par [active] (qui, lui, reste où on l'a posé)
     * partent vers la place libre la plus proche, si elle existe.
     */
    private fun resolveOverlaps(active: Cell) {
        val placed = LinkedHashMap<Cell, RectF>()
        cells.values.forEach { placed[it] = rectOf(it) }
        val a = placed[active] ?: return
        val victims = cells.values.filter { it !== active && overlaps(placed[it]!!, a) }.sortedByDescending {
            val r = RectF(placed[it]!!)
            if (r.intersect(a)) r.width() * r.height() else 0f
        }
        for (v in victims) {
            val r = placed[v]!!
            val spot = freeSpot(v, r, placed) ?: continue
            placed[v] = RectF(spot.x, spot.y, spot.x + r.width(), spot.y + r.height())
            v.animate().x(spot.x).y(spot.y).setDuration(180).start()
            commitAt(v.bid, spot.x, spot.y, r.width().toInt(), r.height().toInt())
        }
        if (victims.isNotEmpty()) board.postDelayed({ refreshFrames() }, 220) else refreshFrames()
    }

    /** Place un bloc qui vient d'être ajouté à l'endroit libre le plus proche de sa position. */
    private fun placeFree(c: Cell) {
        val placed = LinkedHashMap<Cell, RectF>()
        cells.values.forEach { placed[it] = rectOf(it) }
        val r = placed[c] ?: return
        if (placed.none { (o, orc) -> o !== c && overlaps(r, orc) }) return
        val spot = freeSpot(c, r, placed) ?: return
        c.x = spot.x
        c.y = spot.y
        commitAt(c.bid, spot.x, spot.y, r.width().toInt(), r.height().toInt())
    }

    /**
     * Position libre (coin haut-gauche) la plus proche de [r] pour le bloc [c], ou null.
     * Candidats : une grille fine, plus les positions collées aux bords des autres blocs.
     */
    private fun freeSpot(c: Cell, r: RectF, placed: Map<Cell, RectF>): PointF? {
        val w = r.width()
        val h = r.height()
        val maxX = board.width - w
        val maxY = board.height - h
        if (maxX < 0f || maxY < 0f) return null
        val step = dp(6).toFloat()
        val xs = HashSet<Float>()
        val ys = HashSet<Float>()
        var t = 0f
        while (t <= maxX) { xs.add(t); t += step }
        t = 0f
        while (t <= maxY) { ys.add(t); t += step }
        xs.add(maxX); ys.add(maxY); xs.add(r.left.coerceIn(0f, maxX)); ys.add(r.top.coerceIn(0f, maxY))
        for ((o, orc) in placed) {
            if (o === c) continue
            for (v in floatArrayOf(orc.right, orc.left - w, orc.left)) if (v in 0f..maxX) xs.add(v)
            for (v in floatArrayOf(orc.bottom, orc.top - h, orc.top)) if (v in 0f..maxY) ys.add(v)
        }
        val others = placed.filterKeys { it !== c }.values.toList()
        val test = RectF()
        var best: PointF? = null
        var bestD = Float.MAX_VALUE
        for (y in ys) for (x in xs) {
            val d = (x - r.left) * (x - r.left) + (y - r.top) * (y - r.top)
            if (d >= bestD) continue
            test.set(x, y, x + w, y + h)
            if (others.none { overlaps(test, it) }) {
                bestD = d
                best = PointF(x, y)
            }
        }
        return best
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
        if (grid) {
            val g = dp(GRID_DP).toFloat()
            var t = g
            while (t < board.width) { xs.add(t); t += g }
            t = g
            while (t < board.height) { ys.add(t); t += g }
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

        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            // Grille facultative (points) en mode édition.
            if (editing && grid) {
                dot.color = Color.parseColor("#40FFFFFF")
                val g = Ui.dp(context, GRID_DP).toFloat()
                val r = Ui.dp(context, 1).toFloat().coerceAtLeast(1f)
                var y = g
                while (y < height) {
                    var x = g
                    while (x < width) { c.drawCircle(x, y, r, dot); x += g }
                    y += g
                }
            }
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
    private inner class Cell(ctx: Context, val bid: String) : FrameLayout(ctx) {
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
        private var before: String? = null
        private var lastGx: Float? = null
        private var lastGy: Float? = null

        /** Guides affichés ; petit retour haptique quand l'aimant accroche une nouvelle ligne. */
        private fun snapped(gx: Float?, gy: Float?) {
            if ((gx != null && gx != lastGx) || (gy != null && gy != lastGy)) {
                haptic(HapticFeedbackConstants.CLOCK_TICK)
            }
            lastGx = gx
            lastGy = gy
            guides.show(listOfNotNull(gx), listOfNotNull(gy))
        }

        init {
            val m = Ui.dp(ctx, 3)
            setPadding(m, m, m, m)
        }

        /** Cadre d'édition ; rouge (plein) si le bloc en chevauche un autre. */
        fun showFrame(on: Boolean, overlap: Boolean = false) {
            foreground = if (on) {
                val g = GradientDrawable()
                g.setColor(Color.parseColor(if (overlap) "#33FF3B30" else "#22FFFFFF"))
                g.cornerRadius = Ui.dp(context, 14).toFloat()
                if (overlap) g.setStroke(Ui.dp(context, 3), RED)
                else g.setStroke(Ui.dp(context, 2), Ui.ACCENT, Ui.dp(context, 6).toFloat(), Ui.dp(context, 4).toFloat())
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
                    before = CustomLayout.encode(layout)
                    lastGx = null
                    lastGy = null
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
                    if (moved) {
                        before?.let { pushUndo(it) }
                        commit(bid, this, layoutParams.width, layoutParams.height)
                        resolveOverlaps(this)
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) showItemDialog(bid)
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
            snapped(gx, gy)
            refreshFrames()
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
            snapped(gx, gy)
            refreshFrames()
        }
    }

    private fun buildContent(id: String): View? = when (id) {
        "target" -> buildTargetSwitch()
        "source" -> labeled(R.drawable.ic_input, "Source") { actions.showSourcePicker() }
        "dpad" -> DpadView(
            this,
            onKey = { key, repeat -> actions.key(key, repeat = repeat) },
            onHold = { key, start -> actions.hold(key, start) }
        ).also { dpad = it }
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
        else -> extraButton(id)
    }

    /** Bloc ajouté : touche de la TV, macro ou chaîne ; nom, icône et couleur personnalisables. */
    private fun extraButton(id: String): View? {
        val item = layout[id] ?: return null
        val key = CustomLayout.keyOf(id)
        val (defIcon, defLabel) = when {
            key != null -> R.drawable.ic_tune to key
            id.startsWith("m.") -> R.drawable.ic_scene to "Macro"
            id.startsWith("c.") -> R.drawable.ic_tv to (item.data ?: "Chaîne")
            else -> return null
        }
        val res = CustomLayout.ICONS.firstOrNull { it.first == item.icon }?.second ?: defIcon
        val box = labeled(res, item.label ?: defLabel) {
            when {
                key != null -> actions.key(key)
                id.startsWith("m.") -> runMacro(item)
                else -> runChannel(item)
            }
        }
        item.color?.let { box.background = Ui.rounded(this, it, 26) }
        return box
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
        /** Pas de la grille (dp). */
        const val GRID_DP = 24
        const val UNDO_MAX = 50
        const val MACRO_GAP_MS = 350L
        val RED = Color.parseColor("#FF3B30")
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

/** Liste des télécommandes perso (identifiant, nom), télécommande affichée et liaisons TV / décodeur. */
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
            if (legacy != null) e.putString(CustomLayout.key("1", false), legacy).remove("custom_remote_layout")
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

    /** Nouvelle télécommande (copie de [copyFrom], portrait et paysage, sinon disposition par défaut). */
    fun create(ctx: Context, name: String, copyFrom: String?): String {
        val all = ids(ctx)
        val id = ((all.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        val e = sp(ctx).edit()
            .putString(IDS, (all + id).joinToString(","))
            .putString(nameKey(id), name)
        if (copyFrom != null) {
            for (land in listOf(false, true)) {
                sp(ctx).getString(CustomLayout.key(copyFrom, land), null)?.let { e.putString(CustomLayout.key(id, land), it) }
            }
        }
        e.apply()
        return id
    }

    /** Nouvelle télécommande à partir d'une disposition importée. */
    fun createFrom(ctx: Context, name: String, raw: String): String {
        val id = create(ctx, name, null)
        sp(ctx).edit().putString(CustomLayout.key(id, false), raw).apply()
        return id
    }

    fun delete(ctx: Context, id: String) {
        val rest = ids(ctx) - id
        if (rest.isEmpty()) return
        val e = sp(ctx).edit()
            .putString(IDS, rest.joinToString(","))
            .remove(nameKey(id))
            .remove(CustomLayout.key(id, false))
            .remove(CustomLayout.key(id, true))
            .putString(CURRENT, rest.first())
        for (t in Target.values()) if (link(ctx, t) == id) e.remove(linkKey(t))
        e.apply()
    }

    private fun linkKey(target: Target) = "custom_link_" + if (target == Target.DECODER) "decoder" else "tv"

    /** Télécommande perso à afficher quand la cible est [target] (null : ne pas changer). */
    fun link(ctx: Context, target: Target): String? = sp(ctx).getString(linkKey(target), null)

    fun setLink(ctx: Context, target: Target, id: String?) {
        val e = sp(ctx).edit()
        if (id == null) e.remove(linkKey(target)) else e.putString(linkKey(target), id)
        e.apply()
    }
}

/** Disposition d'une télécommande perso (mémorisée dans les préférences). */
object CustomLayout {

    /** Bloc disponible : taille de base en dp et position par défaut (centre, en fractions). */
    class Def(val id: String, val label: String, val w: Int, val h: Int, val cx: Float, val cy: Float)

    /**
     * w, h : taille en dp (avant réduction pour petit écran).
     * Blocs ajoutés : label, icon (nom dans [ICONS]), color (ARGB), data (macro : étapes ; chaîne : numéro).
     */
    class Item(
        val id: String, var cx: Float, var cy: Float, var w: Float, var h: Float, var visible: Boolean,
        var label: String? = null, var icon: String? = null, var color: Int? = null, var data: String? = null
    )

    val SCALES = listOf("Petit" to 0.75f, "Normal" to 1f, "Grand" to 1.25f, "Très grand" to 1.5f)

    val ICONS = listOf(
        "Réglage" to R.drawable.ic_tune, "Lecture" to R.drawable.ic_play, "TV" to R.drawable.ic_tv,
        "Décodeur" to R.drawable.ic_set_top_box, "Scène" to R.drawable.ic_scene, "Applications" to R.drawable.ic_apps,
        "Source" to R.drawable.ic_input, "Web" to R.drawable.ic_language, "Recherche" to R.drawable.ic_search,
        "Accueil" to R.drawable.ic_home, "Minuteur" to R.drawable.ic_timer, "Éco" to R.drawable.ic_eco,
        "Luminosité" to R.drawable.ic_brightness, "Muet" to R.drawable.ic_mute, "Marche" to R.drawable.ic_power,
        "Suivant" to R.drawable.ic_next, "Précédent" to R.drawable.ic_prev, "Clavier" to R.drawable.ic_keyboard,
        "Chiffres" to R.drawable.ic_dialpad, "Historique" to R.drawable.ic_history
    )

    val COLORS: List<Pair<String, Int?>> = listOf(
        "Par défaut" to null,
        "Vert" to Color.parseColor("#1F7A4D"), "Bleu" to Color.parseColor("#2459B8"),
        "Rouge" to Color.parseColor("#A8322D"), "Orange" to Color.parseColor("#B8661F"),
        "Violet" to Color.parseColor("#6A3DB8"), "Jaune" to Color.parseColor("#9C8418"),
        "Gris" to Color.parseColor("#3A4150")
    )

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

    /** Bloc ajouté : touche « k.<Touche>.<n> », macro « m.<n> », chaîne « c.<n> ». */
    fun isExtra(id: String) = id.startsWith("k.") || id.startsWith("m.") || id.startsWith("c.")

    fun keyOf(id: String): String? =
        if (id.startsWith("k.")) id.removePrefix("k.").substringBeforeLast('.').ifEmpty { null } else null

    fun newKeyId(key: String, used: Collection<String>): String {
        var n = 1
        while ("k.$key.$n" in used) n++
        return "k.$key.$n"
    }

    fun newId(prefix: String, used: Collection<String>): String {
        var n = 1
        while ("$prefix.$n" in used) n++
        return "$prefix.$n"
    }

    /** Étapes d'une macro : noms de touches, « ~<ms> » pour une pause. */
    fun steps(item: Item): List<String> = item.data.orEmpty().split(',').filter { it.isNotBlank() }

    fun waitOf(step: String): Long? = if (step.startsWith("~")) step.drop(1).toLongOrNull() else null

    fun def(id: String): Def? =
        DEFS.firstOrNull { it.id == id } ?: when {
            keyOf(id) != null -> Def(id, "Touche ${keyOf(id)}", 118, 48, 0.5f, 0.5f)
            id.startsWith("m.") -> Def(id, "Macro", 118, 48, 0.5f, 0.5f)
            id.startsWith("c.") -> Def(id, "Chaîne", 118, 48, 0.5f, 0.5f)
            else -> null
        }

    fun key(remote: String, land: Boolean) = "custom_remote_layout_$remote" + if (land) "_land" else ""

    fun defaults(): MutableMap<String, Item> =
        DEFS.associateTo(LinkedHashMap<String, Item>()) { it.id to Item(it.id, it.cx, it.cy, it.w.toFloat(), it.h.toFloat(), true) }

    private const val B64 = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING

    private fun b64(text: String) = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), B64)

    private fun unb64(text: String): String? =
        runCatching { String(android.util.Base64.decode(text, B64), Charsets.UTF_8) }.getOrNull()

    /** Format : id:cx:cy:w:h:visible[:extra base64 JSON];… (ancien format id:cx:cy:échelle:visible accepté). */
    fun encode(items: Map<String, Item>): String = items.values.joinToString(";") {
        val base = "${it.id}:${it.cx}:${it.cy}:${it.w}:${it.h}:${if (it.visible) "1" else "0"}"
        if (it.label == null && it.icon == null && it.color == null && it.data == null) base else {
            val o = org.json.JSONObject()
            it.label?.let { v -> o.put("l", v) }
            it.icon?.let { v -> o.put("i", v) }
            it.color?.let { v -> o.put("c", v) }
            it.data?.let { v -> o.put("d", v) }
            base + ":" + b64(o.toString())
        }
    }

    /** Blocs inconnus ignorés ; blocs d'origine absents remis à leur place par défaut. */
    fun decode(raw: String): MutableMap<String, Item> {
        val map = defaults()
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
                6, 7 -> {
                    val w = f[3].toFloatOrNull()?.coerceIn(24f, 1200f) ?: continue
                    val h = f[4].toFloatOrNull()?.coerceIn(24f, 1200f) ?: continue
                    Item(d.id, cx, cy, w, h, f[5] == "1")
                }
                else -> continue
            }
            if (f.size == 7) {
                unb64(f[6])?.let { json ->
                    runCatching {
                        val o = org.json.JSONObject(json)
                        item.label = o.optString("l").ifEmpty { null }
                        item.icon = o.optString("i").ifEmpty { null }
                        item.color = if (o.has("c")) o.getInt("c") else null
                        item.data = o.optString("d").ifEmpty { null }
                    }
                }
            }
            map[d.id] = item
        }
        return map
    }

    /** Paysage : disposition propre, sinon celle du portrait. */
    fun load(ctx: Context, remote: String, land: Boolean): MutableMap<String, Item> {
        val sp = AppPrefs.sp(ctx)
        val raw = (if (land) sp.getString(key(remote, true), null) else null)
            ?: sp.getString(key(remote, false), "") ?: ""
        return decode(raw)
    }

    fun save(ctx: Context, remote: String, land: Boolean, items: Map<String, Item>) {
        AppPrefs.sp(ctx).edit().putString(key(remote, land), encode(items)).apply()
    }

    const val EXPORT_TAG = "ECOTV-PERSO1:"

    /** Texte d'export : étiquette, nom et disposition (base64). */
    fun export(name: String, items: Map<String, Item>): String = EXPORT_TAG + b64(name) + ":" + b64(encode(items))

    /** (nom, disposition) ou null si le texte n'est pas reconnu. */
    fun parseExport(text: String): Pair<String, String>? {
        if (!text.startsWith(EXPORT_TAG)) return null
        val parts = text.removePrefix(EXPORT_TAG).trim().split(':')
        if (parts.size != 2) return null
        val name = unb64(parts[0])?.takeIf { it.isNotBlank() } ?: return null
        val raw = unb64(parts[1]) ?: return null
        if (decode(raw).isEmpty()) return null
        return name to raw
    }
}
