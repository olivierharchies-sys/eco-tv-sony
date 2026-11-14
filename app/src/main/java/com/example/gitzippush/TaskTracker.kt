package com.example.gitzippush

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TaskItem(
    val id: String,
    val title: String,
    var state: Int,
    var percent: Int,
    var detail: String,
    val startedAt: Long,
    var endedAt: Long
)

/**
 * Suivi des tâches (envois, imports, récupération d'APK) : alimente le widget et la notification.
 * L'état est gardé en mémoire et enregistré dans les préférences pour survivre à un redémarrage de l'appli.
 */
object TaskTracker {
    const val RUNNING = 0
    const val DONE = 1
    const val ERROR = 2
    private const val MAX = 12

    private val lock = Any()
    private var items: MutableList<TaskItem>? = null
    private var lastSync = 0L

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("envoi_github_tasks", Context.MODE_PRIVATE)

    private fun load(ctx: Context): MutableList<TaskItem> {
        items?.let { return it }
        val list = ArrayList<TaskItem>()
        try {
            val arr = JSONArray(prefs(ctx).getString("tasks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var state = o.optInt("state")
                var detail = o.optString("detail")
                if (state == RUNNING) {
                    // tâche d'une session précédente : l'appli a été fermée pendant l'opération
                    state = ERROR
                    detail = "Interrompue (appli fermée)"
                }
                list.add(
                    TaskItem(
                        o.optString("id"), o.optString("title"), state, o.optInt("percent"), detail,
                        o.optLong("start"), o.optLong("end")
                    )
                )
            }
        } catch (e: Exception) {
            list.clear()
        }
        items = list
        return list
    }

    private fun save(ctx: Context, list: List<TaskItem>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject().put("id", t.id).put("title", t.title).put("state", t.state)
                    .put("percent", t.percent).put("detail", t.detail).put("start", t.startedAt).put("end", t.endedAt)
            )
        }
        prefs(ctx).edit().putString("tasks", arr.toString()).apply()
    }

    /** Tâches, la plus récente d'abord. */
    fun list(ctx: Context): List<TaskItem> = synchronized(lock) { load(ctx).toList() }

    fun runningCount(ctx: Context): Int = synchronized(lock) { load(ctx).count { it.state == RUNNING } }

    fun start(ctx: Context, title: String): String {
        val id = System.nanoTime().toString()
        synchronized(lock) {
            val list = load(ctx)
            list.add(0, TaskItem(id, title, RUNNING, 0, "Démarrage…", System.currentTimeMillis(), 0L))
            while (list.size > MAX) list.removeAt(list.size - 1)
        }
        sync(ctx, true)
        return id
    }

    fun progress(ctx: Context, id: String, percent: Int?, detail: String?) {
        synchronized(lock) {
            val t = load(ctx).firstOrNull { it.id == id } ?: return
            if (percent != null) t.percent = percent.coerceIn(0, 100)
            if (detail != null) t.detail = detail
        }
        sync(ctx, false)
    }

    fun finish(ctx: Context, id: String, ok: Boolean, detail: String) {
        synchronized(lock) {
            val t = load(ctx).firstOrNull { it.id == id } ?: return
            t.state = if (ok) DONE else ERROR
            if (ok) t.percent = 100
            t.detail = detail
            t.endedAt = System.currentTimeMillis()
        }
        sync(ctx, true)
    }

    fun clearFinished(ctx: Context) {
        synchronized(lock) { load(ctx).removeAll { it.state != RUNNING } }
        sync(ctx, true)
    }

    /** Met à jour le widget et la notification (au plus ~2 fois par seconde pour les simples progressions). */
    private fun sync(ctx: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        val snapshot: List<TaskItem>
        synchronized(lock) {
            if (!force && now - lastSync < 600) return
            lastSync = now
            snapshot = load(ctx).toList()
            save(ctx, snapshot)
        }
        try {
            TasksWidgetProvider.refresh(ctx.applicationContext, snapshot)
            TaskService.sync(ctx.applicationContext, snapshot)
        } catch (e: Exception) {
            // le suivi ne doit jamais faire échouer une tâche
        }
    }
}

/** Raccourci pour une tâche en cours. */
class TaskHandle(private val ctx: Context, title: String) {
    private val id = TaskTracker.start(ctx, title)
    fun progress(percent: Int?, detail: String? = null) = TaskTracker.progress(ctx, id, percent, detail)
    fun done(detail: String) = TaskTracker.finish(ctx, id, true, detail)
    fun fail(detail: String) = TaskTracker.finish(ctx, id, false, detail)
}
