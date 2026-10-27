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
import com.octo4a.repository.KlipperProgress
import com.octo4a.repository.KlipperRepository
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

class KlipperRuntimeService : LifecycleService() {
    private val klipper: KlipperRepository by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    @Volatile private var startCompleted = false
    @Volatile private var userStopped = false
    private var restartJob: Job? = null
    private var restartAttempts = 0

    companion object {
        const val ACTION_START = "com.octo4a.action.START_KLIPPER"
        const val ACTION_STOP = "com.octo4a.action.STOP_KLIPPER"
        private const val CHANNEL_ID = "miracle_klipper_runtime"
        private const val NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        klipper.progress.asLiveData().observe(this) { progress -> updateNotification(progress) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                userStopped = true
                startCompleted = false
                restartJob?.cancel()
                restartJob = null
                klipper.stop()
                stopForeground(true)
                stopSelfResult(startId)
            }
            else -> {
                userStopped = false
                startCompleted = false
                restartAttempts = 0
                startForeground(NOTIFICATION_ID, buildNotification("Starting Klipper"))
                serviceScope.launch {
                    try {
                        klipper.start()
                        startCompleted = true
                        maybeRestartAfterCrash(klipper.progress.value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A start can fail after the child process has been created.
                        // Always ask the repository to terminate any partially-started process.
                        try {
                            klipper.stop()
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
        klipper.stop()
        super.onDestroy()
    }

    private fun updateNotification(progress: KlipperProgress) {
        if (progress.stage == com.octo4a.repository.KlipperStage.Running ||
            progress.stage == com.octo4a.repository.KlipperStage.Starting ||
            progress.stage == com.octo4a.repository.KlipperStage.Stopping ||
            progress.stage == com.octo4a.repository.KlipperStage.Failed
        ) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(progress.message))
        }
        maybeRestartAfterCrash(progress)
    }

    private fun maybeRestartAfterCrash(progress: KlipperProgress) {
        if (!startCompleted || userStopped || restartJob != null ||
            progress.stage != com.octo4a.repository.KlipperStage.Failed ||
            !progress.message.startsWith("Klipper exited with code ") || restartAttempts >= 3
        ) {
            return
        }

        val attempt = ++restartAttempts
        val delayMs = 10_000L * (1L shl (attempt - 1))
        restartJob = serviceScope.launch {
            delay(delayMs)
            if (!userStopped) {
                try {
                    klipper.start()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed startup is shown in the Klipper state and is not retried blindly.
                }
            }
            restartJob = null
            if (!userStopped) maybeRestartAfterCrash(klipper.progress.value)
        }
    }

    private fun buildNotification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.klipper_notification_title))
        .setContentText(message)
        .setSmallIcon(R.drawable.ic_print_24px)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openAppPendingIntent())
        .addAction(
            R.drawable.ic_stop_24px,
            getString(R.string.klipper_stop),
            stopPendingIntent()
        )
        .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, KlipperRuntimeService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, 2, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.klipper_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
