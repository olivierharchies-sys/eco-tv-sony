package com.example.braviaeco

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import kotlin.concurrent.thread

class EcoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_SET -> {
                val mode = EcoMode.fromApi(intent.getStringExtra(EXTRA_MODE)) ?: return
                runInBackground(context) {
                    val result = BraviaClient.setMode(context, mode)
                    toast(context, result.fold(
                        onSuccess = { "TV : éco ${mode.label}" },
                        onFailure = { it.message ?: "Échec" }
                    ))
                }
            }
            ACTION_REFRESH -> {
                runInBackground(context) {
                    val result = BraviaClient.getMode(context)
                    result.exceptionOrNull()?.let { toast(context, it.message ?: "Échec") }
                }
            }
        }
    }

    private fun runInBackground(context: Context, work: () -> Unit) {
        val pending = goAsync()
        thread {
            try {
                work()
            } finally {
                refreshAll(context)
                pending.finish()
            }
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_SET = "com.example.braviaeco.SET_MODE"
        const val ACTION_REFRESH = "com.example.braviaeco.REFRESH"
        const val EXTRA_MODE = "mode"

        private val BUTTONS = mapOf(
            EcoMode.OFF to R.id.btn_off,
            EcoMode.LOW to R.id.btn_low,
            EcoMode.HIGH to R.id.btn_high,
            EcoMode.PICTURE_OFF to R.id.btn_picture_off
        )

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EcoWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_eco)
            val current = Prefs.lastMode(context)

            views.setTextViewText(
                R.id.widget_title,
                "Éco TV · " + (current?.label ?: "?")
            )
            views.setOnClickPendingIntent(R.id.widget_title, broadcast(context, ACTION_REFRESH, null))

            BUTTONS.forEach { (mode, viewId) ->
                views.setInt(
                    viewId,
                    "setBackgroundResource",
                    if (mode == current) R.drawable.btn_active else R.drawable.btn_idle
                )
                views.setOnClickPendingIntent(viewId, broadcast(context, ACTION_SET, mode))
            }
            return views
        }

        private fun broadcast(context: Context, action: String, mode: EcoMode?): PendingIntent {
            val intent = Intent(context, EcoWidgetProvider::class.java).setAction(action)
            if (mode != null) intent.putExtra(EXTRA_MODE, mode.api)
            // requestCode distinct par action/mode pour éviter que les PendingIntent s'écrasent
            val requestCode = (action.hashCode() * 31) + (mode?.ordinal ?: 99)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
