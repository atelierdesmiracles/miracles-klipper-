package com.octo4a.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.asLiveData
import com.octo4a.R
import com.octo4a.repository.KlipperWebInterfaceRepository
import com.octo4a.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class KlipperWebInterfaceRuntimeService : LifecycleService() {
    private val webInterface: KlipperWebInterfaceRepository by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    companion object {
        const val ACTION_START = "com.octo4a.action.START_KLIPPER_WEB_INTERFACE"
        const val ACTION_RESTART = "com.octo4a.action.RESTART_KLIPPER_WEB_INTERFACE"
        const val ACTION_STOP = "com.octo4a.action.STOP_KLIPPER_WEB_INTERFACE"
        private const val CHANNEL_ID = "miracle_klipper_web"
        private const val NOTIFICATION_ID = 4
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        webInterface.progress.asLiveData().observe(this) { progress ->
            notificationManager.notify(NOTIFICATION_ID, buildNotification(progress.message))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                startForeground(NOTIFICATION_ID, buildNotification("Stopping Nginx"))
                serviceScope.launch {
                    try {
                        webInterface.stop()
                    } finally {
                        withContext(Dispatchers.Main) {
                            stopForeground(true)
                            stopSelfResult(startId)
                        }
                    }
                }
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting the Klipper web interface"))
                serviceScope.launch {
                    try {
                        webInterface.start()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Clean up partially-started Nginx processes before reporting failure.
                        try {
                            webInterface.abort()
                        } catch (_: Exception) {
                            // Keep the original startup error visible.
                        }
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification(error.message ?: "Could not start the Klipper web interface")
                        )
                        withContext(Dispatchers.Main) {
                            stopForeground(true)
                            stopSelfResult(startId)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        webInterface.abort()
        super.onDestroy()
    }

    private fun buildNotification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.klipper_web_notification_title))
        .setContentText(message)
        .setSmallIcon(R.drawable.ic_print_24px)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openAppPendingIntent())
        .addAction(R.drawable.ic_stop_24px, getString(R.string.klipper_web_stop), stopPendingIntent())
        .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 4, intent, flags)
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, KlipperWebInterfaceRuntimeService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, 4, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.klipper_web_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
