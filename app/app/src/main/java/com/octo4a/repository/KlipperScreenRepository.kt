package com.octo4a.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

internal data class KlipperScreenManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    val repository: String,
    val revision: String,
    @SerializedName("minimumFreeStorageBytes") val minimumFreeStorageBytes: Long
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported KlipperScreen manifest" }
        require(repository == "https://github.com/KlipperScreen/KlipperScreen.git") {
            "KlipperScreen source must use the official HTTPS repository"
        }
        require(revision.matches(Regex("[a-fA-F0-9]{40}"))) {
            "KlipperScreen revision must be pinned to a full commit SHA"
        }
        require(minimumFreeStorageBytes >= 512L * 1024L * 1024L) {
            "KlipperScreen minimum storage requirement is too small"
        }
    }
}

enum class KlipperScreenStage {
    NotInstalled,
    Installing,
    Installed,
    Starting,
    Running,
    Stopping,
    Stopped,
    Failed
}

data class KlipperScreenProgress(
    val stage: KlipperScreenStage,
    val message: String,
    val revision: String? = null
)

interface KlipperScreenRepository {
    val progress: StateFlow<KlipperScreenProgress>
    val isInstalled: Boolean
    val isRunning: Boolean

    suspend fun install()
    suspend fun start(displayHost: String)
    fun stop()
}

class KlipperScreenRepositoryImpl(
    private val context: Context,
    private val linuxEnvironment: LinuxEnvironmentRepository,
    private val moonraker: MoonrakerRepository,
    private val logger: LoggerRepository
) : KlipperScreenRepository {
    private val baseDirectory = File(context.filesDir, "miracle-klipper")
    private val environmentArchitecture = LinuxArchitecture.resolve(context)
    private val rootfsDirectory = File(baseDirectory, "linux/debian-trixie-$environmentArchitecture")
    private val sharedDirectory = File(baseDirectory, "shared")
    private val softwareDirectory = File(sharedDirectory, "software")
    private val stateDirectory = File(baseDirectory, "state")
    private val installedRevisionFile = File(stateDirectory, "klipperscreen-revision.txt")
    private val installLog = File(baseDirectory, "logs/klipperscreen-install.log")
    private val runtimeLog = File(baseDirectory, "logs/klipperscreen-process.log")
    private val installMutex = Mutex()
    private val startMutex = Mutex()
    private val processLock = Any()
    private var activeProcess: ActiveKlipperScreenProcess? = null

    private val manifest: KlipperScreenManifest by lazy { loadManifest() }
    private val state = MutableStateFlow(initialProgress())

    override val progress: StateFlow<KlipperScreenProgress>
        get() = state

    override val isInstalled: Boolean
        get() = try {
            val revision = manifest.revision.toLowerCase(Locale.US)
            installedRevisionFile.isFile && installedRevisionFile.readText().trim().toLowerCase(Locale.US) == revision &&
                File(sourceDirectory(revision), "screen.py").isFile &&
                File(venvDirectory(revision), "pyvenv.cfg").isFile
        } catch (_: Exception) {
            false
        }

    override val isRunning: Boolean
        get() = synchronized(processLock) { activeProcess?.let { processIsAlive(it.process) } == true }

    private fun initialProgress(): KlipperScreenProgress = if (isInstalled) {
        KlipperScreenProgress(KlipperScreenStage.Installed, "KlipperScreen is installed", manifest.revision)
    } else {
        KlipperScreenProgress(KlipperScreenStage.NotInstalled, "KlipperScreen is not installed")
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
                    throw IOException("Prepare the Debian 13 environment before installing KlipperScreen")
                }
                if (!moonraker.isInstalled) {
                    throw IOException("Install Moonraker before installing KlipperScreen")
                }
                manifest.validate()
                val availableStorage = context.filesDir.usableSpace
                if (availableStorage < manifest.minimumFreeStorageBytes) {
                    throw IOException(
                        "KlipperScreen needs at least %.1f MiB free; %.1f MiB is available".format(
                            Locale.US,
                            manifest.minimumFreeStorageBytes / (1024.0 * 1024.0),
                            availableStorage / (1024.0 * 1024.0)
                        )
                    )
                }
                ensureDirectoryExists(installLog.parentFile!!)
                ensureDirectoryExists(stateDirectory)
                ensureDirectoryExists(softwareDirectory)
                installLog.writeText("")

                val revision = manifest.revision.toLowerCase(Locale.US)
                stagingSource = File(softwareDirectory, "klipperscreen-staging-$revision")
                finalSource = sourceDirectory(revision)
                virtualEnvironment = venvDirectory(revision)
                deleteDirectoryIfPresent(stagingSource)
                deleteDirectoryIfPresent(finalSource)
                deleteDirectoryIfPresent(virtualEnvironment)

                state.value = KlipperScreenProgress(
                    KlipperScreenStage.Installing,
                    "Fetching pinned KlipperScreen source",
                    revision
                )
                runInRootfs(listOf("/usr/bin/git", "init", guestPath(stagingSource)), "Initialize KlipperScreen source")
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "remote", "add", "origin", manifest.repository),
                    "Configure KlipperScreen source"
                )
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "fetch", "--depth=1", "origin", revision),
                    "Download KlipperScreen revision"
                )
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "checkout", "--detach", "FETCH_HEAD"),
                    "Select pinned KlipperScreen revision"
                )
                val checkedOutRevision = runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "rev-parse", "HEAD"),
                    "Verify KlipperScreen revision"
                ).trim()
                if (!checkedOutRevision.equals(revision, ignoreCase = true)) {
                    throw IOException("KlipperScreen source revision verification failed")
                }

                installSystemDependencies(stagingSource)
                state.value = KlipperScreenProgress(
                    KlipperScreenStage.Installing,
                    "Installing KlipperScreen Python dependencies",
                    revision
                )
                runInRootfs(
                    listOf("/usr/bin/python3", "-m", "venv", "--system-site-packages", guestPath(virtualEnvironment)),
                    "Create KlipperScreen virtual environment"
                )
                runInRootfs(
                    listOf(
                        guestPath(File(virtualEnvironment, "bin/python")), "-m", "pip", "install",
                        "--disable-pip-version-check", "--no-input", "--no-cache-dir", "-r",
                        guestPath(File(stagingSource, "scripts/KlipperScreen-requirements.txt"))
                    ),
                    "Install KlipperScreen Python dependencies"
                )
                runInRootfs(
                    listOf(guestPath(File(virtualEnvironment, "bin/python")), "-m", "pip", "check"),
                    "Check KlipperScreen Python dependencies"
                )
                runInRootfs(
                    listOf(
                        guestPath(File(virtualEnvironment, "bin/python")), "-c",
                        "import gi; gi.require_version('Gtk', '3.0'); from gi.repository import Gtk; print(Gtk.MAJOR_VERSION)"
                    ),
                    "Verify KlipperScreen GTK support"
                )
                runInRootfs(
                    listOf(
                        guestPath(File(virtualEnvironment, "bin/python")), "-m", "compileall", "-q",
                        guestPath(stagingSource)
                    ),
                    "Validate KlipperScreen Python sources"
                )

                if (!stagingSource.renameTo(finalSource)) {
                    throw IOException("Could not activate the verified KlipperScreen source")
                }
                installedRevisionFile.writeText("$revision\n")
                state.value = initialProgress()
            } catch (cancelled: CancellationException) {
                stagingSource?.let { deleteDirectoryIfPresent(it) }
                finalSource?.let { deleteDirectoryIfPresent(it) }
                virtualEnvironment?.let { deleteDirectoryIfPresent(it) }
                state.value = KlipperScreenProgress(
                    KlipperScreenStage.Failed,
                    "KlipperScreen installation interrupted"
                )
                recordFailure("KlipperScreen installation interrupted", cancelled)
                throw cancelled
            } catch (error: Throwable) {
                stagingSource?.let { deleteDirectoryIfPresent(it) }
                finalSource?.let { deleteDirectoryIfPresent(it) }
                virtualEnvironment?.let { deleteDirectoryIfPresent(it) }
                val message = "KlipperScreen installation failed: ${describeFailure(error)}"
                state.value = KlipperScreenProgress(KlipperScreenStage.Failed, message, manifest.revision)
                recordFailure(message, error)
                logger.logError(this@KlipperScreenRepositoryImpl, message, error)
                if (error is Exception) throw IOException(message, error)
                throw error
            }
        }
    }

    override suspend fun start(displayHost: String) = withContext(Dispatchers.IO) {
        startMutex.withLock { startLocked(displayHost) }
    }

    private suspend fun startLocked(displayHost: String) {
        if (!isInstalled) throw IOException("Install KlipperScreen before starting it")
        if (!displayHost.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
            throw IOException("Connect to Wi-Fi before starting KlipperScreen")
        }
        val hostAddress = try {
            java.net.InetAddress.getByName(displayHost)
        } catch (error: Exception) {
            throw IOException("The phone's display address is invalid", error)
        }
        if (hostAddress !is Inet4Address) throw IOException("KlipperScreen needs an IPv4 display address")

        val existing = synchronized(processLock) { activeProcess?.takeIf { processIsAlive(it.process) } }
        if (existing != null) {
            state.value = KlipperScreenProgress(KlipperScreenStage.Running, "KlipperScreen is running", manifest.revision)
            return
        }

        state.value = KlipperScreenProgress(
            KlipperScreenStage.Starting,
            "Waiting for XServer-XSDL on $displayHost",
            manifest.revision
        )
        try {
            waitForXServer(displayHost)
            ensureDirectoryExists(runtimeLog.parentFile!!)
            runtimeLog.writeText("")
            val revision = manifest.revision
            val source = sourceDirectory(revision)
            val python = File(venvDirectory(revision), "bin/python")
            val command = listOf(guestPath(python), guestPath(File(source, "screen.py")))
            val process = createRuntimeProcess(command, guestPath(source), displayHost).start()
            val active = ActiveKlipperScreenProcess(process)
            synchronized(processLock) { activeProcess = active }
            startRuntimeOutputReader(active)
            startRuntimeMonitor(active)
            delay(1500)
            if (!processIsAlive(process)) {
                throw IOException("KlipperScreen exited during startup; see klipperscreen-process.log")
            }
            state.value = KlipperScreenProgress(
                KlipperScreenStage.Running,
                "KlipperScreen is running on the phone display",
                revision
            )
        } catch (cancelled: CancellationException) {
            if (!isRunning) {
                state.value = KlipperScreenProgress(KlipperScreenStage.Stopped, "KlipperScreen is stopped", manifest.revision)
            }
            throw cancelled
        } catch (error: Exception) {
            state.value = KlipperScreenProgress(
                KlipperScreenStage.Failed,
                "Could not start KlipperScreen: ${describeFailure(error)}",
                manifest.revision
            )
            logger.logError(this@KlipperScreenRepositoryImpl, "Could not start KlipperScreen", error)
            throw error
        }
    }

    override fun stop() {
        val active = synchronized(processLock) {
            activeProcess?.also {
                it.stopRequested = true
                state.value = KlipperScreenProgress(KlipperScreenStage.Stopping, "Stopping KlipperScreen", manifest.revision)
            }
        }
        if (active == null) {
            if (state.value.stage == KlipperScreenStage.Starting) {
                state.value = KlipperScreenProgress(KlipperScreenStage.Stopped, "KlipperScreen is stopped", manifest.revision)
            }
            return
        }
        Thread({
            active.process.destroy()
            try {
                if (!active.process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    active.process.destroyForcibly()
                    active.process.waitFor()
                }
            } catch (interrupted: InterruptedException) {
                active.process.destroyForcibly()
                Thread.currentThread().interrupt()
                logger.logError(this, "Interrupted while stopping KlipperScreen", interrupted)
            }
        }, "klipperscreen-stop").apply {
            isDaemon = true
            start()
        }
    }

    private suspend fun waitForXServer(host: String) {
        val deadline = System.currentTimeMillis() + XSERVER_START_TIMEOUT_MS
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, XSERVER_PORT), XSERVER_CONNECT_TIMEOUT_MS)
                }
                return
            } catch (error: Exception) {
                lastError = error
            }
            delay(XSERVER_POLL_INTERVAL_MS)
        }
        throw IOException(
            "XServer-XSDL is not responding on $host. Open XServer-XSDL and leave its display ready.",
            lastError
        )
    }

    private fun installSystemDependencies(source: File) {
        val policyFile = File(rootfsDirectory, "usr/sbin/policy-rc.d")
        val previousPolicy = if (policyFile.isFile) policyFile.readBytes() else null
        if (previousPolicy == null) {
            ensureDirectoryExists(policyFile.parentFile!!)
            policyFile.writeText("#!/bin/sh\nexit 101\n")
            policyFile.setExecutable(true, false)
        }
        try {
            state.value = KlipperScreenProgress(
                KlipperScreenStage.Installing,
                "Installing KlipperScreen display and build dependencies",
                manifest.revision
            )
            runInRootfs(
                listOf("/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "update"),
                "Refresh Debian package metadata"
            )
            runInRootfs(
                listOf("/usr/bin/dpkg", "--configure", "-a"),
                "Repair interrupted Debian package configuration"
            )
            val packages = loadDebianDependencies(source) + KLIPPERSCREEN_BUILD_PACKAGES
            runInRootfs(
                listOf(
                    "/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "install", "-y",
                    "--no-install-recommends"
                ) + packages.distinct(),
                "Install KlipperScreen Debian dependencies"
            )
            runInRootfs(listOf("/usr/bin/apt-get", "check"), "Check Debian dependencies")
        } finally {
            if (previousPolicy != null) {
                policyFile.writeBytes(previousPolicy)
            } else if (policyFile.exists() && !policyFile.delete()) {
                logger.log(this, LogType.BOOTSTRAP) { "Could not remove temporary policy-rc.d" }
            }
        }
    }

    private fun loadDebianDependencies(source: File): List<String> {
        val dependencyFile = File(source, "scripts/system-dependencies.json")
        if (!dependencyFile.isFile) {
            throw IOException("The pinned KlipperScreen source is missing system-dependencies.json")
        }
        val dependencies = try {
            JsonParser().parse(dependencyFile.readText()).asJsonObject.getAsJsonArray("debian")
                ?: throw IOException("KlipperScreen has no Debian dependency list")
        } catch (error: Exception) {
            throw IOException("Could not read KlipperScreen's Debian dependency list", error)
        }
        return dependencies.map { item ->
            val entry = item.asString.trim()
            val packageName = entry.substringBefore(';').trim()
            if (packageName.isEmpty() || !packageName.matches(Regex("[a-z0-9][a-z0-9+.-]*"))) {
                throw IOException("Invalid KlipperScreen Debian package entry: $entry")
            }
            packageName
        }.distinct()
    }

    private fun loadManifest(): KlipperScreenManifest {
        val value = try {
            context.assets.open(MANIFEST_ASSET).bufferedReader().use { Gson().fromJson(it, KlipperScreenManifest::class.java) }
        } catch (error: Exception) {
            throw IllegalStateException("Could not load the KlipperScreen manifest", error)
        }
        value.validate()
        return value
    }

    private fun sourceDirectory(revision: String) =
        File(softwareDirectory, "klipperscreen-${revision.toLowerCase(Locale.US)}")

    private fun venvDirectory(revision: String) =
        File(softwareDirectory, "klipperscreen-venv-${revision.toLowerCase(Locale.US)}")

    private fun guestPath(file: File): String {
        val relative = file.relativeTo(sharedDirectory).path.replace(File.separatorChar, '/')
        return "/var/lib/miracle/$relative"
    }

    private fun createProotProcess(
        command: List<String>,
        workingDirectory: String,
        guestEnvironment: Map<String, String> = emptyMap()
    ): ProcessBuilder {
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
        if (serialPipe.exists()) args.addAll(listOf("-b", "${serialPipe.absolutePath}:/dev/ttyOcto4a"))
        val fakeProcStat = ProotRuntime.fakeProcStat(context)
        if (fakeProcStat.isFile) args.addAll(listOf("-b", "${fakeProcStat.absolutePath}:/proc/stat"))
        args.addAll(listOf("-L", "--link2symlink", "-b", "${sharedDirectory.absolutePath}:/var/lib/miracle"))
        args.addAll(listOf("-w", workingDirectory, "/usr/bin/env"))
        val environment = linkedMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "DEBIAN_FRONTEND" to "noninteractive",
            "TMPDIR" to "/tmp",
            "TMP" to "/tmp",
            "TEMP" to "/tmp",
            "GIT_TERMINAL_PROMPT" to "0",
            "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
            "PIP_NO_INPUT" to "1",
            "PIP_NO_CACHE_DIR" to "1"
        )
        environment.putAll(guestEnvironment)
        environment.forEach { (name, value) -> args.add("$name=$value") }
        args.addAll(command)

        val builder = ProcessBuilder(args).directory(bootstrapBin).redirectErrorStream(true)
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
                        appendLog(installLog, "$operation: $line")
                        logger.log(this, LogType.BOOTSTRAP) { "$operation: $line" }
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read $operation output", error)
            }
        }, "klipperscreen-install-output")
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
            throw CancellationException("KlipperScreen installation interrupted during $operation", interrupted)
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

    private fun createRuntimeProcess(command: List<String>, workingDirectory: String, displayHost: String): ProcessBuilder {
        return createProotProcess(
            command,
            workingDirectory,
            mapOf(
                "DISPLAY" to "$displayHost:0",
                "GDK_BACKEND" to "x11",
                "HOME" to "/var/lib/miracle",
                "PYTHONUNBUFFERED" to "1"
            )
        )
    }

    private fun startRuntimeOutputReader(active: ActiveKlipperScreenProcess) {
        Thread({
            try {
                active.process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        appendLog(runtimeLog, line)
                        logger.log(this, LogType.BOOTSTRAP) { "KlipperScreen: $line" }
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read KlipperScreen output", error)
            }
        }, "klipperscreen-runtime-output").apply {
            isDaemon = true
            start()
        }
    }

    private fun startRuntimeMonitor(active: ActiveKlipperScreenProcess) {
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
                        KlipperScreenProgress(KlipperScreenStage.Stopped, "KlipperScreen is stopped", manifest.revision)
                    } else {
                        KlipperScreenProgress(
                            KlipperScreenStage.Failed,
                            "KlipperScreen exited with code $exitCode; see klipperscreen-process.log",
                            manifest.revision
                        )
                    }
                }
            }
        }, "klipperscreen-runtime-monitor").apply {
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

    private fun appendLog(file: File, line: String) {
        try {
            synchronized(file) {
                ensureDirectoryExists(file.parentFile!!)
                file.appendText("$line\n")
            }
        } catch (error: Exception) {
            logger.logError(this, "Could not append to ${file.name}", error)
        }
    }

    private fun ensureDirectoryExists(directory: File) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create directory ${directory.absolutePath}")
        }
    }

    private fun deleteDirectoryIfPresent(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Could not remove incomplete installation directory ${directory.name}")
        }
    }

    private fun describeFailure(error: Throwable): String = generateSequence(error as Throwable?) { it.cause }
        .take(5)
        .map { cause -> cause.message?.trim()?.takeIf { it.isNotEmpty() } ?: cause.javaClass.simpleName }
        .joinToString(" <- ")
        .ifEmpty { error.javaClass.simpleName }

    private fun recordFailure(message: String, error: Throwable) {
        appendLog(installLog, "\n$message\n${Log.getStackTraceString(error)}")
    }

    private data class ActiveKlipperScreenProcess(
        val process: Process,
        @Volatile var stopRequested: Boolean = false
    )

    companion object {
        private const val MANIFEST_ASSET = "klipperscreen-environment.json"
        private const val XSERVER_PORT = 6000
        private const val XSERVER_CONNECT_TIMEOUT_MS = 750
        private const val XSERVER_START_TIMEOUT_MS = 90_000L
        private const val XSERVER_POLL_INTERVAL_MS = 1_000L
        private val KLIPPERSCREEN_BUILD_PACKAGES = listOf(
            "autoconf",
            "build-essential",
            "cmake",
            "fonts-ipafont",
            "fonts-nanum",
            "gcc",
            "libcairo2-dev",
            "libdbus-1-dev",
            "libdbus-glib-1-dev",
            "libgirepository1.0-dev",
            "libmpv-dev",
            "libopenjp2-7",
            "libsystemd-dev",
            "librsvg2-common",
            "pkg-config",
            "python3-dev",
            "python3-venv"
        )
    }
}
