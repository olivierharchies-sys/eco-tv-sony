package com.example.braviaeco

import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Une carte de la page des réglages (MainActivity). Chaque carte construit sa vue, garde ses propres champs,
 * enregistre ses réglages ([persist]) et remet son affichage à jour ([refresh]).
 */
internal abstract class SettingsCard(protected val act: MainActivity) {

    /** Construit la carte et l'ajoute à la page. */
    abstract fun add()

    /** Enregistre les champs de la carte (appelé avant toute action qui lit les réglages). */
    open fun persist() {}

    /** Remet à jour les textes d'état (association, permissions…). */
    open fun refresh() {}

    protected fun tell(text: String) = act.tell(text)

    protected fun show(text: String) = act.show(text)

    /** Carte vide au style commun ; à remplir puis à ajouter avec [attach]. */
    protected fun newCard(): LinearLayout {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundResource(R.drawable.card)
        val pad = Ui.dp(act, 16)
        card.setPadding(pad, pad, pad, pad)
        return card
    }

    /** Ajoute la carte à la page, avant le texte d'aide en bas. */
    protected fun attach(card: LinearLayout) {
        val scroll = (act.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)) as ScrollView
        val column = scroll.getChildAt(0) as LinearLayout
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(act, 16)
        column.addView(card, column.childCount - 1, lp)
    }

    /** Même espacement au-dessus de tous les champs de saisie des cartes. */
    protected fun fieldLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(act, 8)
        return lp
    }

    protected fun cardText(text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(act)
        t.text = text
        t.setTextColor(color)
        t.textSize = sizeSp
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4))
        return t
    }

    protected fun cardButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = Button(act)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setBackgroundResource(R.drawable.btn_surface)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48))
        lp.topMargin = Ui.dp(act, 8)
        parent.addView(b, lp)
    }
}
