package com.octo4a.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal data class KlipperManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    val repository: String,
    val revision: String,
    @SerializedName("minimumFreeStorageBytes") val minimumFreeStorageBytes: Long
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported Klipper manifest" }
        require(repository == "https://github.com/Klipper3d/klipper.git") {
            "Klipper source must use the official HTTPS repository"
        }
        require(revision.matches(Regex("[a-fA-F0-9]{40}"))) {
            "Klipper revision must be pinned to a full commit SHA"
        }
        require(minimumFreeStorageBytes >= 128L * 1024L * 1024L) {
            "Klipper minimum storage requirement is too small"
        }
    }
}

enum class KlipperStage {
    NotInstalled,
    Installing,
    NeedsConfiguration,
    Ready,
    Starting,
    Running,
    Stopping,
    Stopped,
    Failed
}

data class KlipperProgress(
    val stage: KlipperStage,
    val message: String,
    val revision: String? = null
)

interface KlipperRepository {
    val progress: StateFlow<KlipperProgress>
    val isInstalled: Boolean
    val isPrinterConfigurationReady: Boolean

    suspend fun install()
    suspend fun start()
    fun stop()
}

class KlipperRepositoryImpl(
    private val context: Context,
    private val linuxEnvironment: LinuxEnvironmentRepository,
    private val logger: LoggerRepository
) : KlipperRepository {
    private val baseDirectory = File(context.filesDir, "miracle-klipper")
    private val environmentArchitecture = LinuxArchitecture.resolve(context)
    private val rootfsDirectory = File(baseDirectory, "linux/debian-trixie-$environmentArchitecture")
    private val sharedDirectory = File(baseDirectory, "shared")
    private val softwareDirectory = File(sharedDirectory, "software")
    private val printerDataDirectory = File(sharedDirectory, "printer_data")
    private val configFile = File(printerDataDirectory, "config/printer.cfg")
    private val stateDirectory = File(baseDirectory, "state")
    private val installedRevisionFile = File(stateDirectory, "klipper-revision.txt")
    private val installLog = File(baseDirectory, "logs/klipper-install.log")
    private val runtimeLog = File(printerDataDirectory, "logs/klipper-process.log")
    private val installMutex = Mutex()
    // Serialize start attempts so concurrent service/activity requests cannot launch duplicate processes.
    private val startMutex = Mutex()
    private val processLock = Any()
    private var activeProcess: ActiveKlipperProcess? = null

    private val manifest: KlipperManifest by lazy { loadManifest() }
    private val state = MutableStateFlow(initialProgress())

    override val progress: StateFlow<KlipperProgress>
        get() = state

    override val isInstalled: Boolean
        get() = try {
            val revision = manifest.revision
            installedRevisionFile.isFile && installedRevisionFile.readText().trim() == revision &&
                File(sourceDirectory(revision), "klippy/klippy.py").isFile &&
                File(venvDirectory(revision), "pyvenv.cfg").isFile
        } catch (_: Exception) {
            false
        }

    override val isPrinterConfigurationReady: Boolean
        get() = try {
            configFile.isFile && KlipperPrinterConfigurationValidator.isComplete(configFile.readText())
        } catch (_: Exception) {
            false
        }

    private fun initialProgress(): KlipperProgress = when {
        !isInstalled -> KlipperProgress(KlipperStage.NotInstalled, "Klipper is not installed")
        isPrinterConfigurationReady -> KlipperProgress(
            KlipperStage.Ready,
            "Klipper is installed and printer.cfg is configured",
            manifest.revision
        )
        else -> KlipperProgress(
            KlipperStage.NeedsConfiguration,
            "Klipper is installed; configure printer.cfg before starting",
            manifest.revision
        )
    }

    override suspend fun install() = withContext(Dispatchers.IO) {
        installMutex.withLock {
            if (isInstalled) {
                state.value = initialProgress()
                return@withLock
            }

            var stagingSource: File? = null
            var finalSource: File? = null
            var virtualEnvironment: File? = null
            try {
                if (!linuxEnvironment.isReady) {
                    throw IOException("Prepare the Debian 13 environment before installing Klipper")
                }
                manifest.validate()
                val availableStorage = context.filesDir.usableSpace
                if (availableStorage < manifest.minimumFreeStorageBytes) {
                    throw IOException(
                        "Klipper needs at least %.1f MiB free; %.1f MiB is available".format(
                            java.util.Locale.US,
                            manifest.minimumFreeStorageBytes / (1024.0 * 1024.0),
                            availableStorage / (1024.0 * 1024.0)
                        )
                    )
                }
                ensureDirectoryExists(softwareDirectory)
                ensurePrinterDataDirectories()
                ensureDirectoryExists(installLog.parentFile!!)
                ensureDirectoryExists(stateDirectory)
                installLog.writeText("")

                val revision = manifest.revision.toLowerCase(java.util.Locale.US)
                stagingSource = File(softwareDirectory, "klipper-staging-$revision")
                finalSource = sourceDirectory(revision)
                virtualEnvironment = venvDirectory(revision)
                deleteDirectoryIfPresent(stagingSource)
                deleteDirectoryIfPresent(finalSource)
                deleteDirectoryIfPresent(virtualEnvironment)

                state.value = KlipperProgress(KlipperStage.Installing, "Fetching pinned Klipper source", revision)
                runInRootfs(
                    listOf("/usr/bin/git", "init", guestPath(stagingSource)),
                    "Initialize Klipper source"
                )
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "remote", "add", "origin", manifest.repository),
                    "Configure Klipper source"
                )
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "fetch", "--depth=1", "origin", revision),
                    "Download Klipper revision"
                )
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "checkout", "--detach", "FETCH_HEAD"),
                    "Select pinned Klipper revision"
                )
                val checkedOutRevision = runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "rev-parse", "HEAD"),
                    "Verify Klipper revision"
                ).trim()
                if (!checkedOutRevision.equals(revision, ignoreCase = true)) {
                    throw IOException("Klipper source revision verification failed")
                }

                state.value = KlipperProgress(
                    KlipperStage.Installing,
                    "Installing Klipper's pinned Python dependencies",
                    revision
                )
                runInRootfs(
                    listOf("/usr/bin/python3", "-m", "venv", guestPath(virtualEnvironment)),
                    "Create Klipper virtual environment"
                )
                runInRootfs(
                    listOf(
                        guestPath(File(virtualEnvironment, "bin/python")), "-m", "pip", "install",
                        "--disable-pip-version-check", "--no-input", "-r",
                        guestPath(File(stagingSource, "scripts/klippy-requirements.txt"))
                    ),
                    "Install Klipper Python dependencies"
                )
                runInRootfs(
                    listOf(guestPath(File(virtualEnvironment, "bin/python")), "-m", "pip", "check"),
                    "Check Klipper Python dependencies"
                )
                runInRootfs(
                    listOf(
                        guestPath(File(virtualEnvironment, "bin/python")), "-m", "compileall", "-q",
                        guestPath(File(stagingSource, "klippy"))
                    ),
                    "Validate Klipper Python sources"
                )
                runInRootfs(
                    listOf(guestPath(File(virtualEnvironment, "bin/python")), "--version"),
                    "Verify Klipper Python environment"
                )

                if (!stagingSource.renameTo(finalSource)) {
                    throw IOException("Could not activate the verified Klipper source")
                }
                ensureInitialPrinterConfiguration()
                installedRevisionFile.writeText("$revision\n")
                state.value = initialProgress()
            } catch (cancelled: CancellationException) {
                stagingSource?.let { deleteDirectoryIfPresent(it) }
                finalSource?.let { deleteDirectoryIfPresent(it) }
                virtualEnvironment?.let { deleteDirectoryIfPresent(it) }
                state.value = KlipperProgress(KlipperStage.Failed, "Klipper installation interrupted")
                recordFailure("Klipper installation interrupted", cancelled)
                throw cancelled
            } catch (error: Throwable) {
                stagingSource?.let { deleteDirectoryIfPresent(it) }
                finalSource?.let { deleteDirectoryIfPresent(it) }
                virtualEnvironment?.let { deleteDirectoryIfPresent(it) }
                val message = "Klipper installation failed: ${describeFailure(error)}"
                state.value = KlipperProgress(KlipperStage.Failed, message, manifest.revision)
                recordFailure(message, error)
                logger.logError(this@KlipperRepositoryImpl, message, error)
                if (error is Exception) throw IOException(message, error)
                throw error
            }
        }
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        startMutex.withLock {
        if (!isInstalled) throw IOException("Install Klipper before starting it")
        if (!isPrinterConfigurationReady) {
            val message = "Add valid [mcu] and [printer] sections to printer.cfg before starting Klipper"
            state.value = KlipperProgress(KlipperStage.NeedsConfiguration, message, manifest.revision)
            throw IOException(message)
        }

        val existing = synchronized(processLock) {
            activeProcess?.takeIf { processIsAlive(it.process) }
        }
        if (existing != null) {
            state.value = KlipperProgress(KlipperStage.Running, "Klipper host process is running", manifest.revision)
            return@withLock
        }

        var startedProcess: ActiveKlipperProcess? = null
        try {
            ensurePrinterDataDirectories()
            ensureDirectoryExists(runtimeLog.parentFile!!)
            runtimeLog.writeText("")
            val source = sourceDirectory(manifest.revision)
            val venv = venvDirectory(manifest.revision)
            val socketFile = File(printerDataDirectory, "comms/klippy.sock")
            if (socketFile.exists() && !socketFile.delete()) {
                throw IOException("Could not remove the previous Klipper API socket")
            }

            state.value = KlipperProgress(KlipperStage.Starting, "Preparing printer serial connection", manifest.revision)
            waitForUsbSerialBridgeIfConfigured()
            state.value = KlipperProgress(KlipperStage.Starting, "Starting Klipper host process", manifest.revision)
            val command = listOf(
                guestPath(File(venv, "bin/python")),
                guestPath(File(source, "klippy/klippy.py")),
                guestPath(configFile),
                "-l", guestPath(File(printerDataDirectory, "logs/klippy.log")),
                "-a", guestPath(socketFile)
            )
            val process = createProotProcess(command, guestPath(source)).start()
            val active = ActiveKlipperProcess(process)
            startedProcess = active
            synchronized(processLock) {
                activeProcess = active
            }
            startRuntimeOutputReader(active)
            startRuntimeMonitor(active)
            // A live process is not sufficient: Moonraker can only connect once
            // Klipper has created the shared Unix-domain API socket.
            val socketDeadline = System.currentTimeMillis() + SOCKET_START_TIMEOUT_MS
            while (System.currentTimeMillis() < socketDeadline && !socketFile.exists()) {
                val stillRunning = synchronized(processLock) {
                    activeProcess === active && processIsAlive(process)
                }
                if (!stillRunning) {
                    val message = state.value.message.ifBlank {
                        "Klipper exited before creating klippy.sock; see printer_data/logs/klippy.log"
                    }
                    throw IOException(message)
                }
                Thread.sleep(SOCKET_POLL_INTERVAL_MS)
            }

            val stillRunning = synchronized(processLock) {
                activeProcess === active && processIsAlive(process)
            }
            if (!stillRunning) {
                val message = state.value.message.ifBlank {
                    "Klipper stopped during startup; see printer_data/logs/klippy.log"
                }
                throw IOException(message)
            }
            if (!socketFile.exists()) {
                throw IOException(
                    "Klipper is running but did not create printer_data/comms/klippy.sock " +
                        "within ${SOCKET_START_TIMEOUT_MS / 1000} seconds; Moonraker cannot connect. " +
                        "See printer_data/logs/klippy.log"
                )
            }
            state.value = KlipperProgress(
                KlipperStage.Running,
                "Klipper process is running and klippy.sock exists; check MCU readiness in klippy.log",
                manifest.revision
            )
        } catch (cancelled: CancellationException) {
            startedProcess?.let { abortStartupProcess(it) }
            throw cancelled
        } catch (error: Exception) {
            startedProcess?.let { abortStartupProcess(it) }
            val message = "Klipper could not start: ${error.message ?: error.javaClass.simpleName}"
            state.value = KlipperProgress(KlipperStage.Failed, message, manifest.revision)
            logger.logError(this@KlipperRepositoryImpl, message, error)
            throw IOException(message, error)
        }
        }
    }

    private fun abortStartupProcess(active: ActiveKlipperProcess) {
        synchronized(processLock) {
            active.stopRequested = true
            if (activeProcess === active) activeProcess = null
        }
        try {
            active.process.destroy()
        } catch (error: Exception) {
            logger.logError(this, "Could not stop Klipper after a startup failure", error)
        }
    }

    override fun stop() {
        val active = synchronized(processLock) {
            activeProcess?.also { it.stopRequested = true }
        }
        if (active == null) {
            if (state.value.stage == KlipperStage.Running || state.value.stage == KlipperStage.Starting) {
                state.value = KlipperProgress(KlipperStage.Stopped, "Klipper is stopped", manifest.revision)
            }
            return
        }
        state.value = KlipperProgress(KlipperStage.Stopping, "Stopping Klipper", manifest.revision)
        active.process.destroy()
    }

    private fun loadManifest(): KlipperManifest {
        return context.assets.open("klipper-environment.json").bufferedReader().use { reader ->
            (Gson().fromJson(reader, KlipperManifest::class.java)
                ?: throw IOException("Klipper manifest is empty")).also { it.validate() }
        }
    }

    private fun sourceDirectory(revision: String) =
        File(softwareDirectory, "klipper-${revision.toLowerCase(java.util.Locale.US)}")

    private fun venvDirectory(revision: String) =
        File(softwareDirectory, "klipper-venv-${revision.toLowerCase(java.util.Locale.US)}")

    private fun ensurePrinterDataDirectories() {
        listOf(
            File(printerDataDirectory, "config"),
            File(printerDataDirectory, "logs"),
            File(printerDataDirectory, "gcodes"),
            File(printerDataDirectory, "comms")
        ).forEach { ensureDirectoryExists(it) }
    }

    private fun ensureInitialPrinterConfiguration() {
        ensurePrinterDataDirectories()
        val starterTemplate = """
            # Miracle Klipper printer configuration template.
            # Add a printer-specific [mcu] section and a complete [printer] section before starting Klipper.
            # Select a config from the official Klipper config examples that matches your printer and control board.
            # These Moonraker features are included as baseline; no printer pins or kinematics are guessed.

            [virtual_sdcard]
            path: /var/lib/miracle/printer_data/gcodes

            [pause_resume]

            [display_status]
        """.trimIndent() + "\n"

        if (!configFile.exists()) {
            configFile.writeText(starterTemplate)
            return
        }

        // Upgrade only the original comments-only starter. Never replace a user's
        // partially configured or printer-specific file.
        val current = configFile.readText()
        val containsOnlyComments = current.lineSequence().all { line ->
            line.isBlank() || line.trimStart().startsWith("#")
        }
        if (containsOnlyComments &&
            current.contains("# Miracle Klipper printer configuration template.")
        ) {
            configFile.writeText(starterTemplate)
        }
    }

    private fun guestPath(file: File): String {
        val relative = file.relativeTo(sharedDirectory).path.replace(File.separatorChar, '/')
        return "/var/lib/miracle/$relative"
    }

    /**
     * The Android USB serial driver exposes the printer MCU through a PTY symlink
     * at filesDir/serialpipe. Klipper must use /dev/ttyOcto4a for this bridge.
     *
     * The PTY is created asynchronously by the USB foreground service. Starting
     * Klipper before it exists causes PRoot to omit the bind mount entirely, so
     * Klipper can never reach the MCU even though the Android build succeeds.
     */
    private fun isUsbSerialBridgeConfigured(): Boolean {
        if (!configFile.isFile) return false
        return Regex("""(?im)^\s*serial\s*:\s*/dev/ttyOcto4a\s*(?:#.*)?$""")
            .containsMatchIn(configFile.readText())
    }

    private fun waitForUsbSerialBridgeIfConfigured() {
        if (!isUsbSerialBridgeConfigured()) return

        val serialPipe = File(context.filesDir, "serialpipe")
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (serialPipe.exists()) return
            try {
                Thread.sleep(100L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the Android USB serial bridge", interrupted)
            }
        }

        throw IOException(
            "The printer configuration uses /dev/ttyOcto4a, but the Android USB serial bridge " +
                "did not become ready. Connect the printer with a USB-OTG data cable, grant " +
                "Miracle Klipper USB permission, and retry. The expected bridge is filesDir/serialpipe."
        )
    }

    private fun createProotProcess(command: List<String>, workingDirectory: String): ProcessBuilder {
        val bootstrapBin = ProotRuntime.nativeDirectory(context)
        val proot = ProotRuntime.proot(context)
        if (!File(rootfsDirectory, "usr/bin/env").isFile) {
            throw IOException("The Debian 13 environment is unavailable")
        }
        ProotRuntime.requireAvailable(context)
        val temporaryDirectory = File(baseDirectory, "tmp")
        val linkDirectory = File(rootfsDirectory, ".l2s")
        ensureDirectoryExists(temporaryDirectory)
        ensureDirectoryExists(linkDirectory)
        ensureDirectoryExists(sharedDirectory)

        val args = mutableListOf(proot.absolutePath, "-r", rootfsDirectory.absolutePath, "-0")
        listOf("/dev", "/proc", "/storage", "/system", "/vendor", "/apex", "/sys")
            .filter { File(it).exists() }
            .forEach { args.addAll(listOf("-b", it)) }
        val serialPipe = File(context.filesDir, "serialpipe")
        if (serialPipe.exists()) {
            args.addAll(listOf("-b", "${serialPipe.absolutePath}:/dev/ttyOcto4a"))
        } else if (isUsbSerialBridgeConfigured()) {
            // Never launch Klipper with a silent missing-device bind mount.
            throw IOException(
                "USB serial bridge /dev/ttyOcto4a is configured but filesDir/serialpipe is missing"
            )
        }
        val fakeProcStat = ProotRuntime.fakeProcStat(context)
        if (fakeProcStat.isFile) args.addAll(listOf("-b", "${fakeProcStat.absolutePath}:/proc/stat"))
        args.addAll(listOf("-L", "--link2symlink", "-b", "${sharedDirectory.absolutePath}:/var/lib/miracle"))
        args.addAll(listOf("-w", workingDirectory, "/usr/bin/env"))
        args.addAll(listOf(
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "TMPDIR=/tmp",
            "TMP=/tmp",
            "TEMP=/tmp",
            "GIT_TERMINAL_PROMPT=0",
            "PIP_DISABLE_PIP_VERSION_CHECK=1",
            "PIP_NO_INPUT=1"
        ))
        args.addAll(command)

        val builder = ProcessBuilder(args).directory(bootstrapBin).redirectErrorStream(true)
        builder.environment()["PROOT_NO_SECCOMP"] = "1"
        builder.environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
        builder.environment()["PROOT_L2S_DIR"] = linkDirectory.absolutePath
        builder.environment()["PWD"] = bootstrapBin.absolutePath
        builder.environment().remove("LD_PRELOAD")
        builder.environment().remove("LD_LIBRARY_PATH")
        ProotRuntime.applyLoader(builder, context)
        return builder
    }

    private fun runInRootfs(command: List<String>, operation: String): String {
        val process = createProotProcess(command, "/var/lib/miracle").start()
        val output = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        output.append(line).append('\n')
                        appendInstallLog(operation, line)
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read $operation output", error)
            }
        }, "klipper-install-output")
        reader.isDaemon = true
        reader.start()
        val exitCode = try {
            process.waitFor()
        } catch (interrupted: InterruptedException) {
            process.destroy()
            try {
                process.waitFor()
            } catch (interruptedAgain: InterruptedException) {
                process.destroy()
                interrupted.addSuppressed(interruptedAgain)
            }
            throw CancellationException("Klipper installation interrupted during $operation", interrupted)
        } finally {
            try {
                reader.join()
            } catch (_: InterruptedException) {
                reader.interrupt()
            }
        }
        if (exitCode != 0) {
            val tail = output.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n")
            throw IOException("$operation failed with exit code $exitCode" + if (tail.isEmpty()) "" else "\n$tail")
        }
        return output.toString()
    }

    private fun startRuntimeOutputReader(active: ActiveKlipperProcess) {
        Thread({
            try {
                active.process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(runtimeLog) { runtimeLog.appendText("$line\n") }
                        logger.log(this, LogType.BOOTSTRAP) { "Klipper: $line" }
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read Klipper process output", error)
            }
        }, "klipper-runtime-output").apply {
            isDaemon = true
            start()
        }
    }

    private fun startRuntimeMonitor(active: ActiveKlipperProcess) {
        Thread({
            val exitCode = try {
                active.process.waitFor()
            } catch (interrupted: InterruptedException) {
                active.process.destroy()
                Thread.currentThread().interrupt()
                -1
            }
            synchronized(processLock) {
                if (activeProcess === active) {
                    activeProcess = null
                    state.value = if (active.stopRequested || exitCode == 0) {
                        KlipperProgress(KlipperStage.Stopped, "Klipper is stopped", manifest.revision)
                    } else {
                        KlipperProgress(
                            KlipperStage.Failed,
                            "Klipper exited with code $exitCode; see printer_data/logs/klippy.log",
                            manifest.revision
                        )
                    }
                }
            }
        }, "klipper-runtime-monitor").apply {
            isDaemon = true
            start()
        }
    }

    private fun processIsAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun appendInstallLog(operation: String, line: String) {
        try {
            synchronized(installLog) { installLog.appendText("$operation: $line\n") }
            logger.log(this, LogType.BOOTSTRAP) { "$operation: $line" }
        } catch (error: Exception) {
            logger.logError(this, "Could not save Klipper installation output", error)
        }
    }

    private fun recordFailure(message: String, error: Throwable) {
        try {
            ensureDirectoryExists(installLog.parentFile!!)
            synchronized(installLog) {
                installLog.appendText("\n$message\n${Log.getStackTraceString(error)}\n")
            }
        } catch (logError: Exception) {
            logger.logError(this, "Could not save Klipper installation failure details", logError)
        }
    }

    private fun describeFailure(error: Throwable): String = generateSequence(error as Throwable?) { it.cause }
        .take(5)
        .map { cause -> cause.message?.trim()?.takeIf { it.isNotEmpty() } ?: cause.javaClass.simpleName }
        .joinToString(" <- ")

    private fun ensureDirectoryExists(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create ${directory.absolutePath}")
        }
    }

    private fun deleteDirectoryIfPresent(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Could not remove ${directory.absolutePath}")
        }
    }

    private class ActiveKlipperProcess(val process: Process, @Volatile var stopRequested: Boolean = false)

    companion object {
        private const val SOCKET_START_TIMEOUT_MS = 15000L
        private const val SOCKET_POLL_INTERVAL_MS = 250L
    }
}

internal object KlipperPrinterConfigurationValidator {
    private val sectionHeader = Regex("(?m)^\\s*\\[([^\\r\\n]+)]\\s*$")

    fun isComplete(configuration: String): Boolean {
        val sections = sectionHeader.findAll(configuration).map { it.groupValues[1].trim() }.toSet()
        val hasMcu = sections.any { it == "mcu" || it.startsWith("mcu ") }
        return hasMcu && "printer" in sections
    }
}
