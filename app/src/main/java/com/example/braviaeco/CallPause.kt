package com.example.braviaeco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import java.util.concurrent.Executors

/** Réglage « pause automatique pendant un appel » (appel téléphonique, WhatsApp…). */
object CallPause {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("call_pause", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("call_pause", on).apply()
        apply(ctx)
    }

    /** Démarre ou arrête le service selon le réglage. À appeler quand l'appli est visible. */
    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        val i = Intent(app, CallPauseService::class.java)
        if (enabled(app)) {
            runCatching { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i) }
        } else {
            app.stopService(i)
        }
    }
}

/**
 * Surveille le mode audio du téléphone : dès qu'un appel (opérateur, WhatsApp, etc.) sonne ou démarre,
 * envoie « Pause » à l'appareil qui est à l'écran (TV ou décodeur), puis « Lecture » à la fin de l'appel.
 */
class CallPauseService : Service() {

    private enum class Route { TV, DEVICE }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var audio: AudioManager
    private var inCall = false
    private var calmTicks = 0

    /** Appareil auquel « Pause » a été envoyé (null = rien à reprendre). Touché seulement par [io]. */
    private var paused: Route? = null

    private val poll = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        // Appel en cours à l'arrêt du service : on rend la lecture avant de partir.
        val app = applicationContext
        val route = paused
        if (route != null) Thread { send(app, route, "Play") }.start()
        io.shutdown()
        super.onDestroy()
    }

    private fun check() {
        val busy = audio.mode != AudioManager.MODE_NORMAL
        if (busy) {
            calmTicks = 0
            if (!inCall) {
                inCall = true
                pauseTv()
            }
        } else if (inCall && ++calmTicks >= 3) {
            inCall = false
            resumeTv()
        }
    }

    /** Pause sur la source en cours : entrée HDMI = décodeur, sinon la TV elle-même. Rien si la TV est éteinte. */
    private fun pauseTv() {
        val app = applicationContext
        runCatching {
            io.execute {
                val status = BraviaClient.rawCall(app, "system", "getPowerStatus", JSONArray())
                    .getOrNull()?.optJSONObject(0)?.optString("status")
                if (status != "active") return@execute
                val uri = BraviaClient.currentInputUri(app).getOrNull()
                val route = if (uri != null && uri.startsWith("extInput:", ignoreCase = true)) Route.DEVICE else Route.TV
                if (send(app, route, "Pause").isSuccess) paused = route
            }
        }
    }

    private fun resumeTv() {
        val app = applicationContext
        runCatching {
            io.execute {
                val route = paused ?: return@execute
                paused = null
                send(app, route, "Play")
            }
        }
    }

    private fun send(ctx: Context, route: Route, key: String): Result<Unit> =
        if (route == Route.DEVICE) Decoder.sendKey(ctx, key) else BraviaClient.sendIrcc(ctx, key)

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pause pendant les appels", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_mute)
            .setContentTitle("Pause automatique pendant les appels")
            .setContentText("Active : la lecture reprend à la fin de l'appel")
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val ID = 4107
        const val CHANNEL = "call_pause"
    }
}
