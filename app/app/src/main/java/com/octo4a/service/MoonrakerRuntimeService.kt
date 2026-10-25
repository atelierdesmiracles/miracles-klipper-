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
import com.octo4a.repository.MoonrakerProgress
import com.octo4a.repository.MoonrakerRepository
import com.octo4a.repository.MoonrakerStage
import com.octo4a.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class MoonrakerRuntimeService : LifecycleService() {
    private val moonraker: MoonrakerRepository by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    @Volatile private var startCompleted = false
    @Volatile private var userStopped = false
    private var restartJob: Job? = null
    private var restartAttempts = 0

    companion object {
        const val ACTION_START = "com.octo4a.action.START_MOONRAKER"
        const val ACTION_STOP = "com.octo4a.action.STOP_MOONRAKER"
        private const val CHANNEL_ID = "miracle_moonraker_runtime"
        private const val NOTIFICATION_ID = 3
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        moonraker.progress.asLiveData().observe(this) { progress -> updateNotification(progress) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                userStopped = true
                startCompleted = false
                restartJob?.cancel()
                restartJob = null
                moonraker.stop()
                stopForeground(true)
                stopSelfResult(startId)
            }
            else -> {
                userStopped = false
                startCompleted = false
                restartAttempts = 0
                startForeground(NOTIFICATION_ID, buildNotification("Starting Moonraker"))
                serviceScope.launch {
                    try {
                        moonraker.start()
                        startCompleted = true
                        maybeRestartAfterCrash(moonraker.progress.value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Avoid leaving a partially-started Moonraker process behind.
                        try {
                            moonraker.stop()
                        } catch (_: Exception) {
                            // Preserve the original startup failure state.
                        }
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
        userStopped = true
        restartJob?.cancel()
        restartJob = null
        serviceScope.cancel()
        moonraker.stop()
        super.onDestroy()
    }

    private fun updateNotification(progress: MoonrakerProgress) {
        if (progress.stage in setOf(
                MoonrakerStage.Starting,
                MoonrakerStage.Ready,
                MoonrakerStage.KlipperDisconnected,
                MoonrakerStage.Running,
                MoonrakerStage.ApiUnavailable,
                MoonrakerStage.Stopping,
                MoonrakerStage.Failed
            )
        ) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(progress.message))
        }
        maybeRestartAfterCrash(progress)
    }

    private fun maybeRestartAfterCrash(progress: MoonrakerProgress) {
        if (!startCompleted || userStopped || restartJob != null ||
            progress.stage != MoonrakerStage.Failed ||
            !progress.message.startsWith("Moonraker exited with code ") || restartAttempts >= 3
        ) {
            return
        }

        val attempt = ++restartAttempts
        val delayMs = 10_000L * (1L shl (attempt - 1))
        restartJob = serviceScope.launch {
            delay(delayMs)
            if (!userStopped) {
                try {
                    moonraker.start()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed startup is shown in the Moonraker state and is not retried blindly.
                }
            }
            restartJob = null
            if (!userStopped) maybeRestartAfterCrash(moonraker.progress.value)
        }
    }

    private fun buildNotification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.moonraker_notification_title))
        .setContentText(message)
        .setSmallIcon(R.drawable.ic_print_24px)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openAppPendingIntent())
        .addAction(R.drawable.ic_stop_24px, getString(R.string.moonraker_stop), stopPendingIntent())
        .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 1, intent, flags)
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, MoonrakerRuntimeService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, 3, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.moonraker_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
