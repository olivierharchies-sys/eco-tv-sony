package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Actions partagées par la télécommande et la fenêtre de choix de source (lancée par le widget) :
 * envoi des touches dans l'ordre, marche/arrêt, liste des sources, clavier et pavé numérique.
 */
class TvActions(private val act: Activity) {

    /** Appelé sur le thread principal quand l'état affiché (marche/arrêt, source) a changé. */
    var onStateChanged: (() -> Unit)? = null

    /**
     * Si défini, remplace l'envoi de [key] (télécommande Edenwood : les touches partent vers cette TV
     * au lieu de la Sony). Reçoit les noms de touches et true pour une répétition d'appui prolongé.
     */
    var customKey: ((names: Array<out String>, repeat: Boolean) -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /**
     * Relectures d'état et préchargement : fil séparé, pour qu'un appel lent vers une TV qui ne répond pas
     * ne retarde jamais une touche.
     */
    private val status = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private var lastToast = 0L

    private class Entry(val label: String, val saved: String?, val run: () -> Result<*>)

    fun close() {
        io.shutdown()
        status.shutdown()
    }

    // ------------------------------------------------------------------ envoi

    private fun exec(block: () -> Unit) {
        try {
            io.execute(Runnable { block() })
        } catch (e: RejectedExecutionException) {
            // activité déjà fermée : on ignore
        }
    }

    private fun execStatus(block: () -> Unit) {
        try {
            status.execute(Runnable { block() })
        } catch (e: RejectedExecutionException) {
            // activité déjà fermée : on ignore
        }
    }

    /**
     * Exécute un appel réseau dans l'ordre. skipIfBusy : pour les répétitions, ignoré si deux appels sont déjà
     * en attente (un en cours + un suivant prêt). Garder un appel d'avance évite le « temps mort » entre deux
     * touches qui rendait la croix saccadée, sans pour autant empiler les répétitions.
     */
    fun runIo(skipIfBusy: Boolean = false, work: () -> Result<*>) {
        if (skipIfBusy && pending.get() > 1) return
        pending.incrementAndGet()
        try {
            io.execute(Runnable {
                val result = try {
                    work()
                } finally {
                    pending.decrementAndGet()
                }
                val error = result.exceptionOrNull()
                if (error != null) ui.post { toast(error.message ?: "Échec") }
            })
        } catch (e: RejectedExecutionException) {
            pending.decrementAndGet()
        }
    }

    private fun toast(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToast < 2500) return
        lastToast = now
        Ui.toast(act, message)
    }

    /** Touche envoyée à la TV. */
    fun sendKey(vararg names: String, skipIfBusy: Boolean = false) {
        runIo(skipIfBusy) { BraviaClient.sendIrcc(act, *names) }
    }

    /**
     * Touche envoyée à l'appareil choisi (TV ou décodeur). Le volume reste toujours sur la TV.
     * repeat : répétition d'appui prolongé, ignorée si la précédente n'est pas finie.
     */
    fun key(vararg names: String, repeat: Boolean = false) {
        customKey?.let { it(names, repeat); return }
        val toDecoder = Prefs.target(act) == Target.DECODER && names.first() !in TV_ONLY
        runIo(repeat) {
            if (toDecoder) Decoder.sendKey(act, *names) else BraviaClient.sendIrcc(act, *names)
        }
    }

    /**
     * Début (start = true) ou fin d'un appui maintenu sur une flèche. Renvoie false si cela ne s'applique pas
     * (cible TV, décodeur non associé en Wi-Fi) : l'appelant garde alors la répétition d'appuis courts.
     */
    fun hold(name: String, start: Boolean): Boolean {
        if (Prefs.target(act) != Target.DECODER || name in TV_ONLY || !Decoder.canHold(act, name)) return false
        runIo { Decoder.hold(act, name, start) }
        return true
    }

    /** Choisit l'appareil visé par la croix, les chaînes et la lecture. */
    fun selectTarget(target: Target) {
        Prefs.saveTarget(act, target)
        if (target == Target.DECODER && !Decoder.wifi(act)) {
            Ui.toast(act, "Décodeur non associé en Wi-Fi : touches relayées par la TV (HDMI-CEC)")
        }
        ui.post { onStateChanged?.invoke() }
    }

    /** Met à jour l'écran et les widgets après un changement de source fait ailleurs (raccourcis). */
    fun notifyStateChanged() {
        afterChange()
    }

    private fun afterChange() {
        PowerWidgetProvider.refreshAll(act)
        SourceWidgetProvider.refreshAll(act)
        ui.post { onStateChanged?.invoke() }
    }

    // ------------------------------------------------------------------ home, web, plus

    /** Home du périphérique sélectionné (appareil HDMI-CEC) ; Home de la TV si la source est la TV. */
    fun deviceHome() {
        runIo { Decoder.home(act) }
    }

    /** Home de la TV elle-même. */
    fun tvHome() {
        runIo { BraviaClient.sendTvHome(act) }
    }

    /** Ouvre sur la TV la page web et le navigateur réglés dans l'appli. */
    fun openWeb() {
        runIo { BraviaClient.openWeb(act) }
    }

    /** Écran avec toutes les autres possibilités de l'API. */
    fun openMore() {
        act.startActivity(Intent(act, MoreActivity::class.java))
    }

    // ------------------------------------------------------------------ marche / arrêt

    fun togglePower() {
        runIo {
            val r = BraviaClient.togglePower(act)
            if (r.isSuccess) afterChange()
            r
        }
    }

    /** Relit l'état réel de la TV, sans message d'erreur si elle est injoignable. */
    fun refreshPower(attempt: Int = 0) {
        execStatus {
            if (attempt == 0) {
                // Connexion et liste des touches prêtes avant la première pression.
                if (Prefs.target(act) == Target.DECODER) Decoder.warmUp(act) else BraviaClient.warmUp(act)
            }
            val r = BraviaClient.getPowerOn(act)
            var synced = r.isSuccess && r.getOrNull() != true // éteinte : rien à recaler
            if (r.getOrNull() == true) {
                val uri = BraviaClient.syncSource(act).getOrNull()
                val decoderUri = ShortcutPrefs.decoderInputUri(act)
                if (uri != null) {
                    synced = true
                    // Profil d'image associé à l'entrée, si l'entrée a changé depuis la dernière ouverture.
                    val auto = Picture.autoApply(act)
                    if (auto != null) ui.post { Ui.toast(act, "Image : $auto") }
                    if (decoderUri.isNotEmpty()) {
                        val onDecoder = BraviaClient.sameInput(uri, decoderUri)
                        Prefs.saveTarget(act, if (onDecoder) Target.DECODER else Target.TV)
                    }
                }
            }
            if (r.isSuccess) afterChange()
            // TV injoignable ou qui démarre encore : nouvel essai dans quelques secondes (sans bloquer les touches).
            if (!synced && attempt < SYNC_RETRIES) {
                ui.postDelayed({ if (!act.isFinishing) refreshPower(attempt + 1) }, SYNC_RETRY_MS)
            }
        }
    }

    // ------------------------------------------------------------------ volume

    /** Curseur de volume de la TV : le son suit le doigt. */
    fun showVolume() {
        exec {
            val result = BraviaClient.getVolume(act)
            ui.post {
                if (act.isFinishing) return@post
                val vol = result.getOrNull()
                if (vol == null) {
                    toast(result.exceptionOrNull()?.message ?: "Volume indisponible")
                    return@post
                }
                val current = vol.first
                val max = vol.second

                val label = TextView(act)
                label.text = "Volume : $current / $max"
                label.setTextColor(Ui.TEXT)
                label.textSize = 16f
                label.gravity = Gravity.CENTER

                val bar = SeekBar(act)
                bar.max = max
                bar.progress = current
                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                        label.text = "Volume : $progress / $max"
                        if (fromUser) runIo(skipIfBusy = true) { BraviaClient.setVolume(act, progress) }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        val value = bar.progress
                        runIo { BraviaClient.setVolume(act, value) }
                    }
                })

                val box = LinearLayout(act)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(Ui.dp(act, 20), Ui.dp(act, 12), Ui.dp(act, 20), 0)
                box.addView(label)
                box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Volume de la TV")
                    .setView(box)
                    .setNegativeButton("Fermer", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ source

    fun showSourcePicker(onDone: (() -> Unit)? = null) {
        exec {
            val result = BraviaClient.getInputs(act)
            ui.post {
                if (act.isFinishing) return@post

                val entries = ArrayList<Entry>()
                entries.add(Entry("TV", "TV") { BraviaClient.sendIrcc(act, "Tv") })

                val inputs = result.getOrNull()
                if (inputs != null) {
                    for (input in inputs) {
                        val label = if (input.connected) input.display else input.display + " (inactif)"
                        entries.add(Entry(label, input.display) { BraviaClient.setInput(act, input) })
                    }
                } else {
                    toast(result.exceptionOrNull()?.message ?: "Sources indisponibles")
                    for (n in 1..4) {
                        entries.add(Entry("HDMI $n", "HDMI $n") { BraviaClient.sendIrcc(act, "Hdmi$n") })
                    }
                }
                entries.add(Entry("Menu des sources de la TV", null) { BraviaClient.sendIrcc(act, "Input") })

                val labels = entries.map { it.label }.toTypedArray()
                AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Source")
                    .setItems(labels) { _, which ->
                        val entry = entries[which]
                        runIo {
                            val r = entry.run()
                            val name = entry.saved
                            if (r.isSuccess && name != null) {
                                Prefs.saveSource(act, name)
                                afterChange()
                            }
                            r
                        }
                    }
                    .setOnDismissListener { onDone?.invoke() }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ économie d'énergie

    /** Bascule l'économie d'énergie de la TV entre Haut et Bas (Haut si elle était sur autre chose). */
    fun toggleEco() {
        runIo {
            runCatching {
                val current = BraviaClient.getMode(act).getOrNull() ?: Prefs.lastMode(act)
                val next = if (current == EcoMode.HIGH) EcoMode.LOW else EcoMode.HIGH
                BraviaClient.setMode(act, next).getOrThrow()
                EcoWidgetProvider.refreshAll(act)
                Ui.toast(act, "Économie d'énergie : ${next.label}")
            }
        }
    }

    // ------------------------------------------------------------------ clavier

    /**
     * Colle le texte du presse-papiers du téléphone (ex. titre copié par StreOli)
     * dans le champ de saisie affiché à l'écran. Pas de touche Entrée : la recherche se fait en direct.
     */
    fun pasteClipboard() {
        val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(120)
        if (text.isNullOrEmpty()) {
            Ui.toast(act, "Presse-papiers vide : copie d'abord un titre")
            return
        }
        pasteText(text)
    }

    /** Tape [text] dans le champ de saisie affiché sur la TV (ou le décodeur visé). */
    fun pasteText(text: String, forced: AtvDevice? = null) {
        val device = forced ?: remoteDevice()
        val wifi = device.paired(act) && device.ip(act).isNotEmpty()
        if (!wifi && device == AtvDevice.EDENWOOD) {
            Ui.toast(act, "Edenwood non associée (Réglages d'Éco TV)")
            return
        }
        Ui.toast(act, "Collage de « $text »…")
        runIo {
            if (!wifi) {
                BraviaClient.setText(act, text)
            } else {
                val letters = AtvRemote.typable(text)
                if (letters.isEmpty()) Result.failure(IllegalStateException("Aucun caractère envoyable"))
                else AtvRemote.type(act, "", letters, device)
            }
        }
    }

    /** Ouvre StreOli sur le téléphone, à la dernière page affichée (tâche reprise telle quelle). */
    fun openStreOli() {
        val pm = act.packageManager
        // 1) Intent de lancement fourni par Android ; 2) activité explicite en secours.
        val i = (pm.getLaunchIntentForPackage(StreOliLink.PKG)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(StreOliLink.PKG, "${StreOliLink.PKG}.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            act.startActivity(i)
        } catch (e: Exception) {
            val installed = runCatching { pm.getPackageInfo(StreOliLink.PKG, 0) }.isSuccess
            Ui.toast(
                act,
                if (installed) "StreOli refuse l'ouverture : ${e.javaClass.simpleName}"
                else "StreOli introuvable sur ce téléphone",
            )
        }
    }

    /** Appareil Sony visé par la télécommande (TV ou décodeur). */
    fun remoteDevice(): AtvDevice = if (Prefs.target(act) == Target.DECODER) AtvDevice.DECODER else AtvDevice.TV

    fun showKeyboard(forced: AtvDevice? = null) {
        // Le clavier suit l'appareil visé par la télécommande (TV ou décodeur), sauf appareil imposé.
        val device = forced ?: if (Prefs.target(act) == Target.DECODER) AtvDevice.DECODER else AtvDevice.TV
        val edit = EditText(act)
        edit.hint = when (device) {
            AtvDevice.DECODER -> "Texte à envoyer au décodeur"
            AtvDevice.EDENWOOD -> "Texte à envoyer à la Edenwood"
            AtvDevice.TV -> "Texte à envoyer à la TV"
        }
        edit.setSingleLine(true)
        // Pas de clavier plein écran en paysage : il remplace le champ et peut perdre l'action Entrée.
        edit.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        edit.setTextColor(Ui.TEXT)
        edit.setHintTextColor(Ui.DIM)

        /**
         * Envoi au clic sur Entrée seulement (rien pendant la frappe) :
         * appareil associé en Wi-Fi direct → lettres simulées une par une, puis la touche Entrée ;
         * sinon (TV Sony non associée) → texte remis au clavier de la TV, puis OK.
         */
        val wifi = device.paired(act) && device.ip(act).isNotEmpty()

        fun push(text: String): Result<Unit> {
            if (!wifi) return BraviaClient.setText(act, text)
            val letters = AtvRemote.typable(text)
            if (letters.isEmpty()) return Result.failure(IllegalStateException("Aucun caractère envoyable"))
            return AtvRemote.type(act, "", letters, device)
        }

        /** Vide le champ affiché à l'écran : effacements répétés (Wi-Fi direct), sinon texte vide. */
        fun clearRemote(): Result<Unit> = runCatching {
            if (!wifi) return@runCatching BraviaClient.setText(act, "").getOrThrow()
            repeat(CLEAR_DELETES) {
                AtvRemote.sendKey(act, KEY_DEL, device).getOrThrow()
                Thread.sleep(30)
            }
        }

        /** Vraie touche Entrée (valide la saisie) ; « OK » choisirait la lettre surlignée à l'écran. */
        fun enter(): Result<Unit> =
            if (wifi) AtvRemote.sendKey(act, KEY_ENTER, device)
            else BraviaClient.sendIrcc(act, "Confirm")

        // --- Fenêtre graphique : en-tête (balai, fermer), champ arrondi, Entrée.
        val panel = panel()
        val top = header(
            "⌨️",
            when (device) {
                AtvDevice.DECODER -> "Clavier · Décodeur"
                AtvDevice.EDENWOOD -> "Clavier · Edenwood"
                AtvDevice.TV -> "Clavier · TV"
            }
        )
        panel.addView(top)

        edit.textSize = 18f
        edit.background = android.graphics.drawable.GradientDrawable().also {
            it.setColor(Ui.SURFACE_HI)
            it.setStroke(Ui.dp(act, 2), Ui.ACCENT)
            it.cornerRadius = Ui.dp(act, 16) * Ui.ROUND
        }
        edit.setPadding(Ui.dp(act, 16), Ui.dp(act, 14), Ui.dp(act, 16), Ui.dp(act, 14))
        panel.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setView(panel).create()

        fun sendEnter() {
            val text = edit.text.toString()
            if (text.isBlank()) {
                runIo { enter() }
                return
            }
            if (wifi && AtvRemote.typable(text) != text.lowercase()) Ui.toast(act, "Accents, majuscules et symboles spéciaux simplifiés")
            edit.text.clear()
            runIo { push(text).mapCatching { enter().getOrThrow() } }
        }
        // Selon le clavier (Gboard, Samsung, clavier physique…), la touche Entrée arrive comme action « Envoyer »,
        // « OK », « Aller », « Rechercher », ou comme simple touche Entrée sans action : toutes envoient le texte.
        // Une touche Entrée produit un appui et un relâchement : seul l'appui compte, pour ne pas envoyer deux fois.
        edit.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            val isImeAction = actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH
            when {
                isEnterKey -> {
                    if (event!!.action == android.view.KeyEvent.ACTION_DOWN) sendEnter()
                    true
                }
                isImeAction -> {
                    sendEnter()
                    true
                }
                else -> false
            }
        }
        // Balai (vide le champ de l'écran) et ⓧ (ferme) à droite du nom de l'appareil.
        top.addView(roundIcon("🧹") {
            Ui.toast(act, "Champ de l'écran vidé…")
            runIo { clearRemote() }
        })
        top.addView(roundIcon("✕") { dialog.dismiss() })
        val go = wideKey("↵  Entrée", accent = true) { sendEnter() }
        panel.addView(go)

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }

    // ------------------------------------------------------------------ éléments graphiques des fenêtres

    /** Panneau arrondi des fenêtres clavier / pavé. */
    private fun panel(): LinearLayout {
        val p = LinearLayout(act)
        p.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, 18)
        p.setPadding(pad, pad, pad, pad)
        p.background = android.graphics.drawable.GradientDrawable().also {
            it.setColor(Ui.BG)
            it.setStroke(Ui.dp(act, 1), Ui.SURFACE_HI)
            it.cornerRadius = Ui.dp(act, 28) * Ui.ROUND
        }
        return p
    }

    /** Bouton rond (icône) placé dans l'en-tête. */
    private fun roundIcon(text: String, onClick: () -> Unit): View {
        val t = TextView(act)
        t.text = text
        t.textSize = 16f
        t.gravity = Gravity.CENTER
        t.setTextColor(Ui.TEXT)
        val shape = android.graphics.drawable.GradientDrawable()
        shape.shape = android.graphics.drawable.GradientDrawable.OVAL
        shape.setColor(Ui.SURFACE)
        shape.setStroke(Ui.dp(act, 1), Ui.DIM)
        val mask = android.graphics.drawable.GradientDrawable()
        mask.shape = android.graphics.drawable.GradientDrawable.OVAL
        mask.setColor(android.graphics.Color.WHITE)
        t.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#44FFFFFF")), shape, mask
        )
        t.isClickable = true
        t.setOnClickListener {
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        val size = Ui.dp(act, 40)
        t.layoutParams = LinearLayout.LayoutParams(size, size).also { it.leftMargin = Ui.dp(act, 8) }
        return t
    }

    /** En-tête : pastille emoji + titre. */
    private fun header(emoji: String, title: String): LinearLayout {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 0, 0, Ui.dp(act, 14))
        val badge = TextView(act)
        badge.text = emoji
        badge.textSize = 18f
        badge.gravity = Gravity.CENTER
        badge.background = android.graphics.drawable.GradientDrawable().also {
            it.shape = android.graphics.drawable.GradientDrawable.OVAL
            it.setColor(Ui.SURFACE_HI)
        }
        val size = Ui.dp(act, 38)
        row.addView(badge, LinearLayout.LayoutParams(size, size).also { it.rightMargin = Ui.dp(act, 12) })
        val t = TextView(act)
        t.text = title
        t.setTextColor(Ui.TEXT)
        t.textSize = 18f
        t.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Grand bouton sur toute la largeur (accent = couleur de l'appli). */
    private fun wideKey(label: String, accent: Boolean, onClick: () -> Unit): View {
        val b = Ui.pill(act, label, 16f)
        b.background = Ui.rounded(act, if (accent) Ui.ACCENT else Ui.SURFACE, 16)
        if (accent) b.setTextColor(android.graphics.Color.parseColor("#0E1014"))
        b.setOnClickListener {
            it.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 52)).also {
            it.topMargin = Ui.dp(act, 12)
        }
        return b
    }

    /** Touche ronde du pavé (effet de vague au toucher). */
    private fun circleKey(label: String, sub: String, color: Int, textColor: Int): TextView {
        val t = TextView(act)
        t.text = if (sub.isEmpty()) label else android.text.SpannableString("$label\n$sub").also {
            it.setSpan(android.text.style.RelativeSizeSpan(0.42f), label.length + 1, label.length + 1 + sub.length, 0)
            it.setSpan(android.text.style.ForegroundColorSpan(Ui.DIM), label.length + 1, label.length + 1 + sub.length, 0)
        }
        t.setTextColor(textColor)
        t.textSize = 26f
        t.gravity = Gravity.CENTER
        t.setLineSpacing(0f, 0.9f)
        t.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        val shape = android.graphics.drawable.GradientDrawable()
        shape.shape = android.graphics.drawable.GradientDrawable.OVAL
        shape.setColor(color)
        val mask = android.graphics.drawable.GradientDrawable()
        mask.shape = android.graphics.drawable.GradientDrawable.OVAL
        mask.setColor(android.graphics.Color.WHITE)
        t.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#44FFFFFF")), shape, mask
        )
        t.isClickable = true
        return t
    }

    // ------------------------------------------------------------------ pavé numérique

    fun showNumpad(favorites: Boolean = true) {
        val panel = panel()
        panel.addView(header("🔢", "Pavé numérique"))

        // Afficheur : chiffres tapés (effacé après une pause, comme la TV).
        val display = TextView(act)
        display.textSize = 40f
        display.gravity = Gravity.CENTER
        display.setTextColor(Ui.ACCENT)
        display.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        display.letterSpacing = 0.15f
        display.hint = "– –"
        display.setHintTextColor(Ui.SURFACE_HI)
        display.background = Ui.rounded(act, Ui.SURFACE, 18)
        panel.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 72)).also {
            it.bottomMargin = Ui.dp(act, 14)
        })
        val handler = Handler(Looper.getMainLooper())
        val reset = Runnable { display.text = "" }

        val letters = mapOf(
            "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
            "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ"
        )
        val dialog = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setView(panel).create()
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(if (favorites) "★" else "", "0", "OK")
        )
        val size = Ui.dp(act, 70)
        for (labels in rows) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            for (label in labels) {
                val cell: View = when (label) {
                    "" -> View(act)
                    "OK" -> circleKey("OK", "", Ui.ACCENT, android.graphics.Color.parseColor("#0E1014")).also {
                        it.textSize = 20f
                        it.setOnClickListener { v ->
                            v.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                            key("Confirm")
                            handler.removeCallbacks(reset)
                            handler.postDelayed(reset, 600)
                        }
                    }
                    "★" -> circleKey("★", "", Ui.SURFACE, Ui.ACCENT).also {
                        it.setOnClickListener {
                            dialog.dismiss()
                            Favorites.show(act, this)
                        }
                    }
                    else -> circleKey(label, letters[label] ?: "", Ui.SURFACE_HI, Ui.TEXT).also {
                        it.setOnClickListener { v ->
                            v.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                            key("Num$label")
                            val shown = display.text.toString()
                            display.text = (if (shown.length >= 4) "" else shown) + label
                            handler.removeCallbacks(reset)
                            handler.postDelayed(reset, 2500)
                        }
                    }
                }
                val m = Ui.dp(act, 8)
                row.addView(cell, LinearLayout.LayoutParams(size, size).also { it.setMargins(m, m / 2, m, m / 2) })
            }
            panel.addView(row)
        }
        panel.addView(wideKey("Fermer", accent = false) { dialog.dismiss() })
        dialog.setOnDismissListener { handler.removeCallbacks(reset) }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
    }

    private companion object {
        /** Le son sort toujours de la TV : ces touches ne partent jamais vers le décodeur. */
        val TV_ONLY = setOf("VolumeUp", "VolumeDown", "Mute")
        private const val KEY_ENTER = 66
        private const val KEY_DEL = 67
        /** Nombre d'effacements envoyés par « Vider TV » (assez pour une recherche). */
        private const val CLEAR_DELETES = 40

        /** Nouveaux essais de resynchronisation quand la TV ne répond pas encore (démarrage). */
        private const val SYNC_RETRIES = 12
        private const val SYNC_RETRY_MS = 5000L
    }
}
