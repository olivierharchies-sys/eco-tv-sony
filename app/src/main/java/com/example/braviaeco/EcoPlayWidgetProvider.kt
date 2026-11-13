package com.example.braviaeco

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlin.concurrent.thread

/** Widget « Éco + lecture » : les 4 modes d'économie d'énergie, et Lecture / Pause. */
class EcoPlayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action != ACTION_MODE && action != ACTION_KEY) return
        val value = intent.getStringExtra(EXTRA_VALUE) ?: return

        val pending = goAsync()
        thread {
            try {
                if (action == ACTION_MODE) {
                    val mode = EcoMode.fromApi(value) ?: return@thread
                    val result = BraviaClient.setMode(context, mode)
                    Ui.toast(context, result.fold({ "TV : éco ${mode.label}" }, { it.message ?: "Échec" }))
                    // Met aussi à jour le widget des modes (qui relaie vers celui-ci).
                    EcoWidgetProvider.refreshAll(context)
                } else {
                    // Lecture / Pause : vers l'appareil visé par la télécommande (TV ou décodeur).
                    val result =
                        if (Prefs.target(context) == Target.DECODER) Decoder.sendKey(context, value)
                        else BraviaClient.sendIrcc(context, value)
                    result.exceptionOrNull()?.let { Ui.toast(context, it.message ?: "Échec") }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_MODE = "com.example.braviaeco.ECOPLAY_MODE"
        private const val ACTION_KEY = "com.example.braviaeco.ECOPLAY_KEY"
        private const val EXTRA_VALUE = "value"

        private val MODES = listOf(
            EcoMode.OFF to R.id.ep_off,
            EcoMode.LOW to R.id.ep_low,
            EcoMode.HIGH to R.id.ep_high,
            EcoMode.PICTURE_OFF to R.id.ep_picture_off
        )

        private val KEYS = listOf("Play" to R.id.ep_play, "Pause" to R.id.ep_pause)

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EcoPlayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_eco_play)
            val current = Prefs.lastMode(context)
            views.setTextViewText(R.id.ep_title, "Éco · " + (current?.label ?: "?"))
            views.setOnClickPendingIntent(
                R.id.ep_title,
                WidgetIntents.activity(context, RemoteActivity::class.java, 7300)
            )
            MODES.forEachIndexed { index, (mode, viewId) ->
                views.setInt(
                    viewId,
                    "setBackgroundResource",
                    if (mode == current) R.drawable.dw_round_on else R.drawable.dw_round_idle
                )
                views.setOnClickPendingIntent(
                    viewId,
                    WidgetIntents.broadcast(
                        context, EcoPlayWidgetProvider::class.java, ACTION_MODE, 7310 + index, EXTRA_VALUE, mode.api
                    )
                )
            }
            KEYS.forEachIndexed { index, (key, viewId) ->
                views.setOnClickPendingIntent(
                    viewId,
                    WidgetIntents.broadcast(
                        context, EcoPlayWidgetProvider::class.java, ACTION_KEY, 7320 + index, EXTRA_VALUE, key
                    )
                )
            }
            return views
        }
    }
}
