package com.octo4a.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.octo4a.R
import java.io.File
import java.io.IOException

/**
 * Runs the Debian services inside the app-private ARM64 rootfs.
 * PRoot is packaged as native-library payloads by the verified CI download step.
 */
class MiracleKlipperService : Service() {
    private var runtimeProcess: Process? = null
    private lateinit var rootfs: File
    private lateinit var logFile: File

    override fun onCreate() {
        super.onCreate()
        rootfs = File(filesDir, "miracle-klipper/rootfs")
        logFile = File(filesDir, "miracle-klipper/logs/proot-runtime.log")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRuntime()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification(getString(R.string.runtime_notification_starting)))
                try {
                    startRuntime()
                } catch (error: Exception) {
                    appendLog("Échec du démarrage : ${error.message}")
                    stopForeground(true)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    @Synchronized
    @Throws(IOException::class)
    private fun startRuntime() {
        if (runtimeProcess?.isAlive == true) return

        check(File(rootfs, "opt/klipper/klippy/klippy.py").isFile) {
            "Le rootfs Debian n'est pas installé. Installe d'abord l'environnement."
        }
        val configDir = File(rootfs, "home/klipper/printer_data/config")
        check(File(configDir, "printer.cfg").isFile) {
            "Configuration absente : home/klipper/printer_data/config/printer.cfg"
        }
        check(File(configDir, "moonraker.conf").isFile) {
            "Configuration absente : home/klipper/printer_data/config/moonraker.conf"
        }

        val proot = File(applicationInfo.nativeLibraryDir, "libproroot.so")
        val companionLibraries = listOf(
            "libproroot-runtime.so",
            "libproroot-bridge.so",
            "libproroot-linker.so",
            "libproroot-stub-loader.so"
        )
        check(proot.isFile && proot.canExecute() && companionLibraries.all {
            File(applicationInfo.nativeLibraryDir, it).isFile
        }) {
            "Le runtime PRoot ARM64 n'est pas inclus dans cet APK. Recompile via GitHub Actions."
        }

        val tmp = File(filesDir, "miracle-klipper/tmp")
        val logs = File(filesDir, "miracle-klipper/logs")
        check(tmp.mkdirs() || tmp.isDirectory) { "Impossible de créer le dossier temporaire." }
        check(logs.mkdirs() || logs.isDirectory) { "Impossible de créer les journaux." }
        logFile.parentFile?.mkdirs()

        val supervisor = File(rootfs, "tmp/miracle-klipper-supervisor.sh")
        check(supervisor.parentFile?.mkdirs() == true || supervisor.parentFile?.isDirectory == true) {
            "Impossible de préparer le superviseur dans Debian."
        }
        assets.open("miracle-klipper-supervisor.sh").use { input ->
            supervisor.outputStream().use { output -> input.copyTo(output) }
        }
        supervisor.setReadable(true, false)

        val command = mutableListOf(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-0",
            "--link2symlink",
            "-w", "/",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "/bin/sh", "/tmp/miracle-klipper-supervisor.sh"
        )
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["PROOT_TMP_DIR"] = tmp.absolutePath
        builder.environment()["MIRACLE_KLIPPER_ROOT"] = "/"
        builder.environment()["MIRACLE_KLIPPER_DATA"] = "/home/klipper/printer_data"
        builder.environment()["HOME"] = "/home/klipper"
        runtimeProcess = builder.start()
        appendLog("Superviseur Debian lancé.")
        val process = runtimeProcess
        Thread({
            try {
                process?.inputStream?.use { input ->
                    java.io.FileOutputStream(logFile, true).use { output -> input.copyTo(output) }
                }
            } catch (error: Exception) {
                appendLog("Erreur de lecture des journaux : ${error.message}")
            }
        }, "miracle-klipper-runtime-logs").apply { isDaemon = true; start() }
        Thread({
            val exitCode = try { process?.waitFor() ?: -1 } catch (_: InterruptedException) { -1 }
            appendLog("Le superviseur s'est arrêté (code ${exitCode}).")
            if (runtimeProcess === process) {
                runtimeProcess = null
                stopForeground(true)
                stopSelf()
            }
        }, "miracle-klipper-runtime-watch").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stopRuntime() {
        runtimeProcess?.let { process ->
            process.destroy()
            try {
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        runtimeProcess = null
        appendLog("Arrêt demandé.")
    }

    private fun appendLog(message: String) {
        try {
            logFile.parentFile?.mkdirs()
            logFile.appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Exception) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Miracle Klipper", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRuntime()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.octo4a.miracleklipper.START"
        const val ACTION_STOP = "com.octo4a.miracleklipper.STOP"
        private const val CHANNEL_ID = "miracle_klipper_runtime"
        private const val NOTIFICATION_ID = 47
    }
}
