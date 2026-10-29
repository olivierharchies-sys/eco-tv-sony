package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private var lastToast = 0L

    private class Entry(val label: String, val saved: String?, val run: () -> Result<*>)

    fun close() {
        io.shutdown()
    }

    // ------------------------------------------------------------------ envoi

    private fun exec(block: () -> Unit) {
        try {
            io.execute(Runnable { block() })
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

    fun sendKey(vararg names: String, skipIfBusy: Boolean = false) {
        runIo(skipIfBusy) { BraviaClient.sendIrcc(act, *names) }
    }

    private fun afterChange() {
        PowerWidgetProvider.refreshAll(act)
        SourceWidgetProvider.refreshAll(act)
        ui.post { onStateChanged?.invoke() }
    }

    // ------------------------------------------------------------------ home, web, plus

    /** Home du périphérique sélectionné (appareil HDMI-CEC) ; Home de la TV si la source est la TV. */
    fun deviceHome() {
        runIo { BraviaClient.sendDeviceHome(act) }
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
    fun refreshPower() {
        exec {
            val r = BraviaClient.getPowerOn(act)
            if (r.isSuccess) afterChange()
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

    // ------------------------------------------------------------------ clavier

    fun showKeyboard() {
        val edit = EditText(act)
        edit.hint = "Texte à envoyer à la TV"
        edit.setSingleLine(true)
        edit.imeOptions = EditorInfo.IME_ACTION_SEND
        edit.setTextColor(Ui.TEXT)
        edit.setHintTextColor(Ui.DIM)

        // Chaque modification envoie le texte complet (après une courte pause de frappe).
        var sendTask: Runnable? = null
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                sendTask?.let { ui.removeCallbacks(it) }
                val text = s?.toString() ?: ""
                val task = Runnable { runIo { BraviaClient.setText(act, text) } }
                sendTask = task
                ui.postDelayed(task, 350)
            }
        })
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendKey("Confirm")
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
            .setTitle("Clavier de la TV")
            .setView(box)
            .setPositiveButton("Entrée") { _, _ -> sendKey("Confirm") }
            .setNegativeButton("Fermer", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }

    // ------------------------------------------------------------------ pavé numérique

    fun showNumpad() {
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
                        sendKey(if (label == "OK") "Confirm" else "Num$label")
                    }
                    cell = b
                }
                val lp = LinearLayout.LayoutParams(0, Ui.dp(act, 58), 1f)
                lp.setMargins(margin, margin, margin, margin)
                row.addView(cell, lp)
            }
            box.addView(row)
        }

        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pavé numérique")
            .setView(box)
            .setNegativeButton("Fermer", null)
            .show()
    }
}
