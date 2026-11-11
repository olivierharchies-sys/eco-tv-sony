package com.example.braviaeco

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Minuteur d'extinction de la TV, tenu par le téléphone (alarme Android) : il fonctionne même
 * si l'appli est fermée. Il faut que le téléphone soit sur le même réseau que la TV à l'échéance.
 */
object SleepTimer {
    private const val ACTION = "com.example.braviaeco.SLEEP_TIMER"
    private const val K_AT = "sleep_timer_at"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    private fun pending(ctx: Context): PendingIntent {
        val intent = Intent(ctx, SleepReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            ctx, 4242, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun start(ctx: Context, minutes: Int) {
        val app = ctx.applicationContext
        val at = System.currentTimeMillis() + minutes * 60_000L
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(app))
        } catch (e: SecurityException) {
            // Alarme exacte non autorisée : une alarme moins précise (quelques minutes d'écart possibles).
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(app))
        }
        sp(app).edit().putLong(K_AT, at).apply()
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(app))
        clear(app)
    }

    fun clear(ctx: Context) {
        sp(ctx.applicationContext).edit().remove(K_AT).apply()
    }

    /** Minutes restantes (arrondies au-dessus), ou null si aucun minuteur n'est en cours. */
    fun remainingMinutes(ctx: Context): Int? {
        val at = sp(ctx.applicationContext).getLong(K_AT, 0L)
        val left = at - System.currentTimeMillis()
        return if (at == 0L || left <= 0) null else ((left + 59_999L) / 60_000L).toInt()
    }
}

/** Éteint la TV quand le minuteur arrive à échéance. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pr = goAsync()
        thread {
            try {
                BraviaClient.setPower(app, false)
                Ui.toast(app, "Minuteur : TV éteinte")
            } finally {
                SleepTimer.clear(app)
                pr.finish()
            }
        }
    }
}
