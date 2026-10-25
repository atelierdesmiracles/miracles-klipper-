package com.octo4a.repository

import android.content.Context
import android.net.ConnectivityManager
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
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

enum class LinuxEnvironmentStage {
    NotInstalled,
    Downloading,
    Extracting,
    InstallingPackages,
    Ready,
    Failed
}

data class LinuxEnvironmentProgress(
    val stage: LinuxEnvironmentStage,
    val percent: Int? = null,
    val message: String = ""
)

interface LinuxEnvironmentRepository {
    val progress: StateFlow<LinuxEnvironmentProgress>
    val isReady: Boolean
    suspend fun install()
}

internal data class LinuxEnvironmentManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    val distribution: LinuxDistribution,
    val rootfs: LinuxRootfs,
    val aptSnapshot: AptSnapshot,
    val packages: LinuxPackages,
    @SerializedName("minimumFreeStorageBytes") val minimumFreeStorageBytes: Long
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported Linux environment manifest" }
        require(distribution.name == "Debian" && distribution.version == "13" &&
            distribution.codename == "trixie") { "Only Debian 13 Trixie is supported" }
        require(
            (distribution.architecture == "arm64" && distribution.androidAbi == "arm64-v8a") ||
                (distribution.architecture == "x86_64" && distribution.androidAbi == "x86_64")
        ) { "Unsupported Debian architecture" }
        require(
            (distribution.architecture == "arm64" && rootfs.url.contains("debian-trixie-aarch64-")) ||
                (distribution.architecture == "x86_64" && rootfs.url.contains("debian-trixie-x86_64-"))
        ) { "Debian rootfs architecture does not match the Android ABI" }
        require(rootfs.source == "termux/proot-distro")
        require(rootfs.url.startsWith("https://")) { "The Debian rootfs must use HTTPS" }
        require(rootfs.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "The Debian rootfs SHA-256 is invalid"
        }
        require(rootfs.archiveFormat == "tar.xz" && rootfs.stripComponents == 1) {
            "Unsupported Debian rootfs archive format"
        }
        require(aptSnapshot.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            "The Debian snapshot date is invalid"
        }
        require(aptSnapshot.suite == "trixie" && aptSnapshot.securitySuite == "trixie-security") {
            "The Debian snapshot suites do not match Trixie"
        }
        require(aptSnapshot.debian.startsWith("https://snapshot.debian.org/archive/debian/"))
        require(aptSnapshot.security.startsWith("https://snapshot.debian.org/archive/debian-security/"))
        require(minimumFreeStorageBytes >= 512L * 1024L * 1024L) {
            "The minimum free storage requirement is too small"
        }

        val packageNames = packages.foundation + packages.klipperMcuToolchain
        require(packageNames.isNotEmpty() && packageNames.all {
            it.matches(Regex("[a-z0-9][a-z0-9+.-]*"))
        }) { "The Debian package list contains an invalid package name" }
    }
}

internal data class LinuxDistribution(
    val name: String,
    val version: String,
    val codename: String,
    val architecture: String,
    val androidAbi: String
)

internal data class LinuxRootfs(
    val source: String,
    val sourceRelease: String,
    val url: String,
    val sha256: String,
    val archiveFormat: String,
    val stripComponents: Int
)

internal data class AptSnapshot(
    val date: String,
    val debian: String,
    val security: String,
    val suite: String,
    val securitySuite: String,
    val component: String
)

internal data class LinuxPackages(
    val foundation: List<String>,
    val klipperMcuToolchain: List<String>
)

class LinuxEnvironmentRepositoryImpl(
    private val context: Context,
    private val logger: LoggerRepository
) : LinuxEnvironmentRepository {
    private val baseDirectory = File(context.filesDir, "miracle-klipper")
    private val stateDirectory = File(baseDirectory, "state")
    private val environmentArchitecture = LinuxArchitecture.resolve(context)
    private val rootfsDirectory = File(baseDirectory, "linux/debian-trixie-$environmentArchitecture")
    private val sharedDirectory = File(baseDirectory, "shared")
    private val stagingDirectory = File(baseDirectory, "staging/debian")
    private val logFile = File(baseDirectory, "logs/debian-install.log")
    private val installMutex = Mutex()
    private val state = MutableStateFlow(
        LinuxEnvironmentProgress(
            if (isReady) LinuxEnvironmentStage.Ready else LinuxEnvironmentStage.NotInstalled,
            message = if (isReady) "Debian 13 ready" else "Debian 13 not installed"
        )
    )

    override val progress: StateFlow<LinuxEnvironmentProgress>
        get() = state

    override val isReady: Boolean
        get() {
            val manifest = File(stateDirectory, "linux-environment.sha256")
            return manifest.isFile && manifest.readText().trim().equals(
                readExpectedHashOrEmpty(), ignoreCase = true
            ) && File(rootfsDirectory, "usr/bin/env").isFile &&
                File(rootfsDirectory, "usr/bin/apt-get").isFile &&
                File(rootfsDirectory, "usr/bin/python3").exists()
        }

    override suspend fun install() = withContext(Dispatchers.IO) {
        installMutex.withLock {
            if (isReady) {
                state.value = LinuxEnvironmentProgress(
                    LinuxEnvironmentStage.Ready,
                    message = "Debian 13 is already installed"
                )
                return@withLock
            }

            try {
                ensureSupportedArchitecture()
                val manifest = loadManifest()
                manifest.validate()
                ensureBootstrapTools()
                ensureStorage(manifest.minimumFreeStorageBytes)

                if (stagingDirectory.exists() && !stagingDirectory.deleteRecursively()) {
                    throw IOException("Could not remove the incomplete Debian installation")
                }
                ensureDirectoryExists(stagingDirectory)
                ensureDirectoryExists(sharedDirectory)
                ensureDirectoryExists(File(baseDirectory, "tmp"))
                ensureDirectoryExists(logFile.parentFile!!)
                logFile.writeText("")

                val archive = File(stagingDirectory, "debian-rootfs.tar.xz")
                downloadRootfs(manifest.rootfs, archive)
                state.value = LinuxEnvironmentProgress(
                    LinuxEnvironmentStage.Extracting,
                    message = "Extracting the verified Debian rootfs"
                )
                val stagedRootfs = File(stagingDirectory, "rootfs")
                extractRootfs(archive, stagedRootfs)
                configureDebian(stagedRootfs, manifest)

                state.value = LinuxEnvironmentProgress(
                    LinuxEnvironmentStage.InstallingPackages,
                    message = "Installing the pinned Debian package set"
                )
                installPackages(stagedRootfs, manifest)
                verifyEnvironment(stagedRootfs)
                activateRootfs(stagedRootfs, manifest.rootfs.sha256)

                if (stagingDirectory.exists()) stagingDirectory.deleteRecursively()
                state.value = LinuxEnvironmentProgress(
                    LinuxEnvironmentStage.Ready,
                    100,
                    "Debian 13 Trixie and its tools are ready"
                )
            } catch (cancelled: CancellationException) {
                if (stagingDirectory.exists()) stagingDirectory.deleteRecursively()
                state.value = LinuxEnvironmentProgress(
                    LinuxEnvironmentStage.Failed,
                    message = "Debian installation interrupted. Tap again to retry."
                )
                recordFailure("Debian installation interrupted", cancelled)
                logger.logError(this@LinuxEnvironmentRepositoryImpl,
                    "Debian installation interrupted", cancelled)
                throw cancelled
            } catch (error: Throwable) {
                if (stagingDirectory.exists()) stagingDirectory.deleteRecursively()
                val details = describeFailure(error)
                val message = "Debian environment installation failed: $details"
                state.value = LinuxEnvironmentProgress(
                    LinuxEnvironmentStage.Failed,
                    message = message
                )
                recordFailure(message, error)
                logger.logError(this@LinuxEnvironmentRepositoryImpl, message, error)
                if (error is Exception) throw IOException(message, error)
                throw error
            }
        }
    }

    private fun ensureSupportedArchitecture() {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" || it == "x86_64" }) {
            throw IOException("Miracle Klipper requires an ARM64 or x86_64 Android device")
        }
    }

    private fun loadManifest(): LinuxEnvironmentManifest {
        val assetName = if (environmentArchitecture == LinuxArchitecture.X86_64) {
            "linux-environment-x86_64.json"
        } else {
            "linux-environment.json"
        }
        return context.assets.open(assetName).bufferedReader().use { reader ->
            Gson().fromJson(reader, LinuxEnvironmentManifest::class.java)
                ?: throw IOException("The Debian environment manifest is empty")
        }
    }

    private fun readExpectedHashOrEmpty(): String = try {
        loadManifest().rootfs.sha256
    } catch (_: Exception) {
        ""
    }

    private fun ensureBootstrapTools() {
        val bootstrap = File(context.filesDir, "bootstrap")
        val minitar = File(bootstrap, "bin/minitar")
        val proot = File(bootstrap, "bin/proot")
        if (!File(bootstrap, "bootstrap").isDirectory || !minitar.isFile || !proot.isFile) {
            throw IOException("Install the Octo4a bootstrap before preparing Debian")
        }
    }

    private fun ensureStorage(requiredBytes: Long) {
        val available = context.filesDir.usableSpace
        if (available < requiredBytes) {
            val requiredGiB = requiredBytes / (1024.0 * 1024.0 * 1024.0)
            val availableGiB = available / (1024.0 * 1024.0 * 1024.0)
            throw IOException(
                "Debian needs at least %.1f GiB free; %.1f GiB is available".format(
                    java.util.Locale.US, requiredGiB, availableGiB
                )
            )
        }
    }

    private fun downloadRootfs(rootfs: LinuxRootfs, destination: File) {
        state.value = LinuxEnvironmentProgress(
            LinuxEnvironmentStage.Downloading,
            0,
            "Downloading Debian 13 rootfs"
        )
        val url = URL(rootfs.url)
        if (url.protocol != "https") throw IOException("Debian downloads must use HTTPS")

        var connection: HttpsURLConnection? = null
        try {
            val activeConnection = url.openConnection() as? HttpsURLConnection
                ?: throw IOException("Unable to open the Debian rootfs download")
            connection = activeConnection
            activeConnection.connectTimeout = 20_000
            activeConnection.readTimeout = 60_000
            activeConnection.connect()
            val response = activeConnection.responseCode
            if (response !in 200..299 || activeConnection.url.protocol != "https") {
                throw IOException("Debian rootfs download returned HTTP $response")
            }

            val contentLength = activeConnection.contentLength.toLong()
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            activeConnection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        if (contentLength > 0) {
                            val percent = ((downloaded * 100) / contentLength).toInt().coerceIn(0, 99)
                            state.value = LinuxEnvironmentProgress(
                                LinuxEnvironmentStage.Downloading,
                                percent,
                                "Downloading Debian 13 rootfs"
                            )
                        }
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(rootfs.sha256, ignoreCase = true)) {
                throw IOException("Debian rootfs checksum verification failed")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractRootfs(archive: File, stagedRootfs: File) {
        val rawDirectory = File(stagingDirectory, "unpacked")
        ensureDirectoryExists(rawDirectory)
        val minitar = File(context.filesDir, "bootstrap/bin/minitar")
        val process = ProcessBuilder(minitar.absolutePath, "-p")
            .directory(rawDirectory)
            .redirectErrorStream(true)
            .redirectInput(archive)
            .start()
        val exitCode = collectProcessOutput(process, "Debian rootfs extraction")
        if (exitCode != 0) throw IOException("Debian rootfs extraction failed (exit $exitCode)")

        val entries = rawDirectory.listFiles()?.toList().orEmpty()
        if (entries.size != 1 || !entries[0].isDirectory) {
            throw IOException("Debian rootfs archive did not have the expected top-level directory")
        }
        val topLevel = entries[0]
        if (!File(topLevel, "etc").isDirectory || !File(topLevel, "usr").isDirectory) {
            throw IOException("Debian rootfs archive has an unexpected directory layout")
        }
        if (!stagedRootfs.mkdirs()) throw IOException("Could not create the Debian rootfs staging directory")
        val children = topLevel.listFiles()?.toList().orEmpty()
        if (children.isEmpty()) throw IOException("Debian rootfs archive is empty")
        for (child in children) {
            if (!child.renameTo(File(stagedRootfs, child.name))) {
                throw IOException("Could not prepare Debian rootfs entry ${child.name}")
            }
        }
        // Android app storage cannot create device nodes. PRoot binds host /dev at runtime.
        val stagedDev = File(stagedRootfs, "dev")
        if (stagedDev.exists() && !stagedDev.deleteRecursively()) {
            throw IOException("Could not remove unsupported Debian /dev device nodes")
        }
        if (!stagedDev.mkdirs() && !stagedDev.isDirectory) {
            throw IOException("Could not create Debian /dev mount point")
        }
        if (topLevel.exists() && !topLevel.deleteRecursively()) {
            throw IOException("Could not remove the Debian archive wrapper directory")
        }
        if (!File(stagedRootfs, "usr/bin/env").isFile ||
            !File(stagedRootfs, "usr/bin/apt-get").isFile
        ) {
            throw IOException("The Debian rootfs is missing required base tools")
        }
    }

    private fun configureDebian(rootfs: File, manifest: LinuxEnvironmentManifest) {
        val aptDirectory = File(rootfs, "etc/apt")
        val sourcesDirectory = File(aptDirectory, "sources.list.d")
        if (sourcesDirectory.exists() && !sourcesDirectory.deleteRecursively()) {
            throw IOException("Could not replace Debian package sources")
        }
        if (!sourcesDirectory.mkdirs() && !sourcesDirectory.isDirectory) {
            throw IOException("Could not create Debian package source directory")
        }
        File(aptDirectory, "sources.list").delete()
        val sources = buildString {
            append("deb [check-valid-until=no] ${manifest.aptSnapshot.debian} ${manifest.aptSnapshot.suite} ${manifest.aptSnapshot.component}\n")
            append("deb [check-valid-until=no] ${manifest.aptSnapshot.security} ${manifest.aptSnapshot.securitySuite} ${manifest.aptSnapshot.component}\n")
        }
        File(aptDirectory, "sources.list").writeText(sources)
        File(aptDirectory, "apt.conf.d").mkdirs()
        File(aptDirectory, "apt.conf.d/99-miracle-klipper").writeText(
            "Acquire::Retries \"3\";\nAcquire::https::Timeout \"45\";\nDpkg::Use-Pty \"0\";\n"
        )
        ensureDirectoryExists(File(rootfs, "var/lib/miracle"))
        ensureDirectoryExists(File(sharedDirectory, "printer_data/config"))
        ensureDirectoryExists(File(sharedDirectory, "printer_data/logs"))
        ensureDirectoryExists(File(sharedDirectory, "printer_data/gcodes"))
        ensureDirectoryExists(File(sharedDirectory, "printer_data/comms"))
        ensureDirectoryExists(File(sharedDirectory, "backups"))

        configureResolver(rootfs)
    }

    private fun configureResolver(rootfs: File) {
        val resolver = resolverConfiguration()
        if (resolver.isNotBlank()) {
            val resolvConf = File(rootfs, "etc/resolv.conf")
            resolvConf.delete()
            resolvConf.writeText(resolver)
        }
    }

    private fun resolverConfiguration(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return ""
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ""
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.activeNetwork
        } else {
            manager.allNetworks.firstOrNull { manager.getNetworkInfo(it)?.isConnected == true }
        } ?: return ""
        val linkProperties = manager.getLinkProperties(network) ?: return ""
        val servers = linkProperties.dnsServers.map { it.hostAddress }.filter { !it.isNullOrBlank() }
        return if (servers.isEmpty()) "" else servers.joinToString(separator = "", postfix = "") {
            "nameserver $it\n"
        }
    }

    private fun installPackages(rootfs: File, manifest: LinuxEnvironmentManifest) {
        val policy = File(rootfs, "usr/sbin/policy-rc.d")
        val hadPolicy = policy.exists()
        val oldPolicy = if (hadPolicy) policy.readBytes() else null
        policy.parentFile?.mkdirs()
        policy.writeText("#!/bin/sh\nexit 101\n")
        policy.setExecutable(true, false)
        try {
            runInRootfs(rootfs, listOf("/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "update"))
            val packages = (manifest.packages.foundation + manifest.packages.klipperMcuToolchain).distinct()
            runInRootfs(
                rootfs,
                listOf("/usr/bin/apt-get", "install", "-y", "--no-install-recommends") + packages
            )
            runInRootfs(rootfs, listOf("/usr/bin/apt-get", "check"))
        } finally {
            if (hadPolicy && oldPolicy != null) {
                policy.writeBytes(oldPolicy)
            } else {
                policy.delete()
            }
        }
    }

    private fun verifyEnvironment(rootfs: File) {
        runInRootfs(rootfs, listOf("/usr/bin/python3", "--version"))
        runInRootfs(rootfs, listOf("/usr/bin/python3", "-m", "venv", "--help"))
        runInRootfs(rootfs, listOf("/usr/bin/git", "--version"))
    }

    private fun runInRootfs(rootfs: File, command: List<String>) {
        val bootstrapBin = File(context.filesDir, "bootstrap/bin")
        val proot = File(bootstrapBin, "proot")
        val temporaryDirectory = File(baseDirectory, "tmp")
        // PRoot's link2symlink backing files must live on the rootfs filesystem.
        // Keeping them in an app-level sibling can make dpkg's temporary hardlink
        // replacements disappear under Android SELinux while it applies metadata.
        val linkDirectory = File(rootfs, ".l2s")
        ensureDirectoryExists(temporaryDirectory)
        ensureDirectoryExists(linkDirectory)

        val args = mutableListOf(proot.absolutePath, "-r", rootfs.absolutePath, "-0")
        listOf("/dev", "/proc", "/storage", "/system", "/vendor", "/apex", "/sys")
            .filter { File(it).exists() }
            .forEach { args.addAll(listOf("-b", it)) }
        val serialPipe = File(context.filesDir, "serialpipe")
        if (serialPipe.exists()) args.addAll(listOf("-b", "${serialPipe.absolutePath}:/dev/ttyOcto4a"))
        val fakeProcStat = File(context.filesDir, "bootstrap/fake_proc_stat")
        if (fakeProcStat.isFile) args.addAll(listOf("-b", "${fakeProcStat.absolutePath}:/proc/stat"))
        args.addAll(listOf("-L", "--link2symlink", "-b", "${sharedDirectory.absolutePath}:/var/lib/miracle"))
        args.addAll(listOf("-w", "/root", "/usr/bin/env"))
        args.addAll(listOf(
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            // Do not let Android's TMPDIR leak into package maintainer scripts.
            // It points outside the PRoot rootfs and can make mktemp fail.
            "TMPDIR=/tmp",
            "TMP=/tmp",
            "TEMP=/tmp"
        ))
        args.addAll(command)

        val builder = ProcessBuilder(args).directory(bootstrapBin)
        builder.redirectErrorStream(true)
        builder.environment()["PROOT_NO_SECCOMP"] = "1"
        builder.environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
        builder.environment()["PROOT_L2S_DIR"] = linkDirectory.absolutePath
        builder.environment()["PWD"] = bootstrapBin.absolutePath
        builder.environment().remove("LD_PRELOAD")
        builder.environment().remove("LD_LIBRARY_PATH")
        val process = builder.start()
        val exitCode = collectProcessOutput(process, command.joinToString(" "))
        if (exitCode != 0) {
            throw IOException("Debian command failed with exit code $exitCode: ${command.first()}")
        }
    }

    private fun collectProcessOutput(process: Process, operation: String): Int {
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(logFile) {
                            logFile.appendText("$operation: $line\n")
                        }
                        logger.log(this, LogType.BOOTSTRAP) { "$operation: $line" }
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read $operation output", error)
            }
        }, "debian-environment-output")
        reader.isDaemon = true
        reader.start()
        try {
            return process.waitFor()
        } catch (interrupted: InterruptedException) {
            process.destroy()
            try {
                process.waitFor()
            } catch (interruptedAgain: InterruptedException) {
                process.destroy()
                interrupted.addSuppressed(interruptedAgain)
            }
            throw CancellationException("Debian installation interrupted during $operation", interrupted)
        } finally {
            try {
                reader.join()
            } catch (_: InterruptedException) {
                reader.interrupt()
            }
        }
    }

    private fun describeFailure(error: Throwable): String {
        val causes = generateSequence(error as Throwable?) { it.cause }
            .take(5)
            .map { cause ->
                val detail = cause.message?.trim().orEmpty()
                if (detail.isEmpty()) cause.javaClass.simpleName
                else "${cause.javaClass.simpleName}: $detail"
            }
            .toList()
        return causes.joinToString(" <- ").ifEmpty { error.javaClass.simpleName }
    }

    private fun recordFailure(message: String, error: Throwable) {
        try {
            ensureDirectoryExists(logFile.parentFile!!)
            synchronized(logFile) {
                logFile.appendText("\n$message\n${Log.getStackTraceString(error)}\n")
            }
        } catch (logError: Exception) {
            logger.logError(this, "Could not save Debian installation failure details", logError)
        }
    }

    private fun activateRootfs(stagedRootfs: File, expectedHash: String) {
        ensureDirectoryExists(rootfsDirectory.parentFile!!)
        val previousRootfs = File(rootfsDirectory.parentFile, "debian-trixie-$environmentArchitecture.previous")
        if (previousRootfs.exists() && !previousRootfs.deleteRecursively()) {
            throw IOException("Could not remove the previous Debian rootfs backup")
        }
        val stagedRootPath = stagedRootfs.absolutePath
        val activeRootPath = rootfsDirectory.absolutePath
        val hadRootfs = rootfsDirectory.exists()
        if (hadRootfs && !rootfsDirectory.renameTo(previousRootfs)) {
            throw IOException("Could not preserve the existing Debian rootfs")
        }
        if (!stagedRootfs.renameTo(rootfsDirectory)) {
            if (hadRootfs) previousRootfs.renameTo(rootfsDirectory)
            throw IOException("Could not activate the Debian rootfs")
        }

        try {
            // PRoot link2symlink stores emulated hard-link targets under rootfs/.l2s.
            // Debian packages are configured before activation, while the rootfs still
            // lives in the staging directory. Rewrite any absolute symlink targets that
            // refer to that old path before the next dpkg invocation.
            rewriteMovedRootfsSymlinks(rootfsDirectory, stagedRootPath, activeRootPath)
            ensureDirectoryExists(stateDirectory)
            File(stateDirectory, "linux-environment.sha256").writeText("$expectedHash\n")
        } catch (error: Exception) {
            rootfsDirectory.deleteRecursively()
            if (hadRootfs && !previousRootfs.renameTo(rootfsDirectory)) {
                error.addSuppressed(IOException("Could not restore the previous Debian rootfs"))
            }
            throw IOException("Could not finalize the Debian rootfs after moving its PRoot hard-link metadata", error)
        }
        if (previousRootfs.exists()) previousRootfs.deleteRecursively()
    }

    private fun rewriteMovedRootfsSymlinks(root: File, oldRootPath: String, newRootPath: String) {
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
        visit(root)
    }

    private fun ensureDirectoryExists(directory: File) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create directory ${directory.absolutePath}")
        }
    }
}
