package com.rift.core.scanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rift.MainActivity
import com.rift.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Foreground service that keeps the scan session alive while the user walks.
 * Without this service, Android will aggressively kill the app when the screen
 * is off, interrupting the scan.
 */
@AndroidEntryPoint
class ScanForegroundService : Service() {

    @Inject
    lateinit var wifiScanEngine: WifiScanEngine

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "rift_scan"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rift.START_SCAN"
        const val ACTION_STOP = "com.rift.STOP_SCAN"

        fun startIntent(context: Context) = Intent(context, ScanForegroundService::class.java).apply {
            action = ACTION_START
        }

        fun stopIntent(context: Context) = Intent(context, ScanForegroundService::class.java).apply {
            action = ACTION_STOP
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScanForegroundService = this@ScanForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Scanning WiFi..."))
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val stopIntent = Intent(this, ScanForegroundService::class.java).apply {
            action = ACTION_STOP
        }.let {
            PendingIntent.getService(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RIFT")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_wifi_scan)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WiFi Scan Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows scan progress while walking"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
