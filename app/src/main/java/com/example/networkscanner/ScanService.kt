package com.example.networkscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Foreground service that keeps scanning the Wi-Fi network and alerts on new devices. */
class ScanService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null
    private var known: Set<String>? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) {
            running = true
            worker = Thread({ loop() }, "wifi-scan").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        ScanStore.state = ScanStore.State.STOPPED
        ScanStore.changed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop() {
        try {
            while (running) {
                val wifi = NetworkScanner.currentWifi(this)
                if (wifi == null) {
                    ScanStore.state = ScanStore.State.NO_WIFI
                    ScanStore.myIp = null
                    ScanStore.changed()
                    pause(5_000)
                    continue
                }
                ScanStore.myIp = wifi.ip.hostAddress
                ScanStore.gateway = wifi.gateway
                ScanStore.state = ScanStore.State.SCANNING
                ScanStore.progress = 0
                ScanStore.changed()

                val result = NetworkScanner.scan(
                    wifi,
                    { p -> ScanStore.progress = p; ScanStore.changed() },
                    { !running }
                )
                if (!running) break

                ScanStore.devices = result
                ScanStore.state = ScanStore.State.IDLE
                ScanStore.changed()
                alertNew(result)
                pause(SCAN_INTERVAL_MS)
            }
        } catch (e: InterruptedException) {
            // service is stopping
        }
    }

    private fun pause(ms: Long) {
        var left = ms
        while (running && left > 0) {
            Thread.sleep(minOf(500L, left))
            left -= 500L
        }
    }

    /** Notify the first time a device is seen; the first scan just sets the baseline. */
    private fun alertNew(devices: List<ScanStore.Device>) {
        val seen = known
        known = (seen ?: emptySet()) + devices.map { it.ip }
        if (seen == null) return

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "New devices", NotificationManager.IMPORTANCE_DEFAULT)
        )
        devices.filter { it.ip !in seen }.take(3).forEach { d ->
            nm.notify(
                d.ip.hashCode(),
                Notification.Builder(this, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_wifi)
                    .setContentTitle("New device on Wi-Fi")
                    .setContentText(listOfNotNull(d.ip, d.host).joinToString("  "))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wi-Fi scanning", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("Encompass")
            .setContentText("Scanning your Wi-Fi network")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 30_000L
        private const val CHANNEL_ID = "wifi_scan"
        private const val ALERT_CHANNEL_ID = "new_devices"
        private const val NOTIFICATION_ID = 1
    }
}
