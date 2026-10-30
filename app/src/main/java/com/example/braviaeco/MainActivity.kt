package com.example.braviaeco

import android.app.Activity
import android.content.Intent
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var ipField: EditText
    private lateinit var pskField: EditText
    private lateinit var status: TextView
    private lateinit var webField: EditText
    private lateinit var homeKeyField: EditText
    private lateinit var browserLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipField = findViewById(R.id.field_ip)
        pskField = findViewById(R.id.field_psk)
        status = findViewById(R.id.status)

        ipField.setText(Prefs.ip(this))
        pskField.setText(Prefs.psk(this))

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            save()
            show("Réglages enregistrés")
        }

        findViewById<Button>(R.id.btn_open_remote).setOnClickListener {
            save()
            startActivity(Intent(this, RemoteActivity::class.java))
        }

        findViewById<Button>(R.id.btn_read).setOnClickListener {
            save()
            run("Lecture…") {
                BraviaClient.getMode(this).fold(
                    onSuccess = { "Mode actuel de la TV : ${it.label}" },
                    onFailure = { "Échec : ${it.message}" }
                )
            }
        }

        addExtraSettings()

        mapOf(
            R.id.test_off to EcoMode.OFF,
            R.id.test_low to EcoMode.LOW,
            R.id.test_high to EcoMode.HIGH,
            R.id.test_picture_off to EcoMode.PICTURE_OFF
        ).forEach { (id, mode) ->
            findViewById<Button>(id).setOnClickListener {
                save()
                run("Envoi : ${mode.label}…") {
                    BraviaClient.setMode(this, mode).fold(
                        onSuccess = { "OK : éco ${mode.label}" },
                        onFailure = { "Échec : ${it.message}" }
                    )
                }
            }
        }
    }

    private fun save() {
        Prefs.saveConnection(this, ipField.text.toString(), pskField.text.toString())
        if (::webField.isInitialized) {
            Prefs.saveWeb(this, webField.text.toString())
            Prefs.saveDeviceHomeKey(this, homeKeyField.text.toString())
        }
    }

    private fun showBrowser() {
        val title = Prefs.webAppTitle(this)
        browserLabel.text = "Navigateur de la TV : " + (if (title.isEmpty()) "navigateur intégré (par défaut)" else title)
    }

    /** Carte « Bouton Web » et « Home appareil », ajoutée avant le texte d'aide. */
    private fun addExtraSettings() {
        val scroll = (findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        val pad = Ui.dp(this, 16)
        card.setPadding(pad, pad, pad, pad)

        fun title(text: String): TextView {
            val t = TextView(this)
            t.text = text
            t.setTextColor(Color.WHITE)
            t.textSize = 17f
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            return t
        }

        fun note(text: String): TextView {
            val t = TextView(this)
            t.text = text
            t.setTextColor(Ui.DIM)
            t.textSize = 12f
            t.setPadding(0, Ui.dp(this, 4), 0, 0)
            return t
        }

        fun field(hint: String, value: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.setSingleLine(true)
            e.setTextColor(Color.WHITE)
            e.setHintTextColor(Ui.DIM)
            return e
        }

        card.addView(title("Bouton Web"))
        card.addView(note("Page ouverte sur la TV quand tu touches « Web »."))
        webField = field("Adresse de la page (ex. https://www.google.com)", Prefs.webUrl(this))
        webField.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        card.addView(webField)

        browserLabel = TextView(this)
        browserLabel.setTextColor(Color.WHITE)
        browserLabel.textSize = 14f
        browserLabel.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4))
        card.addView(browserLabel)
        showBrowser()

        val pick = android.widget.Button(this)
        pick.text = "Choisir le navigateur de la TV"
        pick.isAllCaps = false
        pick.setTextColor(Color.WHITE)
        pick.setBackgroundResource(R.drawable.btn_surface)
        pick.setOnClickListener { save(); pickBrowser() }
        card.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))

        val test = android.widget.Button(this)
        test.text = "Tester le bouton Web"
        test.isAllCaps = false
        test.setTextColor(Color.WHITE)
        test.setBackgroundResource(R.drawable.btn_surface)
        test.setOnClickListener {
            save()
            run("Ouverture…") {
                BraviaClient.openWeb(this).fold(
                    onSuccess = { "Page envoyée à la TV" },
                    onFailure = { "Échec : ${it.message}" }
                )
            }
        }
        val testLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48))
        testLp.topMargin = Ui.dp(this, 8)
        card.addView(test, testLp)

        val sep = TextView(this)
        sep.setPadding(0, Ui.dp(this, 14), 0, 0)
        card.addView(sep)

        card.addView(title("Home appareil"))
        card.addView(note(
            "Touche envoyée à l'appareil HDMI sélectionné (box, lecteur…) via HDMI-CEC. " +
                "Défaut : SyncMenu. Si la source est la TV, c'est le Home de la TV. " +
                "Les noms valides sont dans Plus > Toutes les touches."
        ))
        homeKeyField = field("Nom de la touche", Prefs.deviceHomeKey(this))
        card.addView(homeKeyField)

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 16)
        column.addView(card, column.childCount - 1, lp)
    }

    private fun pickBrowser() {
        show("Lecture des applications…")
        thread {
            val result = BraviaClient.getApps(this)
            runOnUiThread {
                val apps = result.getOrNull()
                if (apps == null) {
                    show("Échec : ${result.exceptionOrNull()?.message}")
                    return@runOnUiThread
                }
                show("")
                val labels = (listOf("Navigateur intégré (par défaut)") + apps.map { it.title }).toTypedArray()
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Navigateur de la TV")
                    .setItems(labels) { _, which ->
                        Prefs.saveWebApp(this, if (which == 0) null else apps[which - 1])
                        showBrowser()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
    }

    private fun show(text: String) {
        status.text = text
    }

    private fun run(progress: String, work: () -> String) {
        show(progress)
        thread {
            val message = work()
            runOnUiThread {
                show(message)
                EcoWidgetProvider.refreshAll(this)
            }
        }
    }
}
