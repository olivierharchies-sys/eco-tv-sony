package com.example.gitzippush

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

/** Lance l'installation d'un APK avec le programme d'installation d'Android (confirmation demandée à l'utilisateur). */
object ApkInstaller {
    const val ACTION_STATUS = "com.example.gitzippush.INSTALL_STATUS"

    /** Android 8+ : l'utilisateur doit autoriser cette appli à installer des applis. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Écran de réglages « Installer des applis inconnues » pour cette appli. */
    fun permissionIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        )

    /** Version déjà installée si elle est >= celle de l'APK (même paquet), sinon null. */
    @Suppress("DEPRECATION")
    fun alreadyInstalled(context: Context, apk: File): String? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return null
        val cur = try {
            pm.getPackageInfo(info.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        fun code(p: android.content.pm.PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else p.versionCode.toLong()
        return if (code(cur) >= code(info)) "${cur.versionName} (${code(cur)})" else null
    }

    /** À appeler hors du thread principal. Lève une exception si la session d'installation échoue. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            FileInputStream(apk).use { input ->
                session.openWrite("package", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}

/** Reçoit le résultat de l'installation : ouvre la fenêtre de confirmation d'Android, puis affiche le résultat. */
class InstallReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "Installation terminée.", Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(context, "Installation non terminée : $message", Toast.LENGTH_LONG).show()
        }
    }
}
