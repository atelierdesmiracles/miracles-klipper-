package com.octo4a.serial

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.hoho.android.usbserial.driver.*
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.octo4a.R
import com.octo4a.repository.LoggerRepository
import com.octo4a.repository.OctoPrintHandlerRepository
import com.octo4a.service.OctoPrintService
import com.octo4a.utils.preferences.MainPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.Exception
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class SerialDriverClass {
    PROLIFIC,
    CDC,
    FTDI,
    CH341,
    CP21XX,
    UNKNOWN
}

data class ConnectedUsbDevice(val deviceName: String, val productId: Int, val vendorId: Int, var driverClass: SerialDriverClass, var isSelected: Boolean, val autoDetect: Boolean, val device: UsbDevice)

fun ConnectedUsbDevice.createDriver(): UsbSerialDriver? {
    return when (driverClass) {
        SerialDriverClass.CP21XX -> Cp21xxSerialDriver(device)
        SerialDriverClass.FTDI -> FtdiSerialDriver(device)
        SerialDriverClass.PROLIFIC -> ProlificSerialDriver(device)
        SerialDriverClass.CH341 -> Ch34xSerialDriver(device)
        SerialDriverClass.CDC -> CdcAcmSerialDriver(device)
        SerialDriverClass.UNKNOWN -> return null
    }
}

fun ConnectedUsbDevice.id(): String {
    return vendorId.toString() + productId.toString()
}

class VirtualSerialDriver(val context: Context, private val prefs: MainPreferences, private val octoPrintHandler: OctoPrintHandlerRepository, private val logger: LoggerRepository): VSPListener, SerialInputOutputManager.Listener {
    val pty by lazy { VSPPty() }

    private val usbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    var selectedDevice: UsbSerialDriver? = null
    var port: UsbSerialPort? = null
    var connection: UsbDeviceConnection? = null
    var serialInputManager: SerialInputOutputManager? = null
    var currentBaudrate = -1
    var ptyThread: Thread? = null
    @Volatile private var ptyThreadStarted = false
    @Volatile private var ptyStartCancelled = false
    var requestedDevice: ConnectedUsbDevice? = null
    var connectedDevices = MutableStateFlow(listOf<ConnectedUsbDevice>())
    @Volatile private var serialExecutor: ExecutorService? = null

    companion object {
        val usbPermissionRequestCode = 2212
    }

    fun initializeVSP() {
        pty.setVSPListener(this)
    }

    @Synchronized
    fun handlePtyThread() {
        if (ptyThreadStarted || ptyThread?.isAlive == true) return

        ptyStartCancelled = false
        ptyThread = Thread({
            synchronized(this@VirtualSerialDriver) {
                if (ptyStartCancelled) return@synchronized
                try {
                    pty.runPtyThread(context.filesDir.absolutePath)
                    ptyThreadStarted = true
                } catch (failure: Exception) {
                    ptyThreadStarted = false
                    logger.logError(this, "Could not start the USB pseudo-terminal", failure)
                }
            }
        }, "octo4a-pty-controller").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stopPtyThread() {
        ptyStartCancelled = true
        if (ptyThreadStarted) {
            pty.cancelPtyThread()
            ptyThreadStarted = false
        }
        ptyThread?.interrupt()
        ptyThread = null
    }

    /**
     * Close the active serial session without forgetting the selected USB device.
     * Klipper can reopen the port after a baud-rate change or recoverable I/O error.
     */
    @Synchronized
    private fun closeSerialSession() {
        serialInputManager?.stop()
        serialInputManager = null
        try {
            port?.close()
        } catch (failure: Exception) {
            logger.logError(this, "Could not close the USB serial port", failure)
        }
        try {
            connection?.close()
        } catch (failure: Exception) {
            logger.logError(this, "Could not close the USB device connection", failure)
        }
        port = null
        connection = null
        currentBaudrate = -1
    }

    @Synchronized
    fun handleDeviceDetached(detachedDevice: UsbDevice? = null) {
        val activeDevice = selectedDevice?.device
        if (detachedDevice != null && activeDevice != null &&
            detachedDevice.deviceName != activeDevice.deviceName) {
            return
        }
        closeSerialSession()
        selectedDevice = null
        requestedDevice = null
    }

    fun shutdown() {
        handleDeviceDetached()
        serialExecutor?.shutdownNow()
        serialExecutor = null
        stopPtyThread()
    }

    private val printerProber by lazy { UsbSerialProber(getCustomPrinterProber()) }


    fun tryToSelectDevice(device: ConnectedUsbDevice) {
        val devices = connectedDevices.value
        if (device.driverClass != SerialDriverClass.UNKNOWN) {
            if (selectedDevice?.device?.deviceName == device.device.deviceName) return
            if (!usbManager.hasPermission(device.device)) {
                if (requestedDevice?.device?.deviceName == device.device.deviceName) return
                val intent = Intent(OctoPrintService.BROADCAST_SERVICE_USB_GOT_ACCESS)
                    .setPackage(context.packageName)
                requestedDevice = device
                // UsbManager must add EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED to the result
                // intent. An immutable PendingIntent drops those fill-in extras on Android 12+.
                // compileSdk is currently 28, so use Android's documented FLAG_MUTABLE value
                // only on API 31+ until the project performs its dedicated SDK upgrade.
                val mutablePendingIntentFlag = if (Build.VERSION.SDK_INT >= 31) 0x02000000 else 0
                val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or mutablePendingIntentFlag
                val mPendingIntent =
                    PendingIntent.getBroadcast(context, usbPermissionRequestCode, intent, pendingIntentFlags)
                usbManager.requestPermission(device.device, mPendingIntent)
                logger.log(this) { "REQUESTED DEVICE" }
                Toast.makeText(context, context.getString(R.string.requesting_usb_permission), Toast.LENGTH_LONG).show()
            } else {
                // Release the previous controller before switching to another USB device.
                if (selectedDevice?.device?.deviceName != device.device.deviceName) closeSerialSession()
                selectedDevice = device.createDriver()
                prefs.defaultPrinterCustomDriver = device.driverClass.name
                prefs.defaultPrinterPid = device.productId
                prefs.defaultPrinterVid = device.vendorId
                devices.forEach {
                    it.isSelected = it.device.deviceName == device.device.deviceName
                }

                connectedDevices.value = listOf()
                connectedDevices.value = devices

                octoPrintHandler.usbAttached(device.device.deviceName)
                device.device.deviceName
            }
        } else {
            Toast.makeText(context, context.getString(R.string.no_driver_selected), Toast.LENGTH_LONG).show()
        }
    }

    fun updateDevicesList(intent: String): String? {
        val visibleDevices = usbManager.deviceList
        logger.log(this) {
            "USB serial scan: visibleDevices=${visibleDevices.size}, selected=${selectedDevice?.device?.deviceName ?: "none"}"
        }

        val previousDevice = selectedDevice?.device
        if (previousDevice != null && visibleDevices.values.none { it.deviceName == previousDevice.deviceName }) {
            logger.log(this) { "Previously selected USB device disappeared: ${previousDevice.deviceName}" }
            handleDeviceDetached(previousDevice)
        }

        connectedDevices.value = visibleDevices.map { entry ->
            val usbDevice = entry.value
            var driverClass = when (printerProber.probeDevice(usbDevice)) {
                is Cp21xxSerialDriver -> SerialDriverClass.CP21XX
                is FtdiSerialDriver -> SerialDriverClass.FTDI
                is ProlificSerialDriver -> SerialDriverClass.PROLIFIC
                is Ch34xSerialDriver -> SerialDriverClass.CH341
                is CdcAcmSerialDriver -> SerialDriverClass.CDC
                else -> SerialDriverClass.UNKNOWN
            }
            val autoDetected = driverClass != SerialDriverClass.UNKNOWN

            if (usbDevice.productId == prefs.defaultPrinterPid &&
                usbDevice.vendorId == prefs.defaultPrinterVid &&
                !prefs.defaultPrinterCustomDriver.isNullOrBlank()) {
                try {
                    driverClass = SerialDriverClass.valueOf(prefs.defaultPrinterCustomDriver!!)
                } catch (failure: IllegalArgumentException) {
                    logger.logError(this, "Ignoring invalid saved USB driver preference", failure)
                }
            }

            logger.log(this) {
                "USB serial candidate: name=${usbDevice.deviceName}, VID=0x${usbDevice.vendorId.toString(16)}, PID=0x${usbDevice.productId.toString(16)}, interfaces=${usbDevice.interfaceCount}, driver=${driverClass.name}, autoDetected=$autoDetected, permission=${usbManager.hasPermission(usbDevice)}"
            }
            ConnectedUsbDevice(
                usbDevice.deviceName, usbDevice.productId, usbDevice.vendorId,
                driverClass, usbDevice.deviceName == selectedDevice?.device?.deviceName,
                autoDetected, usbDevice
            )
        }

        // Keep an active device selected if it is still connected. This avoids silently
        // switching printers when another device with the same VID/PID is attached.
        if (selectedDevice != null) return ""

        val candidates = connectedDevices.value.filter { it.driverClass != SerialDriverClass.UNKNOWN }
        val savedCandidates = candidates.filter {
            it.vendorId == prefs.defaultPrinterVid && it.productId == prefs.defaultPrinterPid
        }
        when {
            savedCandidates.size == 1 -> tryToSelectDevice(savedCandidates.single())
            savedCandidates.size > 1 -> logger.log(this) {
                "Saved USB VID/PID matches ${savedCandidates.size} devices; refusing ambiguous automatic selection"
            }
            candidates.size == 1 -> tryToSelectDevice(candidates.single())
            candidates.size > 1 -> logger.log(this) {
                "Found ${candidates.size} compatible USB serial devices; waiting for manual selection"
            }
        }
        return ""
    }

    override fun onDataReceived(data: SerialData?) {
        try {
            data?.apply {
                logger.log(this) { pty.getBaudrate(baudrate).toString() }
                val newConnectionRequired = ((isStartPacket || currentBaudrate != baudrate) && selectedDevice != null)
                if (newConnectionRequired || port?.isOpen != true) {
                    if (selectedDevice == null) {
                        updateDevicesList("")
                        if (selectedDevice == null) return
                    }

                    // Close both the serial port and USB connection before reopening,
                    // including on baud-rate changes.
                    closeSerialSession()
                    val activeDriver = selectedDevice
                        ?: throw IllegalStateException("No USB serial driver selected")
                    val activePort = activeDriver.ports.firstOrNull()
                        ?: throw IllegalStateException("USB serial driver exposes no ports")
                    val activeDevice = activeDriver.device
                    if (!usbManager.hasPermission(activeDevice)) {
                        logger.log(this) { "USB permission missing when opening serial port; requesting selection again" }
                        handleDeviceDetached()
                        updateDevicesList("")
                        return
                    }
                    connection = usbManager.openDevice(activeDevice)
                        ?: throw IllegalStateException(
                            "Android UsbManager.openDevice returned null for " +
                                "${activeDevice.deviceName} (VID=0x${activeDevice.vendorId.toString(16)}, PID=0x${activeDevice.productId.toString(16)}). Check USB permission, OTG power, cable, and board state."
                        )
                    port = activePort

                    logger.log(this) {
                        "Opening USB serial port: device=${activeDevice.deviceName}, driver=${activeDriver.javaClass.simpleName}, ports=${activeDriver.ports.size}, baud=${pty.getBaudrate(baudrate)}"
                    }
                    port?.open(connection)
                    logger.log(this) { "USB serial port opened successfully: device=${activeDevice.deviceName}" }
                    // @TODO get it from flags
                    port?.setParameters(
                        pty.getBaudrate(baudrate),
                        8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE
                    )
                    currentBaudrate = baudrate
                    if (newConnectionRequired) {
                        port?.dtr = true
                        port?.rts = true
                    }

                    serialInputManager = SerialInputOutputManager(port, this@VirtualSerialDriver)
                    if (serialExecutor == null || serialExecutor?.isShutdown == true) {
                        serialExecutor = Executors.newSingleThreadExecutor()
                    }
                    serialExecutor?.submit(serialInputManager)
                }

                if (data.data.size > 1) {
                    try {
                        port?.write(data.serialData, 5000)
                    } catch (e: Exception) {
                        logger.logError(this, "Could not write to the USB serial port", e)
                        handleDeviceDetached()
                    }
                }
            }
        } catch (e: Exception) {
            logger.logError(this, "USB serial communication failed", e)
            closeSerialSession()
        }
    }

    override fun onNewData(data: ByteArray) {
        pty.writeData(data)
    }

    override fun onRunError(e: Exception?) {
        if (e != null) {
            logger.logError(this, "USB serial reader stopped unexpectedly", e)
            closeSerialSession()
        }
    }
}
