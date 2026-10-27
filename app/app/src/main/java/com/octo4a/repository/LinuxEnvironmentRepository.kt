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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipInputStream
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
                File(rootfsDirectory, "usr/bin/python3").exists() &&
                File(baseDirectory, "bootstrap/bin/proot").canExecute() &&
                File(baseDirectory, "bootstrap/libexec/proot").listFiles()?.isNotEmpty() == true &&
                File(baseDirectory, "bootstrap/rootfs/debian-trixie.tar.xz").isFile &&
                File(baseDirectory, "bootstrap/android-proot-version").let {
                    it.isFile && it.readText().trim() in setOf(
                        "android-bionic-proot-v1",
                        "android-bionic-proot-v2-pinned"
                    )
                }
        }

    override suspend fun install() = withContext(Dispatchers.IO) {
        installMutex.withLock {
            try {
                ensureSupportedArchitecture()
                val manifest = loadManifest()
                manifest.validate()

                // Refresh old bootstrap assets independently of the Debian rootfs.
                // This avoids reinstalling Debian just to replace a PRoot binary.
                ensureBootstrapTools(manifest.rootfs)
                if (isReady) {
                    state.value = LinuxEnvironmentProgress(
                        LinuxEnvironmentStage.Ready,
                        message = "Debian 13 is already installed"
                    )
                    return@withLock
                }

                ensureStorage(manifest.minimumFreeStorageBytes)

                if (stagingDirectory.exists() && !stagingDirectory.deleteRecursively()) {
                    throw IOException("Could not remove the incomplete Debian installation; close any active installer process and retry")
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

    private fun ensureBootstrapTools(rootfs: LinuxRootfs) {
        val bootstrapDirectory = File(baseDirectory, "bootstrap")
        val proot = File(bootstrapDirectory, "bin/proot")
        val loader = File(bootstrapDirectory, "libexec/proot")
        val bundledRootfs = File(bootstrapDirectory, "rootfs/debian-trixie.tar.xz")
        val prootVersion = File(bootstrapDirectory, "android-proot-version")
        if (proot.isFile && proot.canExecute() && loader.isDirectory &&
            !loader.listFiles().isNullOrEmpty() && bundledRootfs.isFile &&
            prootVersion.isFile && prootVersion.readText().trim() == "android-bionic-proot-v2-pinned") return
        val arch = when (environmentArchitecture) { LinuxArchitecture.ARM64 -> "arm64"; LinuxArchitecture.X86_64 -> "x86_64"; else -> throw IOException("Unsupported Miracle Klipper bootstrap architecture: $environmentArchitecture") }
        val baseUrl = "https://github.com/atelierdesmiracles/miracles-klipper-/releases/download/miracle-klipper-debian13"
        val bundleName = "miracle-klipper-debian13-$arch.zip"
        val staging = File(baseDirectory, "staging/bootstrap-download")
        if (staging.exists() && !staging.deleteRecursively()) throw IOException("Could not clear the previous bootstrap download")
        ensureDirectoryExists(staging)
        val archive = File(staging, bundleName)
        downloadHttps("$baseUrl/$bundleName", archive)
        val checksumFile = File(staging, "$bundleName.sha256")
        downloadHttps("$baseUrl/$bundleName.sha256", checksumFile)
        val expectedBundleHash = checksumFile.readText().trim().split(Regex("\\s+")).firstOrNull() ?: throw IOException("Miracle Klipper bootstrap checksum file is empty")
        if (!expectedBundleHash.matches(Regex("[a-fA-F0-9]{64}"))) throw IOException("Miracle Klipper bootstrap checksum file is invalid")
        val bundleDigest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input -> val buffer = ByteArray(DEFAULT_BUFFER_SIZE); while (true) { val count = input.read(buffer); if (count < 0) break; bundleDigest.update(buffer, 0, count) } }
        val actualBundleHash = bundleDigest.digest().joinToString("") { "%02x".format(it) }
        if (!actualBundleHash.equals(expectedBundleHash, ignoreCase = true)) { staging.deleteRecursively(); throw IOException("Miracle Klipper bootstrap SHA-256 verification failed") }
        val extracted = File(staging, "extracted")
        ensureDirectoryExists(extracted)
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalized = entry.name.replace('\\', '/')
                if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) throw IOException("Unsafe path in Miracle Klipper bootstrap archive")
                val target = File(extracted, normalized).canonicalFile
                val prefix = extracted.canonicalPath + File.separator
                if (target != extracted.canonicalFile && !target.path.startsWith(prefix)) throw IOException("Bootstrap archive entry escapes staging directory")
                if (entry.isDirectory) ensureDirectoryExists(target) else { ensureDirectoryExists(target.parentFile!!); FileOutputStream(target).use { output -> zip.copyTo(output) } }
                zip.closeEntry()
            }
        }
        val manifest = File(extracted, "bootstrap-manifest.txt")
        val manifestText = if (manifest.isFile) manifest.readText() else ""
        if (!manifestText.contains("version=13-trixie") || !manifestText.contains("architecture=$arch") || !manifestText.contains("rootfs_sha256=${rootfs.sha256}")) { staging.deleteRecursively(); throw IOException("Miracle Klipper bootstrap metadata does not match this device or Debian manifest") }
        val bundledProot = File(extracted, "bin/proot")
        val bundledLoader = File(extracted, "libexec/proot")
        val extractedRootfs = File(extracted, "rootfs/debian-trixie.tar.xz")
        if (!bundledProot.isFile || !bundledLoader.isDirectory || bundledLoader.listFiles().isNullOrEmpty() || !extractedRootfs.isFile) {
            staging.deleteRecursively()
            throw IOException("Miracle Klipper bootstrap bundle is incomplete")
        }

        // The release bundle already contains the Android/Bionic PRoot binary and its
        // loader sidecars, all covered by the verified bundle SHA-256. Use these pinned
        // assets directly instead of downloading a second, floating "latest" binary.
        if (bootstrapDirectory.exists() && !bootstrapDirectory.deleteRecursively()) {
            throw IOException("Could not replace the old bootstrap directory")
        }
        ensureDirectoryExists(File(bootstrapDirectory, "bin"))
        ensureDirectoryExists(File(bootstrapDirectory, "libexec"))
        ensureDirectoryExists(File(bootstrapDirectory, "rootfs"))
        ensureDirectoryExists(loader)

        bundledProot.copyTo(proot, overwrite = true)
        if (!proot.setExecutable(true, false)) {
            throw IOException("Could not mark bundled Android PRoot executable")
        }

        val bundledLoaderFiles = bundledLoader.listFiles()
            ?: throw IOException("Could not read bundled PRoot loader sidecars")
        if (bundledLoaderFiles.none { it.isFile && it.name == "loader" }) {
            throw IOException("Miracle Klipper bootstrap bundle is missing the PRoot loader")
        }
        for (sidecar in bundledLoaderFiles) {
            if (!sidecar.isFile) continue
            val destination = File(loader, sidecar.name)
            sidecar.copyTo(destination, overwrite = true)
            if (!destination.setExecutable(true, false)) {
                throw IOException("Could not mark PRoot sidecar executable: ${sidecar.name}")
            }
        }

        extractedRootfs.copyTo(bundledRootfs, overwrite = true)
        manifest.copyTo(File(bootstrapDirectory, "bootstrap-manifest.txt"), overwrite = true)
        File(bootstrapDirectory, "android-proot-version").writeText("android-bionic-proot-v2-pinned")
        staging.deleteRecursively()
    }

    private fun downloadHttps(urlText: String, destination: File) {
        val url = URL(urlText)
        if (url.protocol != "https") throw IOException("Bootstrap downloads must use HTTPS")
        var connection: HttpsURLConnection? = null
        try {
            val active = url.openConnection() as? HttpsURLConnection ?: throw IOException("Unable to open bootstrap download")
            connection = active; active.connectTimeout = 20_000; active.readTimeout = 120_000; active.connect()
            if (active.responseCode !in 200..299 || active.url.protocol != "https") throw IOException("Bootstrap download returned HTTP ${active.responseCode}")
            active.inputStream.use { input -> FileOutputStream(destination).use { output -> input.copyTo(output) } }
        } finally { connection?.disconnect() }
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
        state.value = LinuxEnvironmentProgress(LinuxEnvironmentStage.Downloading, 0, "Preparing verified Debian 13 rootfs")
        val bundledRootfs = File(baseDirectory, "bootstrap/rootfs/debian-trixie.tar.xz")
        if (bundledRootfs.isFile) {
            copyAndVerifyRootfs(bundledRootfs, destination, rootfs.sha256)
            return
        }
        val url = URL(rootfs.url)
        if (url.protocol != "https") throw IOException("Debian downloads must use HTTPS")
        var connection: HttpsURLConnection? = null
        try {
            val active = url.openConnection() as? HttpsURLConnection ?: throw IOException("Unable to open the Debian rootfs download")
            connection = active
            active.connectTimeout = 20_000
            active.readTimeout = 60_000
            active.connect()
            if (active.responseCode !in 200..299 || active.url.protocol != "https") throw IOException("Debian rootfs download returned HTTP ${active.responseCode}")
            val contentLength = active.contentLength.toLong()
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            active.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        if (contentLength > 0) state.value = LinuxEnvironmentProgress(LinuxEnvironmentStage.Downloading, ((downloaded * 100) / contentLength).toInt().coerceIn(0, 99), "Downloading Debian 13 rootfs")
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(rootfs.sha256, ignoreCase = true)) { destination.delete(); throw IOException("Debian rootfs checksum verification failed") }
        } finally { connection?.disconnect() }
    }

    private fun copyAndVerifyRootfs(source: File, destination: File, expectedSha256: String) {
        source.inputStream().use { input -> FileOutputStream(destination).use { output -> input.copyTo(output) } }
        val digest = MessageDigest.getInstance("SHA-256")
        destination.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualHash.equals(expectedSha256, ignoreCase = true)) { destination.delete(); throw IOException("Bundled Debian rootfs checksum verification failed") }
    }
    private fun extractRootfs(archive: File, stagedRootfs: File) {
        if (stagedRootfs.exists() && !stagedRootfs.deleteRecursively()) {
            throw IOException("Could not clear the Debian rootfs staging directory")
        }
        ensureDirectoryExists(stagedRootfs)
        val canonicalRoot = stagedRootfs.canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        var extractedEntries = 0
        XZCompressorInputStream(archive.inputStream().buffered()).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val normalizedName = entry.name.replace('\\', '/')
                    val components = normalizedName.split('/').filter { it.isNotEmpty() && it != "." }
                    // proot-distro archives contain a single wrapper directory.
                    if (components.size <= 1) continue
                    if (components.any { it == ".." } || normalizedName.startsWith("/")) {
                        throw IOException("Unsafe path in Debian rootfs archive: ${entry.name}")
                    }
                    val relative = components.drop(1).joinToString(File.separator)
                    if (relative.isEmpty()) continue
                    // Android app storage cannot create or safely remove Linux device nodes.
                    // Do not extract anything under /dev; PRoot binds the host /dev at runtime.
                    // Skipping it also avoids deleteRecursively() failures on archive-specific
                    // /dev layouts and symlinks.
                    if (relative == "dev" || relative.startsWith("dev/")) continue
                    val target = File(canonicalRoot, relative).canonicalFile
                    if (!target.path.startsWith(rootPrefix)) {
                        throw IOException("Debian rootfs archive entry escapes staging directory")
                    }
                    when {
                        entry.isDirectory -> ensureDirectoryExists(target)
                        entry.isSymbolicLink -> {
                            ensureDirectoryExists(target.parentFile)
                            if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
                            // Absolute links are intentionally preserved for PRoot's guest-root semantics.
                            Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName))
                        }
                        entry.isFile -> {
                            ensureDirectoryExists(target.parentFile)
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
                        }
                        entry.isLink -> {
                            ensureDirectoryExists(target.parentFile)
                            val linkParts = entry.linkName.replace('\\', '/').split('/')
                                .filter { it.isNotEmpty() && it != "." }
                            if (linkParts.any { it == ".." }) {
                                throw IOException("Unsafe hard link in Debian rootfs archive")
                            }
                            val sourceRelative = linkParts.drop(1).joinToString(File.separator)
                            val source = File(canonicalRoot, sourceRelative).canonicalFile
                            if (!source.path.startsWith(rootPrefix) || !source.isFile) {
                                throw IOException("Unsupported hard link in Debian rootfs archive: ${entry.name}")
                            }
                            Files.createLink(target.toPath(), source.toPath())
                        }
                    }
                    extractedEntries++
                }
            }
        }
        if (extractedEntries == 0) throw IOException("Debian rootfs archive is empty")
        // /dev entries were skipped during extraction because Android app storage cannot
        // create Linux device nodes. PRoot supplies the runtime /dev bind mount.
        ensureDirectoryExists(File(stagedRootfs, "dev"))
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
        installKlipperStack(rootfs)
    }

    private fun installKlipperStack(rootfs: File) {
        state.value = LinuxEnvironmentProgress(
            LinuxEnvironmentStage.InstallingPackages,
            90,
            "Installing Klipper, Moonraker and Mainsail"
        )
        val installer = File(rootfs, "tmp/install-miracle-klipper.sh")
        ensureDirectoryExists(installer.parentFile!!)
        context.assets.open("linux/install-miracle-klipper.sh").use { input ->
            FileOutputStream(installer).use { output -> input.copyTo(output) }
        }
        if (!installer.setExecutable(true, false)) {
            throw IOException("Could not prepare the Miracle Klipper installer")
        }
        runInRootfs(rootfs, listOf("/bin/bash", "/tmp/install-miracle-klipper.sh"))
        installer.delete()
    }

    private fun verifyEnvironment(rootfs: File) {
        runInRootfs(rootfs, listOf("/usr/bin/python3", "--version"))
        runInRootfs(rootfs, listOf("/usr/bin/python3", "-m", "venv", "--help"))
        runInRootfs(rootfs, listOf("/usr/bin/git", "--version"))
    }

    private fun runInRootfs(rootfs: File, command: List<String>) {
        val bootstrapBin = File(baseDirectory, "bootstrap/bin")
        val proot = File(bootstrapBin, "proot")
        val temporaryDirectory = File(baseDirectory, "tmp")
        // Use a private app-owned temporary directory for PRoot's runtime files.
        // Do not enable PRoot's link2symlink emulation here: on some Android kernels
        // the legacy Octo4a PRoot build fails chmod/chdir while initializing that layer.
        // The extracted rootfs uses real hard links where supported by app storage.
        ensureDirectoryExists(temporaryDirectory)

        val args = mutableListOf(proot.absolutePath, "-r", rootfs.absolutePath, "-0")
        listOf("/dev", "/proc", "/storage", "/system", "/vendor", "/apex", "/sys")
            .filter { File(it).exists() }
            .forEach { args.addAll(listOf("-b", it)) }
        val serialPipe = File(context.filesDir, "serialpipe")
        if (serialPipe.exists()) args.addAll(listOf("-b", "${serialPipe.absolutePath}:/dev/ttyOcto4a"))
        val fakeProcStat = File(context.filesDir, "bootstrap/fake_proc_stat")
        if (fakeProcStat.isFile) args.addAll(listOf("-b", "${fakeProcStat.absolutePath}:/proc/stat"))
        args.addAll(listOf("-b", "${sharedDirectory.absolutePath}:/var/lib/miracle"))
        // Do not require /usr/bin/env to be executable in the freshly unpacked rootfs.
        // PRoot forwards the host process environment to the guest command.
        // Use / as the initial working directory; /root may not yet be usable on every device.
        args.addAll(listOf("-w", "/", command.first()))
        args.addAll(command.drop(1))

        val builder = ProcessBuilder(args).directory(bootstrapBin)
        builder.redirectErrorStream(true)
        builder.environment()["PROOT_NO_SECCOMP"] = "1"
        builder.environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
        builder.environment().remove("PROOT_L2S_DIR")
        builder.environment()["PWD"] = "/"
        builder.environment()["HOME"] = "/root"
        builder.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        builder.environment()["LANG"] = "C.UTF-8"
        builder.environment()["DEBIAN_FRONTEND"] = "noninteractive"
        builder.environment()["TMPDIR"] = "/tmp"
        builder.environment()["TMP"] = "/tmp"
        builder.environment()["TEMP"] = "/tmp"
        builder.environment().remove("LD_PRELOAD")
        builder.environment().remove("LD_LIBRARY_PATH")
        val process = builder.start()
        val outputTail = java.util.Collections.synchronizedList(mutableListOf<String>())
        val operation = command.joinToString(" ")
        val exitCode = collectProcessOutput(process, operation, outputTail)
        if (exitCode != 0) {
            val details = synchronized(outputTail) { outputTail.takeLast(20).joinToString("\n") }
            val suffix = if (details.isBlank()) "" else "\nLast command output:\n$details"
            throw IOException("Debian command failed with exit code $exitCode: $operation$suffix")
        }
    }

    private fun collectProcessOutput(
        process: Process,
        operation: String,
        outputTail: MutableList<String>
    ): Int {
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(outputTail) {
                            outputTail.add(line)
                            if (outputTail.size > 40) outputTail.removeAt(0)
                        }
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
