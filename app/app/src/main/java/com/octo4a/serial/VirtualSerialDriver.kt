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
                    pty.runPtyThread()
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

    fun handleDeviceDetached(detachedDevice: UsbDevice? = null) {
        val activeDevice = selectedDevice?.device
        if (detachedDevice != null && activeDevice != null && detachedDevice.deviceName != activeDevice.deviceName) {
            return
        }

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
        selectedDevice = null
        requestedDevice = null
        currentBaudrate = -1
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
            if (!usbManager.hasPermission(device.device)) {
                val intent = Intent(OctoPrintService.BROADCAST_SERVICE_USB_GOT_ACCESS)
                    .setPackage(context.packageName)
                requestedDevice = device
                val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                val mPendingIntent =
                    PendingIntent.getBroadcast(context, usbPermissionRequestCode, intent, pendingIntentFlags)
                usbManager.requestPermission(device.device, mPendingIntent)
                logger.log(this) { "REQUESTED DEVICE" }
                Toast.makeText(context, context.getString(R.string.requesting_usb_permission), Toast.LENGTH_LONG).show()
            } else {
                selectedDevice = device.createDriver()
                prefs.defaultPrinterCustomDriver = device.driverClass.name
                prefs.defaultPrinterPid = device.productId
                prefs.defaultPrinterVid = device.vendorId
                devices.forEach {
                    it.isSelected = it.vendorId == device.vendorId && it.productId == device.productId
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
        connectedDevices.value = usbManager.deviceList.map {
            // Try to autodetect driver
            var driverClass = when (printerProber.probeDevice(it.value)) {
                is Cp21xxSerialDriver -> SerialDriverClass.CP21XX
                is FtdiSerialDriver -> SerialDriverClass.FTDI
                is ProlificSerialDriver -> SerialDriverClass.PROLIFIC
                is Ch34xSerialDriver -> SerialDriverClass.CH341
                is CdcAcmSerialDriver -> SerialDriverClass.CDC
                else -> SerialDriverClass.UNKNOWN
            }

            val autoDetected = driverClass != SerialDriverClass.UNKNOWN

            if (it.value.productId == prefs.defaultPrinterPid && it.value.vendorId == prefs.defaultPrinterVid) {
                if (!prefs.defaultPrinterCustomDriver.isNullOrBlank()) {
                    driverClass = SerialDriverClass.valueOf(prefs.defaultPrinterCustomDriver!!)
                }
            }

            ConnectedUsbDevice(it.value.deviceName, it.value.productId, it.value.vendorId, driverClass, false, autoDetected, it.value)
        }

        // prefer last device over autodetected one
        connectedDevices.value.forEach {
            if (it.productId == prefs.defaultPrinterPid && it.vendorId == prefs.defaultPrinterVid && it.driverClass != SerialDriverClass.UNKNOWN) {
                tryToSelectDevice(it)
            }
        }

        // Use automatically detected one as second choice
        if (selectedDevice == null) {
            connectedDevices.value.firstOrNull { it.driverClass != SerialDriverClass.UNKNOWN }?.apply {
                tryToSelectDevice(this)
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

                    if (port?.isOpen == true) {
                        port?.close()
                    }
                    connection = usbManager.openDevice(selectedDevice?.device)
                    port = selectedDevice!!.ports.first()

                    port?.open(connection)
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
        }
    }

    override fun onNewData(data: ByteArray) {
        pty.writeData(data)
    }

    override fun onRunError(e: Exception?) {
        if (e != null) {
            logger.logError(this, "USB serial reader stopped unexpectedly", e)
        }
    }
}
