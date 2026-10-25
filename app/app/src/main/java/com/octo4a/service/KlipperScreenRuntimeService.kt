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
import com.octo4a.repository.KlipperScreenProgress
import com.octo4a.repository.KlipperScreenRepository
import com.octo4a.repository.KlipperScreenStage
import com.octo4a.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class KlipperScreenRuntimeService : LifecycleService() {
    private val klipperScreen: KlipperScreenRepository by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    companion object {
        const val ACTION_START = "com.octo4a.action.START_KLIPPERSCREEN"
        const val ACTION_STOP = "com.octo4a.action.STOP_KLIPPERSCREEN"
        const val EXTRA_DISPLAY_HOST = "com.octo4a.extra.KLIPPERSCREEN_DISPLAY_HOST"
        private const val CHANNEL_ID = "miracle_klipperscreen_runtime"
        private const val NOTIFICATION_ID = 5
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        klipperScreen.progress.asLiveData().observe(this) { progress -> updateNotification(progress) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                klipperScreen.stop()
                stopForeground(true)
                stopSelfResult(startId)
            }
            else -> {
                val displayHost = intent?.getStringExtra(EXTRA_DISPLAY_HOST).orEmpty()
                startForeground(NOTIFICATION_ID, buildNotification("Starting KlipperScreen"))
                serviceScope.launch {
                    try {
                        klipperScreen.start(displayHost)
                        // Quand KlipperScreen se termine (arrêt, quitté ou plantage), on libère le service.
                        klipperScreen.progress.first {
                            it.stage == KlipperScreenStage.Stopped || it.stage == KlipperScreenStage.Failed
                        }
                        withContext(Dispatchers.Main) {
                            stopForeground(true)
                            stopSelf()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            stopForeground(true)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        klipperScreen.stop()
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun updateNotification(progress: KlipperScreenProgress) {
        if (progress.stage in setOf(
                KlipperScreenStage.Starting,
                KlipperScreenStage.Running,
                KlipperScreenStage.Stopping,
                KlipperScreenStage.Failed
            )
        ) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(progress.message))
        }
    }

    private fun buildNotification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.klipperscreen_notification_title))
        .setContentText(message)
        .setSmallIcon(R.drawable.ic_print_24px)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openAppPendingIntent())
        .addAction(R.drawable.ic_stop_24px, getString(R.string.klipperscreen_stop), stopPendingIntent())
        .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 5, intent, flags)
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, KlipperScreenRuntimeService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, 5, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.klipperscreen_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
