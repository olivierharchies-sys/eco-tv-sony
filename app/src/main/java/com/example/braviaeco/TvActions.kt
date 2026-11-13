package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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

    /** Exécute un appel réseau dans l'ordre. skipIfBusy : ignoré si le précédent n'est pas fini (répétitions). */
    fun runIo(skipIfBusy: Boolean = false, work: () -> Result<*>) {
        if (skipIfBusy && pending.get() > 0) return
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
        edit.imeOptions = EditorInfo.IME_ACTION_SEND
        edit.setTextColor(Ui.TEXT)
        edit.setHintTextColor(Ui.DIM)

        // Lus et modifiés uniquement sur le fil réseau (appels faits dans l'ordre).
        var byKeys = device != AtvDevice.TV
        var typed = ""
        var sent = ""

        /**
         * TV : texte complet envoyé d'un bloc au clavier système. Si l'application affichée a son
         * propre clavier, la TV le refuse : on passe alors aux lettres envoyées une par une.
         * Décodeur : toujours lettre par lettre (Wi-Fi direct).
         */
        fun push(text: String): Result<Unit> {
            if (!byKeys) {
                val r = BraviaClient.setText(act, text)
                if (r.exceptionOrNull() !is NoTextFieldException) return r
                byKeys = true
            }
            // Champ de saisie annoncé par l'appareil : texte complet remis à son clavier.
            val ime = AtvRemote.sendText(act, sent, text, device)
            if (ime.getOrNull() == true) sent = text
            if (ime.getOrNull() != false) return ime.map { }
            // Sinon (application avec son propre clavier) : lettres envoyées comme des touches.
            val target = AtvRemote.typable(text)
            return AtvRemote.type(act, typed, target, device).onSuccess { typed = target }
        }

        /** Vraie touche Entrée (valide la saisie) ; « OK » choisirait la lettre surlignée à l'écran. */
        fun enter(): Result<Unit> =
            if (device.paired(act) && device.ip(act).isNotEmpty()) AtvRemote.sendKey(act, KEY_ENTER, device)
            else BraviaClient.sendIrcc(act, "Confirm")

        // Saisie directe : chaque caractère tapé ou effacé part aussitôt, dans l'ordre.
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                runIo { push(text) }
            }
        })
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = edit.text.toString()
                runIo { push(text).mapCatching { enter().getOrThrow() } }
                true
            } else {
                false
            }
        }

        val box = FrameLayout(act)
        val pad = Ui.dp(act, 20)
        box.setPadding(pad, Ui.dp(act, 8), pad, 0)
        box.addView(
            edit,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val dialog = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(
                when (device) {
                    AtvDevice.DECODER -> "Clavier du décodeur"
                    AtvDevice.EDENWOOD -> "Clavier de la Edenwood"
                    AtvDevice.TV -> "Clavier de la TV"
                }
            )
            .setView(box)
            .setPositiveButton("Entrée") { _, _ ->
                val text = edit.text.toString()
                runIo { push(text).mapCatching { enter().getOrThrow() } }
            }
            .setNegativeButton("Fermer", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }

    // ------------------------------------------------------------------ pavé numérique

    fun showNumpad(favorites: Boolean = true) {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, 16)
        box.setPadding(pad, Ui.dp(act, 8), pad, 0)

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "OK")
        )
        val margin = Ui.dp(act, 4)
        for (labels in rows) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            for (label in labels) {
                val cell: View
                if (label.isEmpty()) {
                    cell = View(act)
                } else {
                    val b = Ui.pill(act, label, 20f)
                    b.background = Ui.rounded(act, Ui.SURFACE_HI, 16)
                    b.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        key(if (label == "OK") "Confirm" else "Num$label")
                    }
                    cell = b
                }
                val lp = LinearLayout.LayoutParams(0, Ui.dp(act, 58), 1f)
                lp.setMargins(margin, margin, margin, margin)
                row.addView(cell, lp)
            }
            box.addView(row)
        }

        val dialog = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pavé numérique")
            .setView(box)
        // Chaînes favorites : contenus de la Sony, sans objet pour la Edenwood.
        if (favorites) dialog.setNeutralButton("Chaînes favorites") { _, _ -> Favorites.show(act, this) }
        dialog.setNegativeButton("Fermer", null).show()
    }

    private companion object {
        /** Le son sort toujours de la TV : ces touches ne partent jamais vers le décodeur. */
        val TV_ONLY = setOf("VolumeUp", "VolumeDown", "Mute")
        private const val KEY_ENTER = 66

        /** Nouveaux essais de resynchronisation quand la TV ne répond pas encore (démarrage). */
        private const val SYNC_RETRIES = 12
        private const val SYNC_RETRY_MS = 5000L
    }
}
