package com.example.braviaeco

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlin.concurrent.thread

/** Fabriques de PendingIntent communes aux widgets. */
object WidgetIntents {

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Diffusion vers un widget. [code] doit être unique par bouton (les extras ne distinguent pas les PendingIntent). */
    fun broadcast(
        context: Context,
        target: Class<*>,
        action: String,
        code: Int,
        extraKey: String? = null,
        extraValue: String? = null
    ): PendingIntent {
        val intent = Intent(context, target).setAction(action)
        if (extraKey != null) intent.putExtra(extraKey, extraValue)
        return PendingIntent.getBroadcast(context, code, intent, FLAGS)
    }

    fun activity(context: Context, target: Class<*>, code: Int): PendingIntent {
        val intent = Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, code, intent, FLAGS)
    }
}

/** Widget 2 : un seul bouton marche / arrêt, vert quand la TV est allumée. */
class PowerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return

        val pending = goAsync()
        thread {
            try {
                val result = BraviaClient.togglePower(context)
                result.exceptionOrNull()?.let { Ui.toast(context, it.message ?: "Échec") }
            } finally {
                refreshAll(context)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.braviaeco.TOGGLE_POWER"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PowerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_power)
            val on = Prefs.lastPower(context)

            views.setInt(
                R.id.power_root,
                "setBackgroundResource",
                if (on == true) R.drawable.tile_on else R.drawable.tile_off
            )
            views.setTextViewText(
                R.id.power_state,
                when (on) {
                    true -> "Allumée"
                    false -> "En veille"
                    null -> "Appuyer"
                }
            )
            views.setOnClickPendingIntent(
                R.id.power_root,
                WidgetIntents.broadcast(context, PowerWidgetProvider::class.java, ACTION_TOGGLE, 8001)
            )
            return views
        }
    }
}

/** Widget 3 : ouvre la liste des sources de la TV. Affiche la dernière source choisie. */
class SourceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SourceWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_source)
            views.setTextViewText(R.id.source_state, Prefs.lastSource(context) ?: "Choisir l'entrée")
            views.setOnClickPendingIntent(
                R.id.source_root,
                WidgetIntents.activity(context, SourceActivity::class.java, 8101)
            )
            return views
        }
    }
}

/** Widget 4 : mini-télécommande volume / muet / chaînes. */
class MiniRemoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_KEY) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return

        val pending = goAsync()
        thread {
            try {
                val result = BraviaClient.sendIrcc(context, key)
                result.exceptionOrNull()?.let { Ui.toast(context, it.message ?: "Échec") }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_KEY = "com.example.braviaeco.MINI_KEY"
        const val EXTRA_KEY = "key"

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mini)
            val keys = listOf(
                Pair(R.id.mini_vol_down, "VolumeDown"),
                Pair(R.id.mini_mute, "Mute"),
                Pair(R.id.mini_vol_up, "VolumeUp"),
                Pair(R.id.mini_ch_down, "ChannelDown"),
                Pair(R.id.mini_ch_up, "ChannelUp")
            )
            keys.forEachIndexed { index, (viewId, key) ->
                views.setOnClickPendingIntent(
                    viewId,
                    WidgetIntents.broadcast(
                        context, MiniRemoteWidgetProvider::class.java, ACTION_KEY, 9001 + index, EXTRA_KEY, key
                    )
                )
            }
            return views
        }
    }
}
