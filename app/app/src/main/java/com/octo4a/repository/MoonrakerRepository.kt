package com.octo4a.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
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
import java.nio.file.Files
import java.nio.file.Paths
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class MoonrakerManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    val repository: String,
    val revision: String,
    @SerializedName("minimumFreeStorageBytes") val minimumFreeStorageBytes: Long
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported Moonraker manifest" }
        require(repository == "https://github.com/Arksine/moonraker.git") {
            "Moonraker source must use the official HTTPS repository"
        }
        require(revision.matches(Regex("[a-fA-F0-9]{40}"))) {
            "Moonraker revision must be pinned to a full commit SHA"
        }
        require(minimumFreeStorageBytes >= 128L * 1024L * 1024L) {
            "Moonraker minimum storage requirement is too small"
        }
    }
}

internal data class MoonrakerServerInfo(
    @SerializedName("moonraker_version") val moonrakerVersion: String = "",
    @SerializedName("klippy_connected") val klippyConnected: Boolean = false,
    @SerializedName("klippy_state") val klippyState: String = "unknown"
)

enum class MoonrakerStage {
    NotInstalled,
    Installing,
    Installed,
    NeedsConfiguration,
    Starting,
    Ready,
    KlipperDisconnected,
    Running,
    ApiUnavailable,
    Stopping,
    Stopped,
    Failed
}

data class MoonrakerProgress(
    val stage: MoonrakerStage,
    val message: String,
    val revision: String? = null,
    val moonrakerVersion: String? = null,
    val klippyConnected: Boolean? = null,
    val klippyState: String? = null
)

interface MoonrakerRepository {
    val progress: StateFlow<MoonrakerProgress>
    val isInstalled: Boolean

    suspend fun install()
    suspend fun start()
    fun stop()
}

class MoonrakerRepositoryImpl(
    private val context: Context,
    private val linuxEnvironment: LinuxEnvironmentRepository,
    private val klipper: KlipperRepository,
    private val logger: LoggerRepository
) : MoonrakerRepository {
    private val baseDirectory = File(context.filesDir, "miracle-klipper")
    private val environmentArchitecture = LinuxArchitecture.resolve(context)
    private val rootfsDirectory = File(baseDirectory, "linux/debian-trixie-$environmentArchitecture")
    private val sharedDirectory = File(baseDirectory, "shared")
    private val softwareDirectory = File(sharedDirectory, "software")
    private val printerDataDirectory = File(sharedDirectory, "printer_data")
    private val configFile = File(printerDataDirectory, "config/moonraker.conf")
    private val stateDirectory = File(baseDirectory, "state")
    private val installedRevisionFile = File(stateDirectory, "moonraker-revision.txt")
    private val installLog = File(baseDirectory, "logs/moonraker-install.log")
    private val runtimeLog = File(printerDataDirectory, "logs/moonraker-process.log")
    private val installMutex = Mutex()
    private val processLock = Any()
    private var activeProcess: ActiveMoonrakerProcess? = null

    private val manifest: MoonrakerManifest by lazy { loadManifest() }
    private val state = MutableStateFlow(initialProgress())

    override val progress: StateFlow<MoonrakerProgress>
        get() = state

    override val isInstalled: Boolean
        get() = try {
            val revision = manifest.revision
            installedRevisionFile.isFile && installedRevisionFile.readText().trim() == revision &&
                File(sourceDirectory(revision), "moonraker/moonraker.py").isFile &&
                File(venvDirectory(revision), "pyvenv.cfg").isFile
        } catch (_: Exception) {
            false
        }

    private fun initialProgress(): MoonrakerProgress = when {
        !isInstalled -> MoonrakerProgress(MoonrakerStage.NotInstalled, "Moonraker is not installed")
        !MoonrakerConfigurationValidator.isSafeLocalConfiguration(readConfiguration()) ->
            MoonrakerProgress(
                MoonrakerStage.NeedsConfiguration,
                "Moonraker config must use loopback-only access and the Klipper socket",
                manifest.revision
            )
        else -> MoonrakerProgress(
            MoonrakerStage.Installed,
            "Moonraker is installed; API access is restricted to this phone",
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
                    throw IOException("Prepare the Debian 13 environment before installing Moonraker")
                }
                if (!klipper.isInstalled) {
                    throw IOException("Install Klipper before installing Moonraker")
                }
                manifest.validate()
                val availableStorage = context.filesDir.usableSpace
                if (availableStorage < manifest.minimumFreeStorageBytes) {
                    throw IOException(
                        "Moonraker needs at least %.1f MiB free; %.1f MiB is available".format(
                            Locale.US,
                            manifest.minimumFreeStorageBytes / (1024.0 * 1024.0),
                            availableStorage / (1024.0 * 1024.0)
                        )
                    )
                }
                ensurePrinterDataDirectories()
                ensureDirectoryExists(installLog.parentFile!!)
                ensureDirectoryExists(stateDirectory)
                installLog.writeText("")

                val revision = manifest.revision.toLowerCase(Locale.US)
                stagingSource = File(softwareDirectory, "moonraker-staging-$revision")
                finalSource = sourceDirectory(revision)
                virtualEnvironment = venvDirectory(revision)
                deleteDirectoryIfPresent(stagingSource)
                deleteDirectoryIfPresent(finalSource)
                deleteDirectoryIfPresent(virtualEnvironment)

                state.value = MoonrakerProgress(
                    MoonrakerStage.Installing,
                    "Fetching pinned Moonraker source",
                    revision
                )
                runInRootfs(
                    listOf("/usr/bin/git", "init", guestPath(stagingSource)),
                    "Initialize Moonraker source"
                )
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "remote", "add", "origin", manifest.repository),
                    "Configure Moonraker source"
                )
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "fetch", "--depth=1", "origin", revision),
                    "Download Moonraker revision"
                )
                runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "checkout", "--detach", "FETCH_HEAD"),
                    "Select pinned Moonraker revision"
                )
                val checkedOutRevision = runInRootfs(
                    listOf("/usr/bin/git", "-C", guestPath(stagingSource), "rev-parse", "HEAD"),
                    "Verify Moonraker revision"
                ).trim()
                if (!checkedOutRevision.equals(revision, ignoreCase = true)) {
                    throw IOException("Moonraker source revision verification failed")
                }
                installSystemDependencies(stagingSource)

                state.value = MoonrakerProgress(
                    MoonrakerStage.Installing,
                    "Installing Moonraker's Python dependencies",
                    revision
                )
                runInRootfs(
                    listOf("/usr/bin/python3", "-m", "venv", guestPath(virtualEnvironment)),
                    "Create Moonraker virtual environment"
                )
                runInRootfs(
                    listOf(
                        guestPath(File(virtualEnvironment, "bin/python")), "-m", "pip", "install",
                        "--disable-pip-version-check", "--no-input", "--no-cache-dir", "-r",
                        guestPath(File(stagingSource, "scripts/moonraker-requirements.txt"))
                    ),
                    "Install Moonraker Python dependencies"
                )
                runInRootfs(
                    listOf(guestPath(File(virtualEnvironment, "bin/python")), "-m", "pip", "check"),
                    "Check Moonraker Python dependencies"
                )
                runInRootfs(
                    listOf(
                        guestPath(File(virtualEnvironment, "bin/python")), "-m", "compileall", "-q",
                        guestPath(File(stagingSource, "moonraker"))
                    ),
                    "Validate Moonraker Python sources"
                )
                runInRootfs(
                    listOf(
                        guestPath(File(virtualEnvironment, "bin/python")),
                        guestPath(File(stagingSource, "moonraker/moonraker.py")), "--help"
                    ),
                    "Verify Moonraker command line"
                )

                if (!stagingSource.renameTo(finalSource)) {
                    throw IOException("Could not activate the verified Moonraker source")
                }
                ensureInitialConfiguration()
                installedRevisionFile.writeText("$revision\n")
                state.value = initialProgress()
            } catch (cancelled: CancellationException) {
                stagingSource?.let { deleteDirectoryIfPresent(it) }
                finalSource?.let { deleteDirectoryIfPresent(it) }
                virtualEnvironment?.let { deleteDirectoryIfPresent(it) }
                state.value = MoonrakerProgress(MoonrakerStage.Failed, "Moonraker installation interrupted")
                recordFailure("Moonraker installation interrupted", cancelled)
                throw cancelled
            } catch (error: Throwable) {
                stagingSource?.let { deleteDirectoryIfPresent(it) }
                finalSource?.let { deleteDirectoryIfPresent(it) }
                virtualEnvironment?.let { deleteDirectoryIfPresent(it) }
                val message = "Moonraker installation failed: ${describeFailure(error)}"
                state.value = MoonrakerProgress(MoonrakerStage.Failed, message, manifest.revision)
                recordFailure(message, error)
                logger.logError(this@MoonrakerRepositoryImpl, message, error)
                if (error is Exception) throw IOException(message, error)
                throw error
            }
        }
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (!isInstalled) throw IOException("Install Moonraker before starting it")
        migrateLegacyConfiguration()
        if (!MoonrakerConfigurationValidator.isSafeLocalConfiguration(readConfiguration())) {
            val message = "Moonraker config must bind to loopback and use the Nginx proxy settings"
            state.value = MoonrakerProgress(MoonrakerStage.NeedsConfiguration, message, manifest.revision)
            throw IOException(message)
        }

        val existing = synchronized(processLock) {
            activeProcess?.takeIf { processIsAlive(it.process) }
        }
        if (existing != null) {
            state.value = queryServerInfo().toProgress(manifest.revision)
            return@withContext
        }

        try {
            ensurePrinterDataDirectories()
            ensureDirectoryExists(runtimeLog.parentFile!!)
            runtimeLog.writeText("")
            val source = sourceDirectory(manifest.revision)
            val venv = venvDirectory(manifest.revision)
            val moonrakerSocket = File(printerDataDirectory, "comms/moonraker.sock")
            if (moonrakerSocket.exists() && !moonrakerSocket.delete()) {
                throw IOException("Could not remove the previous Moonraker API socket")
            }

            state.value = MoonrakerProgress(MoonrakerStage.Starting, "Starting Moonraker API", manifest.revision)
            val command = listOf(
                guestPath(File(venv, "bin/python")),
                guestPath(File(source, "moonraker/moonraker.py")),
                "-d", guestPath(printerDataDirectory),
                "-c", guestPath(configFile),
                "-l", guestPath(File(printerDataDirectory, "logs/moonraker.log")),
                "-u", guestPath(moonrakerSocket)
            )
            val process = createProotProcess(command, guestPath(source)).start()
            val active = ActiveMoonrakerProcess(process)
            synchronized(processLock) { activeProcess = active }
            startRuntimeOutputReader(active)
            startRuntimeMonitor(active)

            val deadline = System.currentTimeMillis() + API_START_TIMEOUT_MS
            var lastError: Exception? = null
            while (System.currentTimeMillis() < deadline) {
                if (!processIsAlive(process)) throw IOException("Moonraker exited during startup")
                try {
                    val info = queryServerInfo()
                    state.value = info.toProgress(manifest.revision)
                    startHealthMonitor(active)
                    return@withContext
                } catch (error: Exception) {
                    lastError = error
                    try {
                        Thread.sleep(API_RETRY_INTERVAL_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw CancellationException("Moonraker startup interrupted", interrupted)
                    }
                }
            }
            throw IOException("Moonraker API did not answer /server/info: ${lastError?.message ?: "timeout"}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = "Moonraker could not start: ${error.message ?: error.javaClass.simpleName}"
            state.value = MoonrakerProgress(MoonrakerStage.Failed, message, manifest.revision)
            logger.logError(this@MoonrakerRepositoryImpl, message, error)
            throw IOException(message, error)
        }
    }

    override fun stop() {
        val active = synchronized(processLock) {
            activeProcess?.also { it.stopRequested = true }
        }
        if (active == null) {
            if (state.value.stage in listOf(MoonrakerStage.Ready, MoonrakerStage.KlipperDisconnected, MoonrakerStage.Running, MoonrakerStage.Starting)) {
                state.value = MoonrakerProgress(MoonrakerStage.Stopped, "Moonraker is stopped", manifest.revision)
            }
            return
        }
        state.value = MoonrakerProgress(MoonrakerStage.Stopping, "Stopping Moonraker", manifest.revision)
        active.process.destroy()
    }

    private fun loadManifest(): MoonrakerManifest = context.assets.open("moonraker-environment.json")
        .bufferedReader().use { reader ->
            (Gson().fromJson(reader, MoonrakerManifest::class.java)
                ?: throw IOException("Moonraker manifest is empty")).also { it.validate() }
        }

    private fun sourceDirectory(revision: String) =
        File(softwareDirectory, "moonraker-${revision.toLowerCase(Locale.US)}")

    private fun venvDirectory(revision: String) =
        File(softwareDirectory, "moonraker-venv-${revision.toLowerCase(Locale.US)}")

    private fun ensurePrinterDataDirectories() {
        listOf(
            File(printerDataDirectory, "config"),
            File(printerDataDirectory, "logs"),
            File(printerDataDirectory, "gcodes"),
            File(printerDataDirectory, "comms"),
            File(printerDataDirectory, "database"),
            File(printerDataDirectory, "certs"),
            File(printerDataDirectory, "backup")
        ).forEach { ensureDirectoryExists(it) }
    }

    private fun ensureInitialConfiguration() {
        ensurePrinterDataDirectories()
        if (!configFile.exists()) {
            configFile.writeText(
                "# Miracle Klipper Moonraker configuration.\n" +
                    "# Moonraker stays on loopback behind the LAN-filtered Nginx proxy.\n" +
                    "[server]\n" +
                    "host: 127.0.0.1\n" +
                    "port: 7125\n" +
                    "klippy_uds_address: /var/lib/miracle/printer_data/comms/klippy.sock\n" +
                    "use_xheaders: True\n\n" +
                    "[authorization]\n" +
                    "enable_api_key: True\n" +
                    "trusted_clients:\n" +
                    " 127.0.0.0/8\n" +
                    " ::1/128\n" +
                    " 10.0.0.0/8\n" +
                    " 172.16.0.0/12\n" +
                    " 192.168.0.0/16\n" +
                    " 169.254.0.0/16\n" +
                    " fc00::/7\n" +
                    " fe80::/10\n" +
                    "cors_domains:\n" +
                    " http://127.0.0.1:5000\n" +
                    " http://localhost:5000\n\n" +
                    "[machine]\n" +
                    "provider: none\n" +
                    "shutdown_action: halt\n\n" +
                    "[history]\n\n" +
                    "[octoprint_compat]\n"
            )
        } else {
            migrateLegacyConfiguration()
        }
    }

    private fun migrateLegacyConfiguration() {
        if (!configFile.isFile) return
        val current = configFile.readText()
        if (!MoonrakerConfigurationValidator.isLegacyLocalConfiguration(current)) return
        val oldTrustedClients = "trusted_clients:\n 127.0.0.0/8\n ::1/128\n"
        if (!current.contains(oldTrustedClients)) return
        val newTrustedClients =
            "trusted_clients:\n" +
                " 127.0.0.0/8\n" +
                " ::1/128\n" +
                " 10.0.0.0/8\n" +
                " 172.16.0.0/12\n" +
                " 192.168.0.0/16\n" +
                " 169.254.0.0/16\n" +
                " fc00::/7\n" +
                " fe80::/10\n"
        val migrated = current
            .replace("use_xheaders: False", "use_xheaders: True")
            .replace(oldTrustedClients, newTrustedClients)
        if (MoonrakerConfigurationValidator.isSafeLocalConfiguration(migrated)) {
            configFile.writeText(migrated)
        }
    }

    private fun readConfiguration(): String = try {
        if (configFile.isFile) configFile.readText() else ""
    } catch (_: Exception) {
        ""
    }

    private fun installSystemDependencies(moonrakerSource: File) {
        val policyFile = File(rootfsDirectory, "usr/sbin/policy-rc.d")
        val previousPolicy = if (policyFile.isFile) policyFile.readBytes() else null
        val hadPolicy = previousPolicy != null
        if (!hadPolicy) {
            ensureDirectoryExists(policyFile.parentFile!!)
            policyFile.writeText("#!/bin/sh\nexit 101\n")
            policyFile.setExecutable(true, false)
        }
        try {
            state.value = MoonrakerProgress(MoonrakerStage.Installing, "Installing Moonraker system dependencies", manifest.revision)
            // Repair old PRoot link2symlink entries from installations created before
            // rootfs activation rewrote staging paths. A stale target can make dpkg
            // fail to create /var/lib/dpkg/status-old with EPERM on non-root Android.
            repairMovedRootfsSymlinks()
            runInRootfs(
                listOf("/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "update"),
                "Refresh Debian package metadata"
            )
            restoreDebconfFrontendIfMissing()
            // Keep PRoot's --link2symlink enabled for dpkg. dpkg creates a hard-link
            // backup of its status database; raw link() is denied on some Android
            // kernels, while PRoot's compatibility mode provides that operation.
            runInRootfs(
                listOf("/usr/bin/dpkg", "--configure", "-a"),
                "Repair interrupted Debian package configuration"
            )
            runInRootfs(
                mutableListOf(
                    "/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "install", "-y",
                    "--no-install-recommends"
                ).apply { addAll(loadKiauhMoonrakerDependencies(moonrakerSource)) },
                "Install Moonraker system dependencies"
            )
            runInRootfs(listOf("/usr/bin/apt-get", "check"), "Check Debian dependencies")
        } finally {
            if (hadPolicy && previousPolicy != null) {
                policyFile.writeBytes(previousPolicy)
            } else if (policyFile.exists() && !policyFile.delete()) {
                logger.log(this, LogType.BOOTSTRAP) { "Could not remove temporary policy-rc.d" }
            }
        }
    }

    private fun repairMovedRootfsSymlinks() {
        val oldRootPath = File(baseDirectory, "staging/debian/rootfs").absolutePath
        val newRootPath = rootfsDirectory.absolutePath
        if (!rootfsDirectory.isDirectory) return

        fun visit(directory: File) {
            val children = directory.listFiles()
                ?: throw IOException("Could not inspect Debian directory ${directory.absolutePath}")
            for (child in children) {
                val path = child.toPath()
                if (Files.isSymbolicLink(path)) {
                    val target = Files.readSymbolicLink(path).toString()
                    if (target.contains(oldRootPath)) {
                        val rewrittenTarget = target.replace(oldRootPath, newRootPath)
                        Files.delete(path)
                        Files.createSymbolicLink(path, Paths.get(rewrittenTarget))
                    }
                } else if (child.isDirectory) {
                    visit(child)
                }
            }
        }
        visit(rootfsDirectory)
    }

    private fun restoreDebconfFrontendIfMissing() {
        val frontendFile = File(rootfsDirectory, "usr/share/debconf/frontend")
        val perlFile = File(rootfsDirectory, "usr/bin/perl")
        if (frontendFile.isFile && frontendFile.canExecute() && perlFile.canExecute()) return

        runInRootfs(
            listOf(
                "/usr/bin/apt-get", "-o", "APT::Sandbox::User=root", "download", "debconf", "perl-base"
            ),
            "Download missing Debian configuration support and Perl"
        )
        runInRootfs(
            listOf(
                "/bin/sh", "-c",
                    "found=0; for package in /var/lib/miracle/debconf_*.deb /var/lib/miracle/perl-base_*.deb; do " +
                    "[ -f \"\$package\" ] || continue; found=1; " +
                    "/usr/bin/dpkg-deb --extract \"\$package\" / || exit \$?; " +
                    "/usr/bin/rm -f \"\$package\" || exit \$?; done; " +
                    "if [ \"\$found\" -eq 0 ]; then echo 'Downloaded Debian recovery packages were not found'; exit 1; fi; " +
                    "test -x /usr/share/debconf/frontend || { echo 'Debconf frontend is missing or not executable'; exit 1; }; " +
                    "test -x /usr/bin/perl || { echo 'Perl interpreter is missing or not executable'; exit 1; }; " +
                    "echo 'Debconf frontend and Perl interpreter are ready'"
            ),
            "Restore Debian configuration support and Perl"
        )
        if (!frontendFile.isFile || !perlFile.exists()) {
            throw IOException("Debian configuration support or its Perl interpreter is still unavailable")
        }
    }

    /** Mirrors KIAUH's dependency discovery from Moonraker's scripts/system-dependencies.json. */
    private fun loadKiauhMoonrakerDependencies(moonrakerSource: File): List<String> {
        val dependencyFile = File(moonrakerSource, "scripts/system-dependencies.json")
        if (!dependencyFile.isFile) {
            throw IOException("The pinned Moonraker source is missing scripts/system-dependencies.json")
        }
        val root = try {
            JsonParser().parse(dependencyFile.readText()).asJsonObject
        } catch (error: Exception) {
            throw IOException("Could not read Moonraker's system dependency list", error)
        }
        val debianDependencies = root.getAsJsonArray("debian")
            ?: throw IOException("Moonraker's system dependency list has no Debian packages")
        val packages = mutableListOf("debconf", "perl-base")
        for (dependency in debianDependencies) {
            val entry = dependency.asString.trim()
            val parts = entry.split(';', limit = 2)
            val packageName = parts[0].trim()
            if (packageName.isEmpty() || !packageName.matches(Regex("[a-z0-9+.-]+"))) {
                throw IOException("Invalid Moonraker Debian package entry: $entry")
            }
            // packagekit pulls polkit/dbus whose maintainer scripts fail under PRoot; Moonraker
            // runs with [machine] provider: none and no update_manager, so it is not needed.
            if (packageName == "packagekit") continue
            if (parts.size == 1 || isKiauhDependencyEnabledOnDebian(parts[1].trim())) {
                packages.add(packageName)
            }
        }
        return packages.distinct()
    }

    private fun isKiauhDependencyEnabledOnDebian(condition: String): Boolean = when (condition) {
        "distro_id != 'ubuntu' or distro_version <= '24.04'" -> true
        "distro_id == 'ubuntu' and distro_version >= '24.10'" -> false
        "vendor == 'raspberry-pi' and distro_version >= '11'" -> false
        else -> throw IOException("Unsupported KIAUH Moonraker dependency condition: $condition")
    }

    private fun guestPath(file: File): String {
        val relative = file.relativeTo(sharedDirectory).path.replace(File.separatorChar, '/')
        return "/var/lib/miracle/$relative"
    }

    private fun createProotProcess(
        command: List<String>,
        workingDirectory: String,
        guestEnvironment: Map<String, String> = emptyMap(),
        useLink2Symlink: Boolean = true
    ): ProcessBuilder {
        val bootstrapBin = File(context.filesDir, "bootstrap/bin")
        val proot = File(bootstrapBin, "proot")
        if (!File(rootfsDirectory, "usr/bin/env").isFile || !proot.isFile) {
            throw IOException("The Debian 13 environment or Octo4a PRoot tools are unavailable")
        }
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
        val fakeProcStat = File(context.filesDir, "bootstrap/fake_proc_stat")
        if (fakeProcStat.isFile) args.addAll(listOf("-b", "${fakeProcStat.absolutePath}:/proc/stat"))
        args.add("-L")
        if (useLink2Symlink) args.add("--link2symlink")
        args.addAll(listOf("-b", "${sharedDirectory.absolutePath}:/var/lib/miracle"))
        args.addAll(listOf("-w", workingDirectory, "/usr/bin/env"))
        args.addAll(
            listOf(
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "LANG=C.UTF-8",
                "DEBIAN_FRONTEND=noninteractive",
                "TMPDIR=/tmp",
                "TMP=/tmp",
                "TEMP=/tmp",
                "GIT_TERMINAL_PROMPT=0",
                "PIP_DISABLE_PIP_VERSION_CHECK=1",
                "PIP_NO_INPUT=1",
                "PIP_NO_CACHE_DIR=1"
            )
        )
        guestEnvironment.forEach { (name, value) -> args.add("$name=$value") }
        args.addAll(command)

        val builder = ProcessBuilder(args).directory(bootstrapBin).redirectErrorStream(true)
        builder.environment()["PROOT_NO_SECCOMP"] = "1"
        builder.environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
        builder.environment()["PROOT_L2S_DIR"] = linkDirectory.absolutePath
        builder.environment()["PWD"] = bootstrapBin.absolutePath
        builder.environment().remove("LD_PRELOAD")
        builder.environment().remove("LD_LIBRARY_PATH")
        return builder
    }

    private fun runInRootfs(
        command: List<String>,
        operation: String,
        guestEnvironment: Map<String, String> = emptyMap(),
        useLink2Symlink: Boolean = true
    ): String {
        val process = createProotProcess(command, "/var/lib/miracle", guestEnvironment, useLink2Symlink).start()
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
        }, "moonraker-install-output")
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
            throw CancellationException("Moonraker installation interrupted during $operation", interrupted)
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

    private fun queryServerInfo(): MoonrakerServerInfo {
        val connection = URL("http://127.0.0.1:7125/server/info").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = API_REQUEST_TIMEOUT_MS
        connection.readTimeout = API_REQUEST_TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Moonraker /server/info returned HTTP $status")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return MoonrakerServerInfoParser.parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun startHealthMonitor(active: ActiveMoonrakerProcess) {
        Thread({
            var failedChecks = 0
            while (processIsAlive(active.process)) {
                try {
                    val info = queryServerInfo()
                    failedChecks = 0
                    synchronized(processLock) {
                        if (activeProcess === active) state.value = info.toProgress(manifest.revision)
                    }
                } catch (error: Exception) {
                    failedChecks++
                    if (failedChecks >= API_FAILURES_BEFORE_UNAVAILABLE) {
                        synchronized(processLock) {
                            if (activeProcess === active) {
                                state.value = MoonrakerProgress(
                                    MoonrakerStage.ApiUnavailable,
                                    "Moonraker process is running, but /server/info is unavailable: ${error.message}",
                                    manifest.revision
                                )
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(API_HEALTH_INTERVAL_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "moonraker-api-health").apply {
            isDaemon = true
            start()
        }
    }

    private fun startRuntimeOutputReader(active: ActiveMoonrakerProcess) {
        Thread({
            try {
                active.process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(runtimeLog) { runtimeLog.appendText("$line\n") }
                        logger.log(this, LogType.BOOTSTRAP) { "Moonraker: $line" }
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read Moonraker process output", error)
            }
        }, "moonraker-runtime-output").apply {
            isDaemon = true
            start()
        }
    }

    private fun startRuntimeMonitor(active: ActiveMoonrakerProcess) {
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
                        MoonrakerProgress(MoonrakerStage.Stopped, "Moonraker is stopped", manifest.revision)
                    } else {
                        MoonrakerProgress(
                            MoonrakerStage.Failed,
                            "Moonraker exited with code $exitCode; see printer_data/logs/moonraker.log",
                            manifest.revision
                        )
                    }
                }
            }
        }, "moonraker-runtime-monitor").apply {
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
            logger.logError(this, "Could not save Moonraker installation output", error)
        }
    }

    private fun recordFailure(message: String, error: Throwable) {
        try {
            ensureDirectoryExists(installLog.parentFile!!)
            synchronized(installLog) {
                installLog.appendText("\n$message\n${Log.getStackTraceString(error)}\n")
            }
        } catch (logError: Exception) {
            logger.logError(this, "Could not save Moonraker installation failure details", logError)
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

    private fun MoonrakerServerInfo.toProgress(revision: String): MoonrakerProgress {
        val stage = when {
            !klippyConnected -> MoonrakerStage.KlipperDisconnected
            klippyState == "ready" -> MoonrakerStage.Ready
            else -> MoonrakerStage.Running
        }
        val message = when (stage) {
            MoonrakerStage.Ready -> "Moonraker API is healthy; Klipper reports ready"
            MoonrakerStage.KlipperDisconnected -> "Moonraker API is healthy; waiting for Klipper connection"
            else -> "Moonraker API is healthy; Klipper state is $klippyState"
        }
        return MoonrakerProgress(stage, message, revision, moonrakerVersion, klippyConnected, klippyState)
    }

    private class ActiveMoonrakerProcess(val process: Process, @Volatile var stopRequested: Boolean = false)

    companion object {
        private const val API_REQUEST_TIMEOUT_MS = 2000
        private const val API_START_TIMEOUT_MS = 30000L
        private const val API_RETRY_INTERVAL_MS = 1000L
        private const val API_HEALTH_INTERVAL_MS = 3000L
        private const val API_FAILURES_BEFORE_UNAVAILABLE = 2
    }
}

internal object MoonrakerServerInfoParser {
    fun parse(responseBody: String): MoonrakerServerInfo {
        val root = try {
            JsonParser().parse(responseBody)
        } catch (error: Exception) {
            throw IOException("Moonraker /server/info returned invalid JSON", error)
        }
        if (!root.isJsonObject) throw IOException("Moonraker /server/info returned a non-object response")

        val response = root.asJsonObject
        val apiError = response.get("error")?.takeUnless { it.isJsonNull }
        if (apiError != null) {
            val message = if (apiError.isJsonObject) {
                apiError.asJsonObject.get("message")?.asString
            } else {
                apiError.toString()
            }
            throw IOException("Moonraker /server/info returned an API error: ${message ?: apiError}")
        }

        val result = response.get("result")?.takeUnless { it.isJsonNull } ?: root
        if (!result.isJsonObject) throw IOException("Moonraker /server/info returned an invalid result")
        val info = Gson().fromJson(result, MoonrakerServerInfo::class.java)
            ?: throw IOException("Moonraker /server/info returned an empty response")
        if (info.moonrakerVersion.isBlank()) {
            throw IOException("Moonraker /server/info did not include moonraker_version")
        }
        return info
    }
}

internal object MoonrakerConfigurationValidator {
    private val sectionHeader = Regex("(?m)^\\s*\\[([^\\r\\n]+)]\\s*$")

    fun isSafeLocalConfiguration(configuration: String): Boolean = isConfiguration(configuration, true)

    fun isLegacyLocalConfiguration(configuration: String): Boolean = isConfiguration(configuration, false)

    private fun isConfiguration(configuration: String, useReverseProxy: Boolean): Boolean {
        val sections = mutableMapOf<String, MutableList<String>>()
        val sectionOccurrences = mutableMapOf<String, Int>()
        var currentSection: String? = null
        configuration.lineSequence().forEach { line ->
            val match = sectionHeader.matchEntire(line)
            if (match != null) {
                currentSection = match.groupValues[1].trim()
                sections.getOrPut(currentSection!!) { mutableListOf() }
                sectionOccurrences[currentSection!!] = (sectionOccurrences[currentSection!!] ?: 0) + 1
            } else if (currentSection != null) {
                sections[currentSection]?.add(line)
            }
        }
        val server = sections.singleSection("server", sectionOccurrences) ?: return false
        val auth = sections.singleSection("authorization", sectionOccurrences) ?: return false
        val machine = sections.singleSection("machine", sectionOccurrences) ?: return false
        return server.hasExactlyOneSetting("host", "127.0.0.1") &&
            server.hasExactlyOneSetting("port", "7125") &&
            server.hasExactlyOneSetting(
                "klippy_uds_address",
                "/var/lib/miracle/printer_data/comms/klippy.sock"
            ) &&
            server.hasExactlyOneSetting("use_xheaders", if (useReverseProxy) "True" else "False") &&
            machine.hasExactlyOneSetting("provider", "none") &&
            auth.hasExactlyOneSetting("enable_api_key", "True") &&
            auth.listValues("trusted_clients") == (
                if (useReverseProxy) setOf(
                    "127.0.0.0/8",
                    "::1/128",
                    "10.0.0.0/8",
                    "172.16.0.0/12",
                    "192.168.0.0/16",
                    "169.254.0.0/16",
                    "fc00::/7",
                    "fe80::/10"
                ) else setOf("127.0.0.0/8", "::1/128")
                ) &&
            auth.listValues("cors_domains") == setOf(
                "http://127.0.0.1:5000",
                "http://localhost:5000"
            )
    }

    private fun Map<String, MutableList<String>>.singleSection(
        name: String,
        sectionOccurrences: Map<String, Int>
    ): List<String>? = if (sectionOccurrences[name] == 1) get(name) else null

    private fun List<String>.hasExactlyOneSetting(name: String, value: String): Boolean {
        val settings = filter { line ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("#") && trimmed.substringBefore(':').trim() == name
        }
        return settings.size == 1 && settings.single().trim() == "$name: $value"
    }

    private fun List<String>.listValues(name: String): Set<String> {
        val firstSetting = indexOfFirst { line ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("#") && trimmed.substringBefore(':').trim() == name
        }
        if (firstSetting < 0 || count { line ->
                val trimmed = line.trimStart()
                !trimmed.startsWith("#") && trimmed.substringBefore(':').trim() == name
            } != 1
        ) return emptySet()

        val initial = this[firstSetting].substringAfter(':').trim()
        val values = mutableSetOf<String>()
        if (initial.isNotEmpty()) values.add(initial)
        for (line in drop(firstSetting + 1)) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (!line.first().isWhitespace()) break
            values.add(line.trim())
        }
        return values
    }
}
