package com.example.braviaeco

import android.app.AlertDialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Cartes « Bouton Web » (page et navigateur ouverts sur la TV) et « Home appareil » (touche HDMI-CEC). */
internal class WebCard(act: MainActivity) : SettingsCard(act) {

    private lateinit var webField: EditText
    private lateinit var homeKeyField: EditText
    private lateinit var browserLabel: TextView

    override fun persist() {
        if (!::webField.isInitialized) return
        Prefs.saveWeb(act, webField.text.toString())
        Prefs.saveDeviceHomeKey(act, homeKeyField.text.toString())
    }

    private fun showBrowser() {
        val title = Prefs.webAppTitle(act)
        browserLabel.text = act.getString(R.string.settings_navigateur_de_la_tv) + (if (title.isEmpty()) act.getString(R.string.settings_navigateur_integre_par_defaut) else title)
    }

    /** Carte « Bouton Web » et « Home appareil », ajoutée avant le texte d'aide. */
    override fun add() {
        val card = newCard()

        fun title(text: String): TextView {
            val t = TextView(act)
            t.text = text
            t.setTextColor(Color.WHITE)
            t.textSize = 17f
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            return t
        }

        fun note(text: String): TextView {
            val t = TextView(act)
            t.text = text
            t.setTextColor(Ui.DIM)
            t.textSize = 12f
            t.setPadding(0, Ui.dp(act, 4), 0, 0)
            return t
        }

        fun field(hint: String, value: String): EditText {
            val e = EditText(act)
            e.hint = hint
            e.setText(value)
            e.setSingleLine(true)
            e.setTextColor(Color.WHITE)
            e.setHintTextColor(Ui.DIM)
            return e
        }

        card.addView(title(act.getString(R.string.settings_bouton_web)))
        card.addView(note(act.getString(R.string.settings_page_ouverte_sur_la_tv)))
        webField = field(act.getString(R.string.settings_adresse_de_la_page_ex), Prefs.webUrl(act))
        webField.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        card.addView(webField, fieldLp())

        browserLabel = TextView(act)
        browserLabel.setTextColor(Color.WHITE)
        browserLabel.textSize = 14f
        browserLabel.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 4))
        card.addView(browserLabel)
        showBrowser()

        val pick = android.widget.Button(act)
        pick.text = act.getString(R.string.settings_choisir_le_navigateur_de_la)
        pick.isAllCaps = false
        pick.setTextColor(Color.WHITE)
        pick.setBackgroundResource(R.drawable.btn_surface)
        pick.setOnClickListener { act.saveAll(); pickBrowser() }
        card.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48)))

        val test = android.widget.Button(act)
        test.text = act.getString(R.string.settings_tester_le_bouton_web)
        test.isAllCaps = false
        test.setTextColor(Color.WHITE)
        test.setBackgroundResource(R.drawable.btn_surface)
        test.setOnClickListener {
            act.saveAll()
            act.runAction(act.getString(R.string.settings_ouverture), loud = true) {
                BraviaClient.openWeb(act).fold(
                    onSuccess = { act.getString(R.string.settings_page_envoyee_a_la_tv) },
                    onFailure = { "Échec : ${it.message}" }
                )
            }
        }
        val testLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48))
        testLp.topMargin = Ui.dp(act, 8)
        card.addView(test, testLp)

        val sep = TextView(act)
        sep.setPadding(0, Ui.dp(act, 14), 0, 0)
        card.addView(sep)

        card.addView(title(act.getString(R.string.settings_home_appareil)))
        card.addView(note(
            act.getString(R.string.settings_touche_envoyee_a_l_appareil)
        ))
        homeKeyField = field(act.getString(R.string.settings_nom_de_la_touche), Prefs.deviceHomeKey(act))
        card.addView(homeKeyField, fieldLp())

        attach(card)
    }

    private fun pickBrowser() {
        tell(act.getString(R.string.settings_lecture_des_applications))
        Bg.run {
            val result = BraviaClient.getApps(act)
            act.runOnUiThread {
                val apps = result.getOrNull()
                if (apps == null) {
                    tell("Échec : ${result.exceptionOrNull()?.message}")
                    return@runOnUiThread
                }
                show("")
                val labels = (listOf(act.getString(R.string.settings_navigateur_integre_par_defaut_2)) + apps.map { it.title }).toTypedArray()
                AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(act.getString(R.string.settings_navigateur_de_la_tv_2))
                    .setItems(labels) { _, which ->
                        Prefs.saveWebApp(act, if (which == 0) null else apps[which - 1])
                        showBrowser()
                    }
                    .setNegativeButton(act.getString(R.string.dw_sleep_cancel), null)
                    .show()
            }
        }
    }
}
