package com.example.gitzippush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/** Notifications de fin de compilation et de nettoyage, avec actions. */
object Notifier {
    private const val CHANNEL = "compilations"

    const val EXTRA_ACTION = "eg_action"
    const val EXTRA_REPO = "eg_repo"
    const val EXTRA_SHA = "eg_sha"
    const val EXTRA_BRANCH = "eg_branch"
    const val EXTRA_FILE = "eg_file"
    const val EXTRA_RUN = "eg_run"
    const val ACT_APK = "apk"
    const val ACT_REPORT = "report"
    const val ACT_RERUN = "rerun"
    const val ACT_REPORTS = "reports"
    const val ACT_FILES = "files"

    private fun builder(ctx: Context): Notification.Builder {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Résultats des compilations", NotificationManager.IMPORTANCE_DEFAULT)
            )
            Notification.Builder(ctx, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }
    }

    private fun pending(ctx: Context, code: Int, extras: Intent.() -> Unit): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        i.extras()
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (e: Exception) {
            // notifications refusées
        }
    }

    fun buildSuccess(ctx: Context, e: ArchiveEntry) {
        val id = e.sha.hashCode()
        val n = builder(ctx).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("✓ Compilation réussie : ${e.repo.substringAfter('/')}")
            .setContentText("Commit ${e.sha.take(7)} · version gardée dans l'historique")
            .setAutoCancel(true)
            .setContentIntent(pending(ctx, id) { putExtra(EXTRA_ACTION, ACT_FILES) })
            .addAction(
                Notification.Action.Builder(
                    null, "Installer l'APK",
                    pending(ctx, id + 1) {
                        putExtra(EXTRA_ACTION, ACT_APK).putExtra(EXTRA_REPO, e.repo)
                            .putExtra(EXTRA_SHA, e.sha).putExtra(EXTRA_BRANCH, e.branch)
                    }
                ).build()
            )
            .build()
        post(ctx, id, n)
    }

    fun buildFailed(ctx: Context, e: ArchiveEntry, report: File?, runId: Long, detail: String) {
        val id = e.sha.hashCode()
        val b = builder(ctx).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("✗ Compilation échouée : ${e.repo.substringAfter('/')}")
            .setContentText(detail + if (report != null) " · rapport prêt" else "")
            .setAutoCancel(true)
            .setContentIntent(pending(ctx, id) { putExtra(EXTRA_ACTION, ACT_REPORTS) })
        if (report != null) {
            b.addAction(
                Notification.Action.Builder(
                    null, "Envoyer le rapport à Claude",
                    pending(ctx, id + 2) { putExtra(EXTRA_ACTION, ACT_REPORT).putExtra(EXTRA_FILE, report.absolutePath) }
                ).build()
            )
        }
        if (runId > 0) {
            b.addAction(
                Notification.Action.Builder(
                    null, "Relancer",
                    pending(ctx, id + 3) {
                        putExtra(EXTRA_ACTION, ACT_RERUN).putExtra(EXTRA_REPO, e.repo).putExtra(EXTRA_RUN, runId)
                    }
                ).build()
            )
        }
        post(ctx, id, b.build())
    }

    fun simple(ctx: Context, id: Int, title: String, text: String) {
        val n = builder(ctx).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setContentIntent(pending(ctx, id) { })
            .build()
        post(ctx, id, n)
    }
}
