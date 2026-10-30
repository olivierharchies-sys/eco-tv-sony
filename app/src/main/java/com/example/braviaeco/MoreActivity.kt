package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread

/** Toutes les autres possibilités de l'API Bravia : applications, touches, volume, mode éco, infos, redémarrage. */
class MoreActivity : Activity() {

    private lateinit var actions: TvActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        actions = TvActions(this)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setBackgroundColor(Ui.BG)
        list.setPadding(dp(16), dp(16), dp(16), dp(24))

        val title = TextView(this)
        title.text = "Plus de commandes"
        title.setTextColor(Ui.TEXT)
        title.textSize = 22f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        list.addView(title)

        val sub = TextView(this)
        sub.text = "Les autres fonctions de l'API réseau de la TV"
        sub.setTextColor(Ui.DIM)
        sub.textSize = 13f
        sub.setPadding(0, dp(2), 0, dp(10))
        list.addView(sub)

        add(list, "Applications de la TV", "Lancer YouTube, Netflix, un navigateur…") { showApps() }
        add(list, "Toutes les touches", "Chaque touche de télécommande connue de la TV") { showKeys() }
        add(list, "Volume précis", "Régler le volume avec un curseur") { showVolume() }
        add(list, "Mode économie d'énergie", "Off, Bas, Haut, Image off") { showEco() }
        add(list, "Infos de la TV", "Modèle, réseau, contenu en cours") { showInfo() }
        add(list, "Réglages de l'appli", "IP, clé PSK, page web, touche Home appareil") {
            startActivity(Intent(this, MainActivity::class.java))
        }
        add(list, "Redémarrer la TV", "Demande un redémarrage complet") { confirmReboot() }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.addView(list)
        setContentView(scroll)
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.close()
    }

    // ------------------------------------------------------------------ fonctions

    private fun showApps() {
        bg({ BraviaClient.getApps(this) }) { result ->
            val apps = result.getOrNull()
            if (apps == null || apps.isEmpty()) {
                fail(result.exceptionOrNull()?.message ?: "Aucune application")
                return@bg
            }
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Applications")
                .setItems(apps.map { it.title }.toTypedArray()) { _, which ->
                    val app = apps[which]
                    actions.runIo { BraviaClient.launchApp(this, app.uri) }
                }
                .setNegativeButton("Fermer", null)
                .show()
        }
    }

    private fun showKeys() {
        bg({ BraviaClient.keyNames(this) }) { result ->
            val names = result.getOrNull()
            if (names == null) {
                fail(result.exceptionOrNull()?.message ?: "Touches indisponibles")
                return@bg
            }
            // La liste reste ouverte : on peut enchaîner plusieurs touches.
            val lv = ListView(this)
            lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
            lv.setOnItemClickListener { _, _, position, _ ->
                actions.sendKey(names[position])
            }
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Touches (${names.size})")
                .setView(lv)
                .setNegativeButton("Fermer", null)
                .show()
        }
    }

    private fun showVolume() {
        bg({ BraviaClient.getVolume(this) }) { result ->
            val vol = result.getOrNull()
            if (vol == null) {
                fail(result.exceptionOrNull()?.message ?: "Volume indisponible")
                return@bg
            }
            val (current, max, _) = vol

            val label = TextView(this)
            label.text = "Volume : $current / $max"
            label.setTextColor(Ui.TEXT)
            label.textSize = 16f
            label.gravity = Gravity.CENTER

            val bar = SeekBar(this)
            bar.max = max
            bar.progress = current
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "Volume : $progress / $max"
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {
                    val value = bar.progress
                    actions.runIo { BraviaClient.setVolume(this@MoreActivity, value) }
                }
            })

            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(20), dp(12), dp(20), 0)
            box.addView(label)
            box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Volume")
                .setView(box)
                .setNegativeButton("Fermer", null)
                .show()
        }
    }

    private fun showEco() {
        val modes = EcoMode.values()
        val current = Prefs.lastMode(this)
        val labels = modes.map { if (it == current) "● ${it.label}" else it.label }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Économie d'énergie")
            .setItems(labels) { _, which ->
                val mode = modes[which]
                actions.runIo {
                    val r = BraviaClient.setMode(this, mode)
                    EcoWidgetProvider.refreshAll(this)
                    r
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun showInfo() {
        bg({ BraviaClient.getInfo(this) }) { result ->
            val text = result.getOrNull()
            if (text == null) {
                fail(result.exceptionOrNull()?.message ?: "Infos indisponibles")
                return@bg
            }
            val tv = TextView(this)
            tv.text = text
            tv.setTextColor(Ui.TEXT)
            tv.textSize = 13f
            tv.setTextIsSelectable(true)
            tv.setPadding(dp(20), dp(8), dp(20), 0)
            val scroll = ScrollView(this)
            scroll.addView(tv)
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Infos de la TV")
                .setView(scroll)
                .setNegativeButton("Fermer", null)
                .show()
        }
    }

    private fun confirmReboot() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Redémarrer la TV ?")
            .setMessage("La TV va redémarrer complètement.")
            .setPositiveButton("Redémarrer") { _, _ ->
                actions.runIo { BraviaClient.reboot(this) }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ------------------------------------------------------------------ outils

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun fail(message: String) = Ui.toast(this, message)

    /** Exécute [work] hors du thread principal puis [done] sur le thread principal. */
    private fun <T> bg(work: () -> T, done: (T) -> Unit) {
        thread {
            val value = work()
            runOnUiThread { if (!isFinishing) done(value) }
        }
    }

    private fun add(parent: LinearLayout, title: String, hint: String, onClick: () -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(10), dp(16), dp(10))
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = Ui.rounded(this, Ui.SURFACE, 18)
        box.isClickable = true
        box.setOnClickListener { onClick() }

        val t = TextView(this)
        t.text = title
        t.setTextColor(Ui.TEXT)
        t.textSize = 16f
        box.addView(t)

        val h = TextView(this)
        h.text = hint
        h.setTextColor(Ui.DIM)
        h.textSize = 12f
        box.addView(h)

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        parent.addView(box, lp)
    }
}
