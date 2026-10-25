package com.octo4a.repository

import android.content.Context
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.octo4a.utils.*
import com.octo4a.viewmodel.BootstrapItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

interface BootstrapRepository {
    val commandsFlow: SharedFlow<String>
    val downloadProgressData: LiveData<Int>
    val isBootstrapInstallItemSelected: Boolean
    suspend fun downloadBootstrap()
    suspend fun extractBootstrap()
    fun runCommand(
        command: String,
        prooted: Boolean = true,
        root: Boolean = true,
        bash: Boolean = false
    ): Process

    fun ensureHomeDirectory()
    fun resetSSHPassword(newPassword: String)

    fun selectReleaseForInstallation(release: BootstrapItem)

    val isBootstrapInstalled: Boolean
    val isSSHConfigured: Boolean
    val bootstrapVersion: String
}

class BootstrapRepositoryImpl(
    private val logger: LoggerRepository,
    private val githubRepository: GithubRepository,
    val context: Context
) : BootstrapRepository {
    companion object {
        private val FILES_PATH = "/data/data/com.octo4a/files"
        val PREFIX_PATH = "$FILES_PATH/bootstrap"
        val HOME_PATH = "$FILES_PATH"
    }

    val filesPath: String by lazy {
        (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath
    }
    private var _commandsFlow = MutableSharedFlow<String>(100)
    private var _downloadProgressFlow = MutableStateFlow(0)
    private var _selectedGitHubRelease: BootstrapItem? = null
    override val commandsFlow: SharedFlow<String>
        get() = _commandsFlow

    override val downloadProgressData: LiveData<Int>
        get() = _downloadProgressFlow.asLiveData()

    override val isBootstrapInstallItemSelected: Boolean
        get() = _selectedGitHubRelease != null

    private fun shouldUsePre5Bootstrap(): Boolean {
        if (getArchString() != "arm" && getArchString() != "i686") {
            return false
        }

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
    }

    override fun selectReleaseForInstallation(release: BootstrapItem) {
        _selectedGitHubRelease = release
    }

    override suspend fun downloadBootstrap() {
        withContext(Dispatchers.IO) {
            _downloadProgressFlow.value = 0

            val prefixFile = File(PREFIX_PATH)
            if (prefixFile.isDirectory) {
                return@withContext
            }
            if (prefixFile.exists()) {
                throw IOException("Bootstrap destination exists but is not a directory")
            }

            if (_selectedGitHubRelease == null) {
                logger.log(this) { "No release selected for installation" }
                return@withContext
            }

            val stagingDirectory = File("${FILES_PATH}/bootstrap-staging")
            val storageDirectory = File(FILES_PATH)
            ensureDirectoryExists(storageDirectory)
            if (stagingDirectory.exists()) {
                deleteFolder(stagingDirectory)
            }
            ensureDirectoryExists(stagingDirectory)

            var connection: HttpsURLConnection? = null

            try {
                val arch = getArchString()
                logger.log(this) { "Arch: $arch" }

                val buffer = ByteArray(8096)
                val urlPrefix = _selectedGitHubRelease!!.assetUrl
                logger.log(this) { "Downloading bootstrap ${_selectedGitHubRelease?.bootstrapVersion} from $urlPrefix" }

                val noSSLv3Factory: SSLSocketFactory = TLSSocketFactory(context)
                val assetUrl = URL(urlPrefix)
                if (assetUrl.protocol != "https") {
                    throw IOException("Bootstrap downloads must use HTTPS")
                }

                val activeConnection = assetUrl.openConnection() as? HttpsURLConnection
                    ?: throw IOException("Unable to create HTTPS connection")
                connection = activeConnection
                activeConnection.connectTimeout = 20_000
                activeConnection.readTimeout = 30_000
                activeConnection.sslSocketFactory = noSSLv3Factory
                activeConnection.connect()
                val responseCode = activeConnection.responseCode
                logger.log(this) { "Request to ${activeConnection.url} returned status code $responseCode" }
                if (responseCode !in 200..299) {
                    throw RuntimeException(
                        "Fetching ${activeConnection.url} failed with status code $responseCode"
                    )
                }

                val contentLength = activeConnection.contentLength
                BootstrapArchiveSafety.requireEnoughSpace(storageDirectory)
                var extractedFiles = 0
                var lastSpaceCheckBytes = 0L

                ZipInputStream(ProgressTrackingInputStream(activeConnection.inputStream) { bytesRead ->
                    if (contentLength > 0) {
                        _downloadProgressFlow.value =
                            ((bytesRead.toDouble() / contentLength.toDouble()) * 100.0)
                                .toInt()
                                .coerceIn(0, 99)
                    }
                }).use { zipInput ->
                    var zipEntry = zipInput.nextEntry
                    while (zipEntry != null) {
                        val targetFile = BootstrapArchiveSafety.resolveEntryTarget(
                            stagingDirectory,
                            zipEntry.name
                        )
                        val isDirectory = zipEntry.isDirectory

                        ensureDirectoryExists(if (isDirectory) targetFile else targetFile.parentFile)

                        if (!isDirectory) {
                            if (zipEntry.size > 0) {
                                BootstrapArchiveSafety.requireEnoughSpace(
                                    storageDirectory,
                                    zipEntry.size
                                )
                            }
                            extractedFiles++
                            FileOutputStream(targetFile).use { outStream ->
                                while (true) {
                                    val readBytes = zipInput.read(buffer)
                                    if (readBytes == -1) break
                                    outStream.write(buffer, 0, readBytes)
                                    lastSpaceCheckBytes += readBytes
                                    if (lastSpaceCheckBytes >= 1024L * 1024L) {
                                        BootstrapArchiveSafety.requireEnoughSpace(storageDirectory)
                                        lastSpaceCheckBytes = 0
                                    }
                                }
                            }
                        }
                        zipEntry = zipInput.nextEntry
                    }
                }

                if (extractedFiles == 0 ||
                    !File(stagingDirectory, "entrypoint.sh").isFile ||
                    !File(stagingDirectory, "rootfs.tar.xz").isFile ||
                    !File(stagingDirectory, "bin/minitar").isFile
                ) {
                    throw IOException("Downloaded archive is not a valid Octo4a bootstrap")
                }

                if (!stagingDirectory.renameTo(prefixFile)) {
                    throw RuntimeException("Unable to rename staging folder")
                }
                _downloadProgressFlow.value = 100
            } catch (e: Exception) {
                if (stagingDirectory.exists()) {
                    try {
                        deleteFolder(stagingDirectory)
                    } catch (cleanupError: Exception) {
                        logger.log(this) { "Unable to clean incomplete bootstrap staging directory: $cleanupError" }
                    }
                }
                throw (e)
            } finally {
                connection?.disconnect()
            }
        }

    }

    override suspend fun extractBootstrap() {
        withContext(Dispatchers.IO) {
            logger.log(this) { "Bootstrap downloaded, extracting it..." }
            runCommand("ls", prooted = false).waitAndPrintOutput(logger)

            if (shouldUsePre5Bootstrap()) {
                runCommand(
                    "rm -rf bin && mv bin-pre5 bin && rm -rf libexec && mv libexec-pre5 libexec",
                    prooted = false
                ).waitAndPrintOutput(logger)
            }

            // First call inside of proot will automatically extract the bootstrap
            runCommand("ls").waitAndPrintOutput(logger)
            logger.log(this) { "Bootstrap extracted, setting it up..." }

            runCommand("chmod -R 755 /mnt/external/").waitAndPrintOutput(
                logger
            )

            runCommand("cp -rf /home/octoprint/extensions /mnt/external/").waitAndPrintOutput(
                logger
            )

            runCommand("chown root:root /mnt/external/").waitAndPrintOutput(
                logger
            )
            runCommand("mkdir -p /mnt/external/.octoprint/plugins").waitAndPrintOutput(logger)
            runCommand("cp /home/octoprint/comm-fix.py /mnt/external/.octoprint/plugins").waitAndPrintOutput(
                logger
            )
        }
    }

    private fun ensureDirectoryExists(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw RuntimeException("Unable to create directory: " + directory.absolutePath)
        }
    }

    /** Delete a folder and all its content or throw. Don't follow symlinks.  */
    @Throws(IOException::class)
    fun deleteFolder(fileOrDirectory: File) {
        if (fileOrDirectory.canonicalPath == fileOrDirectory.absolutePath && fileOrDirectory.isDirectory) {
            val children = fileOrDirectory.listFiles()

            if (children != null) {
                for (child in children) {
                    deleteFolder(child)
                }
            }
        }

        if (!fileOrDirectory.delete()) {
            throw RuntimeException("Unable to delete " + (if (fileOrDirectory.isDirectory) "directory " else "file ") + fileOrDirectory.absolutePath)
        }
    }

    override fun runCommand(
        command: String,
        prooted: Boolean,
        root: Boolean,
        bash: Boolean
    ): Process {
        logger.log { ">$command" }
        val FILES = "/data/data/com.octo4a/files"
        val directory = File(filesPath)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val pb = ProcessBuilder()
        pb.redirectErrorStream(true)
        pb.environment()["HOME"] = "$FILES/bootstrap"
        pb.environment()["LANG"] = "'en_US.UTF-8'"
        pb.environment()["PWD"] = "$FILES/bootstrap"
        logger.log(this) {filesPath}
        pb.environment()["EXTRA_BIND"] =
            "-b ${filesPath}:/mnt/external -b /data/data/com.octo4a/files/serialpipe:/dev/ttyOcto4a"
        pb.environment()["PATH"] =
            "/sbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
        pb.directory(File("$FILES/bootstrap"))
        var user = "root"
        if (!root) user = "octoprint"
        if (prooted) {
            // run inside proot
            val shell = if (bash) "/bin/bash" else "/bin/sh"
            pb.command("sh", "entrypoint.sh", user, shell, "-c", command)
        } else {
            pb.command("sh", "-c", command)
        }
        return pb.start()
    }

    override fun ensureHomeDirectory() {
        val homeFile = File(HOME_PATH)
        if (!homeFile.exists()) {
            homeFile.mkdir()
        }
    }

    override val isSSHConfigured: Boolean
        get() {
            return File("/data/data/com.octo4a/files/bootstrap/bootstrap/home/octoprint/.ssh_configured").exists()
        }

    override val bootstrapVersion: String
        get() = runCommand("cat build-version.txt", prooted = false).getOutputAsString()

    override fun resetSSHPassword(newPassword: String) {
        logger.log(this) { "Deleting password just in case" }
        try {
            runCommand("passwd -d octoprint").waitAndPrintOutput(logger)
        } catch (e: java.lang.Exception) {
            logger.log(this) { "Failed to delete password: $e" }
        }

        runCommand("passwd octoprint").setPassword(newPassword)
        try {
            runCommand("passwd -d root").waitAndPrintOutput(logger)
        } catch (e: java.lang.Exception) {
            logger.log(this) { "Failed to delete password: $e" }
        }
        runCommand("passwd root").setPassword(newPassword)
        runCommand("touch .ssh_configured", root = false)
        runCommand("touch .ssh_configured", root = true)
    }

    override val isBootstrapInstalled: Boolean
        get() = File("$FILES_PATH/bootstrap/bootstrap").exists()
}
