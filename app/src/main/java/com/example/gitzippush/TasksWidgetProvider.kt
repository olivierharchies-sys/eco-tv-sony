package com.example.gitzippush

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Widget « Suivi des tâches » : les 4 dernières tâches avec leur avancement. */
class TasksWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context, TaskTracker.list(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CLEAR) TaskTracker.clearFinished(context)
    }

    companion object {
        const val ACTION_CLEAR = "com.example.gitzippush.CLEAR_TASKS"

        private val ROWS = intArrayOf(R.id.tw_r1, R.id.tw_r2, R.id.tw_r3, R.id.tw_r4)
        private val TITLES = intArrayOf(R.id.tw_t1, R.id.tw_t2, R.id.tw_t3, R.id.tw_t4)
        private val BARS = intArrayOf(R.id.tw_p1, R.id.tw_p2, R.id.tw_p3, R.id.tw_p4)
        private val DETAILS = intArrayOf(R.id.tw_d1, R.id.tw_d2, R.id.tw_d3, R.id.tw_d4)

        fun refresh(context: Context, tasks: List<TaskItem>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TasksWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget_tasks)
            val running = tasks.count { it.state == TaskTracker.RUNNING }
            views.setTextViewText(
                R.id.tw_title,
                if (running > 0) "Suivi des tâches · $running en cours" else "Suivi des tâches"
            )

            for (i in ROWS.indices) {
                val t = tasks.getOrNull(i)
                if (t == null) {
                    views.setViewVisibility(ROWS[i], if (i == 0) View.VISIBLE else View.GONE)
                    if (i == 0) {
                        views.setTextViewText(TITLES[0], "Aucune tâche")
                        views.setTextColor(TITLES[0], 0xFF98A2B3.toInt())
                        views.setViewVisibility(BARS[0], View.GONE)
                        views.setTextViewText(DETAILS[0], "Les envois et imports apparaissent ici.")
                    }
                    continue
                }
                views.setViewVisibility(ROWS[i], View.VISIBLE)
                val mark = when (t.state) {
                    TaskTracker.RUNNING -> "⏳ "
                    TaskTracker.DONE -> "✓ "
                    else -> "✗ "
                }
                views.setTextViewText(TITLES[i], mark + t.title)
                views.setTextColor(
                    TITLES[i],
                    when (t.state) {
                        TaskTracker.RUNNING -> 0xFFECEFF4.toInt()
                        TaskTracker.DONE -> 0xFF34C77B.toInt()
                        else -> 0xFFFF6B6B.toInt()
                    }
                )
                views.setViewVisibility(BARS[i], if (t.state == TaskTracker.RUNNING) View.VISIBLE else View.GONE)
                views.setProgressBar(BARS[i], 100, t.percent, t.percent <= 0)
                views.setTextViewText(DETAILS[i], t.detail)
            }

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.tw_title, open)
            val clear = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, TasksWidgetProvider::class.java).setAction(ACTION_CLEAR),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.tw_clear, clear)
            manager.updateAppWidget(ids, views)
        }
    }
}
