package fr.atelierdesmiracles.miracleklipper.v2

import java.io.File
import java.io.IOException

/**
 * Minimal process adapter. The caller must own Android foreground-service
 * lifecycle and user consent before starting a guest.
 */
class QemuProcessController {
    @Volatile private var process: Process? = null

    @Synchronized
    fun start(config: QemuGuestConfig, workingDirectory: File): QemuStartResult {
        if (process?.isAlive == true) return QemuStartResult.AlreadyRunning
        when (val preflight = QemuCommandBuilder.build(config)) {
            is QemuPreflightResult.Missing -> return QemuStartResult.MissingFiles(preflight.items)
            is QemuPreflightResult.Invalid -> return QemuStartResult.Invalid(preflight.reason)
            is QemuPreflightResult.Ready -> {
                return try {
                    if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
                        return QemuStartResult.Failed("Impossible de créer le répertoire de travail.")
                    }
                    config.qmpSocket.parentFile?.mkdirs()
                    config.serialLog.parentFile?.mkdirs()
                    process = ProcessBuilder(preflight.command)
                        .directory(workingDirectory)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(config.serialLog))
                        .start()
                    QemuStartResult.Started(process!!)
                } catch (error: IOException) {
                    QemuStartResult.Failed(error.message ?: "Échec du lancement de QEMU.")
                } catch (error: SecurityException) {
                    QemuStartResult.Failed(error.message ?: "Android a refusé le lancement de QEMU.")
                }
            }
        }
    }

    @Synchronized
    fun stop(): Boolean {
        val running = process ?: return true
        if (!running.isAlive) {
            process = null
            return true
        }
        running.destroy()
        val stopped = try {
            running.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!stopped) running.destroyForcibly()
        process = null
        return stopped
    }

    fun isRunning(): Boolean = process?.isAlive == true
}

sealed class QemuStartResult {
    data class Started(val process: Process) : QemuStartResult()
    data class MissingFiles(val items: List<String>) : QemuStartResult()
    data class Invalid(val reason: String) : QemuStartResult()
    data class Failed(val reason: String) : QemuStartResult()
    object AlreadyRunning : QemuStartResult()
}
