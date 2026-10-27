package com.octo4a.service

import android.app.*
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.asLiveData
import com.octo4a.R
import com.octo4a.repository.*
import com.octo4a.serial.VirtualSerialDriver
import com.octo4a.serial.id
import com.octo4a.ui.MainActivity
import com.octo4a.viewmodel.InstallationViewModel
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import java.io.File


// OctoprintService handles foreground service that OctoPrintManager resides in
class OctoPrintService : LifecycleService() {
    private val handlerRepository: OctoPrintHandlerRepository by inject()
    private val bootstrapRepository: BootstrapRepository by inject()
    private val fifoEventRepository: FIFOEventRepository by inject()
    private val extensionsRepository: ExtensionsRepository by inject()
    private val logger: LoggerRepository by inject()
    private val errorHandler: AppErrorHandler by inject()

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var startupJob: Job? = null
    private var receiverRegistered = false
    private var runtimeStarted = false
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        // Constants
        const val LOG_TAG = "Octo4a_Service"

        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "octo4a_notification_channel"

        const val BROADCAST_SERVICE_RECEIVE_ACTION = "com.octo4a.service_receive_event"
        const val BROADCAST_SERVICE_USB_GOT_ACCESS = "com.octo4a.usb_access_received"
        const val EVENT_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val EVENT_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
        const val EXTRA_EVENTDATA = "EXTRA_EVENTDATA"
    }

    val virtualSerialDriver: VirtualSerialDriver by inject()

    // Prepares intent filter for broadcast receiver
    private val intentFilter by lazy {
        val filter = IntentFilter()
        filter.addAction(BROADCAST_SERVICE_RECEIVE_ACTION)
        filter.addAction(BROADCAST_SERVICE_USB_GOT_ACCESS)
        filter.addAction(EVENT_USB_ATTACHED)
        filter.addAction(EVENT_USB_DETACHED)

        filter
    }

    private val notificationBuilder by lazy {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Miracle Klipper setup")
            .setContentText("...")
            .setVibrate(null)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_print_24px)
            .setContentIntent(pendingIntent)
    }

    private fun updateNotificationStatus(status: String) {
        notificationBuilder.setContentText(status)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                EVENT_USB_ATTACHED -> {
                    virtualSerialDriver.updateDevicesList(BROADCAST_SERVICE_USB_GOT_ACCESS)?.apply {
                        handlerRepository.usbAttached(this)
                        verifyPtyRunning()
                    }
                }

                EVENT_USB_DETACHED -> {
                    val detachedDevice = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                    virtualSerialDriver.handleDeviceDetached(detachedDevice)
                    virtualSerialDriver.updateDevicesList(BROADCAST_SERVICE_USB_GOT_ACCESS)
                    handlerRepository.usbDetached()
                }

                BROADCAST_SERVICE_USB_GOT_ACCESS -> {
                    val requestedDevice = virtualSerialDriver.requestedDevice
                    virtualSerialDriver.requestedDevice = null
                    if (intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) == true && requestedDevice != null) {
                        virtualSerialDriver.tryToSelectDevice(requestedDevice)
                    } else {
                        logger.log(this@OctoPrintService) { "USB device permission was denied or the device disappeared" }
                    }
                }
            }

        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(broadcastReceiver, intentFilter)
        receiverRegistered = true

        handlerRepository.serverState.asLiveData().observe(this) {
            verifyPtyRunning()
            updateNotificationStatus(
                when (it) {
                    ServerStatus.Running -> resources.getString(R.string.notification_status_running)
                    ServerStatus.Stopped -> resources.getString(R.string.notification_status_stopped)
                    ServerStatus.DownloadingBootstrap, ServerStatus.ExtractingBootstrap -> resources.getString(R.string.notification_status_installing)
                    else -> resources.getString(R.string.notification_status_starting)
                }
            )
        }
        // The legacy OctoPrint event pipe is intentionally not observed by Miracle Klipper.
    }

    fun verifyPtyRunning() {
        val ptyFile = File(filesDir, "serialpipe")
        if (!ptyFile.exists()) {
            virtualSerialDriver.initializeVSP()
            virtualSerialDriver.handlePtyThread()
        }
    }

    override fun onDestroy() {
        startupJob?.cancel()
        serviceJob.cancel()
        cleanup("OctoPrint", "Stop server") { handlerRepository.stopOctoPrint() }
        cleanup("SSH", "Stop service") { handlerRepository.stopSSH() }
        cleanup("Extensions", "Stop services") { extensionsRepository.stopAllExtensions() }
        if (receiverRegistered) {
            cleanup("Android service", "Unregister USB receiver") { unregisterReceiver(broadcastReceiver) }
            receiverRegistered = false
        }
        cleanup("USB serial", "Close connection") { virtualSerialDriver.shutdown() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundNotification()
        if (!runtimeStarted) {
            runtimeStarted = true
            startupJob = scope.launch {
                try {
                    bootstrapRepository.ensureHomeDirectory()
                    virtualSerialDriver.initializeVSP()
                    virtualSerialDriver.handlePtyThread()
                    handlerRepository.beginInstallation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    errorHandler.report("Android service", "Start server", failure)
                }
            }
        }
        return START_STICKY
    }

    private inline fun cleanup(component: String, operation: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            errorHandler.report(component, operation, failure)
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notificationBuilder.build())
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Miracle Klipper setup",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
