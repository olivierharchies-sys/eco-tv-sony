package com.example.braviaeco

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Mesure du signal Bluetooth des TV (Classic + BLE). Chaque TV est associée une fois à un appareil
 * Bluetooth (adresse + nom : le nom sert de repli si la TV change d'adresse BLE).
 */
object BtRoom {

    /** Un appareil vu pendant un scan. */
    data class Seen(val address: String, val name: String, val rssi: Int)

    private const val SCAN_MS = 10_000L
    /** Mesures plus vieilles que ça : ignorées. */
    private const val MAX_AGE_MS = 120_000L
    /** Pas de nouveau scan automatique si le dernier a moins de ça. */
    private const val MIN_GAP_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Pair<Int, Long>>() // clé : adresse ou "name:<nom>"
    private var scanning = false
    private var lastScan = 0L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isOn(ctx: Context): Boolean = runCatching { adapter(ctx)?.isEnabled == true }.getOrDefault(false)

    /** Permissions nécessaires au scan (en plus de la localisation, déjà demandée pour le Wi-Fi). */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermission(ctx: Context): Boolean =
        permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun address(ctx: Context, device: String): String = sp(ctx).getString("bt_addr_$device", "") ?: ""
    fun name(ctx: Context, device: String): String = sp(ctx).getString("bt_name_$device", "") ?: ""
    fun associated(ctx: Context, device: String): Boolean = address(ctx, device).isNotEmpty()

    fun associate(ctx: Context, device: String, seen: Seen) {
        sp(ctx).edit().putString("bt_addr_$device", seen.address).putString("bt_name_$device", seen.name)
            .remove("room_last").apply()
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove("bt_addr_${Prefs.DEVICE_SONY}").remove("bt_name_${Prefs.DEVICE_SONY}")
            .remove("bt_addr_${Prefs.DEVICE_EDENWOOD}").remove("bt_name_${Prefs.DEVICE_EDENWOOD}")
            .remove("room_last").apply()
    }

    /** Dernier signal (dBm) mesuré pour une TV, ou null si non vue récemment. */
    fun level(ctx: Context, device: String): Int? {
        val now = SystemClock.elapsedRealtime()
        val keys = listOfNotNull(
            address(ctx, device).takeIf { it.isNotEmpty() },
            name(ctx, device).takeIf { it.isNotEmpty() }?.let { "name:$it" }
        )
        return keys.mapNotNull { k -> cache[k]?.takeIf { now - it.second <= MAX_AGE_MS }?.first }.maxOrNull()
    }

    /** Scan automatique (respecte un délai minimal entre deux scans). onDone : thread principal. */
    fun refresh(ctx: Context, onDone: () -> Unit) {
        if (scanning || (lastScan != 0L && SystemClock.elapsedRealtime() - lastScan < MIN_GAP_MS)) return
        // Arrêt anticipé dès que les deux TV associées ont été vues.
        val wanted = listOf(Prefs.DEVICE_SONY, Prefs.DEVICE_EDENWOOD).filter { associated(ctx, it) }
            .map { address(ctx, it) to name(ctx, it) }
        scan(ctx, until = { seen ->
            wanted.all { (addr, name) -> seen.any { it.address == addr || (name.isNotEmpty() && it.name == name) } }
        }) { onDone() }
    }

    /** Démarrage de l'appli : le prochain [refresh] scanne même si un scan récent existe. */
    fun forceNext() {
        lastScan = 0L
    }

    /**
     * Scan Classic + BLE pendant ~10 s. onDone reçoit les appareils vus (meilleur signal),
     * triés du plus fort au plus faible, sur le thread principal.
     */
    @SuppressLint("MissingPermission")
    fun scan(ctx: Context, until: ((Collection<Seen>) -> Boolean)? = null, onDone: (List<Seen>) -> Unit) {
        val app = ctx.applicationContext
        val bt = adapter(app)
        if (scanning || bt == null || !isOn(app) || !hasPermission(app)) {
            main.post { onDone(emptyList()) }
            return
        }
        scanning = true
        val found = HashMap<String, Seen>()
        var finish: () -> Unit = {}

        fun record(device: BluetoothDevice?, rssi: Int, fallbackName: String?) {
            if (device == null) return
            val addr = device.address?.uppercase() ?: return
            val name = runCatching { device.name }.getOrNull() ?: fallbackName ?: ""
            val prev = found[addr]
            if (prev == null || rssi > prev.rssi) found[addr] = Seen(addr, name.ifEmpty { prev?.name ?: "" }, rssi)
            if (until != null && runCatching { until(found.values) }.getOrDefault(false)) main.post { finish() }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != BluetoothDevice.ACTION_FOUND) return
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                if (rssi != Short.MIN_VALUE.toInt()) record(d, rssi, i.getStringExtra(BluetoothDevice.EXTRA_NAME))
            }
        }
        val le = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                record(r.device, r.rssi, r.scanRecord?.deviceName)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            }
        }
        runCatching { bt.cancelDiscovery(); bt.startDiscovery() }
        val scanner = runCatching { bt.bluetoothLeScanner }.getOrNull()
        runCatching { scanner?.startScan(le) }

        var done = false
        finish = finish@{
            if (done) return@finish
            done = true
            runCatching { bt.cancelDiscovery() }
            runCatching { scanner?.stopScan(le) }
            runCatching { app.unregisterReceiver(receiver) }
            val now = SystemClock.elapsedRealtime()
            found.values.forEach { s ->
                cache[s.address] = s.rssi to now
                if (s.name.isNotEmpty()) {
                    val k = "name:${s.name}"
                    val old = cache[k]
                    if (old == null || old.second != now || s.rssi > old.first) cache[k] = s.rssi to now
                }
            }
            lastScan = now
            scanning = false
            onDone(found.values.sortedByDescending { it.rssi })
        }
        main.postDelayed({ finish() }, SCAN_MS)
    }
}
