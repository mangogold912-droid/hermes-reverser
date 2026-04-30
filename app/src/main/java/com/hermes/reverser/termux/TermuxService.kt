package com.hermes.reverser.termux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Termux 백그라운드 서비스
 */
class TermuxService : Service() {

    companion object {
        private const val TAG = "TermuxService"
        private const val CHANNEL_ID = "termux_service"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "TermuxService started")
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val command = intent?.getStringExtra("command")
        if (command != null) {
            Log.i(TAG, "Executing: " + command)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "TermuxService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Termux Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Reverser")
            .setContentText("Termux integration running...")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .build()
    }
}
