package com.example.braviaeco

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.net.Inet4Address
import kotlin.concurrent.thread

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

    const val NOTIF_ID = 4108
    private const val CHANNEL = "keep_awake"

    /** Notification du service ; ok = false : décodeur injoignable alors que la TV est allumée. */
    fun notification(ctx: Context, ok: Boolean): Notification {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Décodeur éveillé", NotificationManager.IMPORTANCE_MIN)
            )
            Notification.Builder(ctx, CHANNEL)
        } else {
            Notification.Builder(ctx)
        }
        return builder
            .setSmallIcon(R.drawable.ic_set_top_box)
            .setContentTitle(if (ok) "Décodeur gardé éveillé" else "⚠ Décodeur injoignable")
            .setContentText(if (ok) "Actif tant que la TV est allumée" else "Vérifie le décodeur (Wi-Fi, adresse IP)")
            .setOngoing(true)
            .build()
    }

    /** Échecs consécutifs : la notification passe en alerte à partir de 2, revient à la normale au 1er succès. */
    fun report(ctx: Context, success: Boolean) {
        val app = ctx.applicationContext
        val prev = sp(app).getInt("keep_awake_fails", 0)
        val fails = if (success) 0 else prev + 1
        sp(app).edit().putInt("keep_awake_fails", fails).apply()
        val wasOk = prev < FAIL_LIMIT
        val isOk = fails < FAIL_LIMIT
        if (wasOk != isOk && enabled(app)) {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(NOTIF_ID, notification(app, isOk)) }
        }
    }

    /** false si les derniers envois au décodeur ont échoué alors que la TV était allumée. */
    fun healthy(ctx: Context): Boolean = sp(ctx.applicationContext).getInt("keep_awake_fails", 0) < FAIL_LIMIT

    private const val FAIL_LIMIT = 2
}

/** Relance le maintien éveillé après redémarrage du téléphone ou mise à jour de l'appli. */
class KeepAwakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (KeepAwake.enabled(context)) KeepAwake.apply(context)
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

    override fun onCreate() {
        super.onCreate()
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        KeepAwakeReceiver.schedule(this, 0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        KeepAwakeReceiver.cancel(this)
        super.onDestroy()
    }

    private fun notification(): Notification = KeepAwake.notification(this, KeepAwake.healthy(this))

    private companion object {
        const val ID = KeepAwake.NOTIF_ID
    }
}

/**
 * Minuterie du maintien éveillé. AlarmManager (et non Handler) : continue écran éteint / téléphone
 * en veille. Chaque passage tient le processeur et le Wi-Fi le temps d'envoyer la touche.
 */
class KeepAwakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (!KeepAwake.enabled(app)) return
        schedule(app, INTERVAL_MS)
        val pending = goAsync()
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EcoTv:keepAwake")
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifi = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EcoTv:keepAwake")
        cpu.setReferenceCounted(false)
        wifi?.setReferenceCounted(false)
        cpu.acquire(25_000L)
        runCatching { wifi?.acquire() }
        thread(isDaemon = true, name = "keep-awake") {
            try {
                ping(app)
                // Écran éteint : on ferme la liaison, sinon les « ping » du décodeur réveillent le téléphone.
                if (!pm.isInteractive) AtvRemote.disconnect(AtvDevice.DECODER)
            } finally {
                runCatching { if (wifi?.isHeld == true) wifi.release() }
                runCatching { if (cpu.isHeld) cpu.release() }
                pending.finish()
            }
        }
    }

    /** Un passage : si la TV est allumée, une touche inoffensive au décodeur (nouvel essai si échec). */
    private fun ping(app: Context) {
        if (!Decoder.wifi(app)) return
        if (!onHomeWifi(app)) return
        val tvOn = BraviaClient.getPowerOn(app).getOrNull()
            ?: run { Thread.sleep(3000); BraviaClient.getPowerOn(app).getOrNull() }
            ?: return
        if (!tvOn) {
            KeepAwake.report(app, true)
            return
        }
        if (AtvRemote.sendKey(app, KEY_SHIFT).isSuccess) {
            KeepAwake.report(app, true)
            return
        }
        AtvRemote.disconnect(AtvDevice.DECODER)
        Thread.sleep(3000)
        KeepAwake.report(app, AtvRemote.sendKey(app, KEY_SHIFT).isSuccess)
    }

    /** Téléphone en Wi-Fi, sur le même réseau (/24) que le décodeur. */
    private fun onHomeWifi(app: Context): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        val decoder = AtvPrefs.ip(app).trim()
        val prefix = decoder.substringBeforeLast('.', "")
        if (prefix.isEmpty() || prefix.count { it == '.' } != 2) return true
        val addrs = cm.getLinkProperties(net)?.linkAddresses ?: return true
        val mine = addrs.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        mine.isEmpty() || mine.any { it.substringBeforeLast('.') == prefix }
    }.getOrDefault(true)

    companion object {
        /** Intervalle entre deux touches (Android peut l'allonger à ~9 min en veille profonde). */
        private const val INTERVAL_MS = 4 * 60 * 1000L

        /** KEYCODE_SHIFT_LEFT : ne déclenche aucune action dans les menus ni les applications. */
        private const val KEY_SHIFT = 59

        private fun intent(ctx: Context): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(ctx, 4109, Intent(ctx, KeepAwakeReceiver::class.java), flags)
        }

        fun schedule(ctx: Context, delayMs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = SystemClock.elapsedRealtime() + delayMs
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, intent(ctx))
        }

        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(intent(ctx))
        }
    }
}
