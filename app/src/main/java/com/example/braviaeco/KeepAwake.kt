package com.example.braviaeco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/** Réglage « garder le décodeur éveillé tant que la TV est allumée ». */
object KeepAwake {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("keep_awake", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("keep_awake", on).apply()
        apply(ctx)
    }

    /** Démarre ou arrête le service selon le réglage. À appeler quand l'appli est visible. */
    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        val i = Intent(app, KeepAwakeService::class.java)
        if (enabled(app)) {
            runCatching { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i) }
        } else {
            app.stopService(i)
        }
    }
}

/**
 * Empêche la mise en veille automatique du décodeur : tant que la TV est allumée, envoie à intervalles
 * réguliers une touche sans effet (Maj) au décodeur, ce qui compte comme une action de l'utilisateur.
 * Nécessite le décodeur associé en Wi-Fi direct. Quand la TV est éteinte ou injoignable, ne fait rien :
 * le décodeur peut alors se mettre en veille normalement.
 */
class KeepAwakeService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val tick = object : Runnable {
        override fun run() {
            runCatching { io.execute { ping() } }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        io.shutdown()
        super.onDestroy()
    }

    /** Un passage : si la TV est allumée, une touche inoffensive au décodeur. */
    private fun ping() {
        val app = applicationContext
        if (!Decoder.wifi(app)) return
        val tvOn = BraviaClient.getPowerOn(app).getOrNull() ?: return
        if (!tvOn) return
        AtvRemote.sendKey(app, KEY_SHIFT)
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Décodeur éveillé", NotificationManager.IMPORTANCE_MIN)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_set_top_box)
            .setContentTitle("Décodeur gardé éveillé")
            .setContentText("Actif tant que la TV est allumée")
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val ID = 4108
        const val CHANNEL = "keep_awake"

        /** Intervalle entre deux touches (la veille automatique des décodeurs se compte en heures). */
        const val INTERVAL_MS = 5 * 60 * 1000L

        /** KEYCODE_SHIFT_LEFT : ne déclenche aucune action dans les menus ni les applications. */
        const val KEY_SHIFT = 59
    }
}
