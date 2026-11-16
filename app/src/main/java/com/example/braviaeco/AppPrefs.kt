package com.example.braviaeco

import android.content.Context
import android.content.SharedPreferences

/** Point d'accès unique au fichier de réglages de l'appli (tous les réglages vivent dans le même fichier). */
object AppPrefs {
    const val FILE = "bravia_eco"

    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
