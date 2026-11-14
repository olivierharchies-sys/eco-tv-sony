package com.example.gitzippush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Service au premier plan : garde l'appli active pendant un envoi ou une attente de compilation
 * (même écran éteint ou appli en arrière-plan) et affiche la progression dans une notification.
 */
class TaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tasks = TaskTracker.list(this)
        val notification = buildNotification(this, tasks)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (tasks.none { it.state == TaskTracker.RUNNING }) {
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "taches"
        private const val NOTIF_ID = 42

        fun sync(ctx: Context, tasks: List<TaskItem>) {
            val intent = Intent(ctx, TaskService::class.java)
            if (tasks.any { it.state == TaskTracker.RUNNING }) {
                try {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
                } catch (e: Exception) {
                    // démarrage refusé (appli en arrière-plan) : l'envoi continue sans service
                }
            } else {
                ctx.stopService(intent)
            }
        }

        private fun buildNotification(ctx: Context, tasks: List<TaskItem>): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder: Notification.Builder
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Suivi des tâches", NotificationManager.IMPORTANCE_LOW)
                )
                builder = Notification.Builder(ctx, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                builder = Notification.Builder(ctx)
            }
            val running = tasks.firstOrNull { it.state == TaskTracker.RUNNING }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(running?.title ?: "Envoi GitHub")
                .setContentText(running?.detail ?: "Terminé")
                .setOngoing(running != null)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
            if (running != null) builder.setProgress(100, running.percent, running.percent <= 0)
            return builder.build()
        }
    }
}
