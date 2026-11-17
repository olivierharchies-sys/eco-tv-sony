package com.example.braviaeco

import android.content.Context
import android.view.View

/** Réglage « retour haptique » (petite vibration à chaque appui) : activé par défaut. */
object Haptics {
    private const val KEY = "haptics"

    fun enabled(ctx: Context): Boolean = AppPrefs.sp(ctx).getBoolean(KEY, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        AppPrefs.sp(ctx).edit().putBoolean(KEY, on).apply()
    }
}

/**
 * Remplace [View.performHapticFeedback] dans toute l'appli : ne vibre que si le réglage est activé.
 * La lecture du réglage est instantanée (préférences déjà en mémoire).
 */
fun View.haptic(feedbackConstant: Int) {
    if (Haptics.enabled(context)) performHapticFeedback(feedbackConstant)
}
