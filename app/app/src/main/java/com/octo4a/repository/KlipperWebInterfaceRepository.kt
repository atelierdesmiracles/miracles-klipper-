package com.octo4a.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.octo4a.utils.preferences.MainPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal data class WebInterfaceAsset(
    val version: String,
    val repository: String,
    val url: String,
    @SerializedName("archiveSizeBytes") val archiveSizeBytes: Long,
    val sha256: String
)

internal data class WebInterfaceManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    @SerializedName("minimumFreeStorageBytes") val minimumFreeStorageBytes: Long,
    val clients: Map<String, WebInterfaceAsset>
) {
    fun validate() {
        require(schemaVersion == 1) { "Unsupported web interface manifest" }
        require(minimumFreeStorageBytes >= 512L * 1024L * 1024L) {
            "Web interface storage requirement is too small"
        }
        require(clients.keys == setOf("mainsail", "fluidd")) {
            "Web interface manifest must pin Mainsail and Fluidd"
        }
        clients.forEach { (client, asset) ->
            require(asset.version.matches(Regex("v\\d+(\\.\\d+)+"))) {
                client + " version must be a pinned release tag"
            }
            val officialRepository = when (client) {
                "mainsail" -> "mainsail-crew/mainsail"
                "fluidd" -> "fluidd-core/fluidd"
                else -> ""
            }
            require(asset.repository == officialRepository) {
                client + " must use its official repository"
            }
            val officialUrl = "https://github.com/" + asset.repository +
                "/releases/download/" + asset.version + "/" + client + ".zip"
            require(asset.url == officialUrl) {
                client + " archive must use its official HTTPS release URL"
            }
            require(asset.archiveSizeBytes in 1024L..(64L * 1024L * 1024L)) {
                client + " archive size is outside the allowed range"
            }
            require(asset.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                client + " archive must have a pinned SHA-256 digest"
            }
        }
    }
}

enum class KlipperWebInterface(val key: String, val displayName: String) {
    Mainsail("mainsail", "Mainsail"),
    Fluidd("fluidd", "Fluidd");

    companion object {
        fun fromPreference(value: String?): KlipperWebInterface =
            values().firstOrNull { it.key == value } ?: Mainsail
    }
}

enum class WebInterfaceStage {
    NotInstalled, InstallingNginx, Downloading, Extracting,
    Installed, Starting, Ready, Stopping, Stopped, Failed
}

data class WebInterfaceProgress(
    val stage: WebInterfaceStage,
    val message: String,
    val client: KlipperWebInterface? = null,
    val version: String? = null,
    val moonrakerReachable: Boolean? = null
)

interface KlipperWebInterfaceRepository {
    val progress: StateFlow<WebInterfaceProgress>
    val selectedClient: KlipperWebInterface
    val isRunning: Boolean

    fun isInstalled(client: KlipperWebInterface): Boolean
    suspend fun install(client: KlipperWebInterface)
    suspend fun start()
    suspend fun stop()
    fun abort()

    companion object {
        const val LOCAL_URL = "http://127.0.0.1:8080/"
    }
}

class KlipperWebInterfaceRepositoryImpl(
    private val context: Context,
    private val linuxEnvironment: LinuxEnvironmentRepository,
    private val preferences: MainPreferences,
    private val logger: LoggerRepository
) : KlipperWebInterfaceRepository {
    private val baseDirectory = File(context.filesDir, "miracle-klipper")
    private val environmentArchitecture = LinuxArchitecture.resolve(context)
    private val rootfsDirectory = File(baseDirectory, "linux/debian-trixie-$environmentArchitecture")
    private val sharedDirectory = File(baseDirectory, "shared")
    private val webRootDirectory = File(sharedDirectory, "webui")
    private val nginxDirectory = File(sharedDirectory, "nginx")
    private val installLog = File(baseDirectory, "logs/web-interface-install.log")
    private val runtimeLog = File(sharedDirectory, "printer_data/logs/nginx-process.log")
    private val installMutex = Mutex()
    private val runtimeMutex = Mutex()
    private val processLock = Any()
    private val manifest: WebInterfaceManifest by lazy { loadManifest() }
    private val state = MutableStateFlow(initialProgress())
    private var activeProcess: ActiveNginxProcess? = null

    override val progress: StateFlow<WebInterfaceProgress>
        get() = state

    override val selectedClient: KlipperWebInterface
        get() = KlipperWebInterface.fromPreference(preferences.selectedKlipperWebInterface)

    override val isRunning: Boolean
        get() = synchronized(processLock) {
            activeProcess?.let { processIsAlive(it.process) } ?: false
        }

    private fun initialProgress(): WebInterfaceProgress {
        val client = runCatching { selectedClient }.getOrNull()
        return if (client != null && isInstalled(client)) {
            WebInterfaceProgress(
                WebInterfaceStage.Installed,
                client.displayName + " is installed; select it and open the Klipper interface",
                client,
                runCatching { release(client).version }.getOrNull()
            )
        } else {
            WebInterfaceProgress(WebInterfaceStage.NotInstalled, "Install Mainsail or Fluidd to continue")
        }
    }

    override fun isInstalled(client: KlipperWebInterface): Boolean = try {
        val asset = release(client)
        val directory = installedDirectory(client, asset.version)
        val marker = File(directory, RELEASE_MARKER)
        directory.isDirectory &&
            File(directory, "index.html").isFile &&
            marker.isFile &&
            marker.readText().trim() == asset.version + "\n" + asset.sha256.toLowerCase(Locale.US) &&
            File(rootfsDirectory, "usr/sbin/nginx").isFile
    } catch (_: Exception) {
        false
    }

    override suspend fun install(client: KlipperWebInterface) = withContext(Dispatchers.IO) {
        installMutex.withLock {
            if (isInstalled(client)) {
                state.value = WebInterfaceProgress(
                    WebInterfaceStage.Installed,
                    client.displayName + " is already installed",
                    client,
                    release(client).version
                )
                return@withLock
            }

            var archive: File? = null
            var staging: File? = null
            try {
                if (!linuxEnvironment.isReady) {
                    throw IOException("Prepare the Debian 13 environment before installing a web interface")
                }
                manifest.validate()
                if (context.filesDir.usableSpace < manifest.minimumFreeStorageBytes) {
                    throw IOException("At least 512 MiB of free storage is required for a web interface")
                }
                ensureDirectoryExists(installLog.parentFile!!)
                ensureDirectoryExists(File(sharedDirectory, "printer_data/logs"))
                ensureDirectoryExists(webRootDirectory)
                installLog.writeText("")
                ensureNginxInstalled()

                val asset = release(client)
                val clientDirectory = File(webRootDirectory, client.key)
                ensureDirectoryExists(clientDirectory)
                archive = File(File(baseDirectory, "staging"), client.key + "-" + asset.version + ".zip")
                staging = File(clientDirectory, "." + asset.version + ".staging")
                deleteDirectoryIfPresent(staging)
                ensureDirectoryExists(archive.parentFile!!)

                state.value = WebInterfaceProgress(
                    WebInterfaceStage.Downloading,
                    "Downloading " + client.displayName + " " + asset.version,
                    client,
                    asset.version
                )
                downloadArchive(asset, archive, client)

                state.value = WebInterfaceProgress(
                    WebInterfaceStage.Extracting,
                    "Verifying and extracting " + client.displayName,
                    client,
                    asset.version
                )
                extractArchive(archive, staging)
                configureClient(client, staging)
                if (!File(staging, "index.html").isFile) {
                    throw IOException(client.displayName + " archive does not contain a root index.html")
                }
                File(staging, RELEASE_MARKER).writeText(
                    asset.version + "\n" + asset.sha256.toLowerCase(Locale.US) + "\n"
                )

                val target = installedDirectory(client, asset.version)
                if (target.exists() && !target.deleteRecursively()) {
                    throw IOException("Could not replace the previous " + client.displayName + " files")
                }
                if (!staging.renameTo(target)) {
                    throw IOException("Could not activate the " + client.displayName + " files")
                }
                state.value = WebInterfaceProgress(
                    WebInterfaceStage.Installed,
                    client.displayName + " " + asset.version + " is installed",
                    client,
                    asset.version
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "Could not install " + client.displayName + ": " + describeFailure(error)
                recordFailure(message, error)
                state.value = WebInterfaceProgress(WebInterfaceStage.Failed, message, client)
                throw IOException(message, error)
            } finally {
                archive?.delete()
                staging?.let { deleteDirectoryIfPresent(it) }
            }
        }
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        try {
            runtimeMutex.withLock {
            val client = selectedClient
            val asset = release(client)
            if (!isInstalled(client)) {
                throw IOException("Install " + client.displayName + " before opening the Klipper interface")
            }

            synchronized(processLock) {
                activeProcess?.takeIf { processIsAlive(it.process) }?.let { active ->
                    if (active.client == client && isStaticInterfaceHealthy()) {
                        state.value = readyProgress(client, asset.version)
                        return@withLock
                    }
                }
            }
            stopProcess()

            if (!linuxEnvironment.isReady) throw IOException("Debian 13 is not ready")
            prepareNginxDirectories()
            writeNginxConfiguration(client, asset)
            state.value = WebInterfaceProgress(
                WebInterfaceStage.Starting,
                "Starting Nginx on port " + WEB_PORT,
                client,
                asset.version
            )
            if (!isPortAvailable()) throw IOException("Port " + WEB_PORT + " is already in use on this phone")

            runInRootfs(
                listOf(NGINX_BINARY, "-t", "-c", guestPath(nginxConfigFile), "-p", nginxPrefix()),
                "Validate Nginx configuration"
            )
            val process = createProotProcess(
                listOf(NGINX_BINARY, "-g", "daemon off;", "-c", guestPath(nginxConfigFile), "-p", nginxPrefix()),
                guestPath(nginxDirectory)
            ).start()
            val active = ActiveNginxProcess(process, client)
            synchronized(processLock) { activeProcess = active }
            drainRuntimeOutput(active)
            monitorRuntimeProcess(active)

            var staticReady = false
            for (attempt in 0 until STARTUP_ATTEMPTS) {
                if (!processIsAlive(process)) {
                    throw IOException("Nginx stopped during startup; see printer_data/logs/nginx-error.log")
                }
                if (isStaticInterfaceHealthy()) {
                    staticReady = true
                    break
                }
                delay(STARTUP_RETRY_MS)
            }
            if (!staticReady) {
                stopProcess()
                throw IOException("Nginx did not serve the selected interface on port " + WEB_PORT)
            }
                state.value = readyProgress(client, asset.version)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val client = selectedClient
            val message = "Could not start ${client.displayName}: ${describeFailure(error)}"
            state.value = WebInterfaceProgress(
                WebInterfaceStage.Failed,
                message,
                client,
                runCatching { release(client).version }.getOrNull()
            )
            recordFailure(message, error)
            logger.logError(this@KlipperWebInterfaceRepositoryImpl, message, error)
            throw IOException(message, error)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        runtimeMutex.withLock { stopProcess() }
    }

    override fun abort() {
        val active = synchronized(processLock) {
            activeProcess.also { activeProcess = null }
        } ?: return
        active.stopRequested = true
        active.process.destroy()
    }

    private val nginxConfigFile: File
        get() = File(nginxDirectory, "nginx.conf")

    private fun readyProgress(client: KlipperWebInterface, version: String): WebInterfaceProgress {
        // Test Moonraker directly and through Nginx separately so the UI can
        // distinguish a stopped API from a broken reverse-proxy route.
        val directApiReachable = isMoonrakerDirectApiHealthy()
        val proxiedApiReachable = isMoonrakerApiHealthy()
        val message = when {
            proxiedApiReachable ->
                client.displayName + " is ready at " + KlipperWebInterfaceRepository.LOCAL_URL +
                    "; Moonraker API is reachable through Nginx"
            directApiReachable ->
                client.displayName + " is served, and Moonraker responds on port 7125, " +
                    "but Nginx cannot proxy /server/info; check printer_data/logs/nginx-error.log"
            else ->
                client.displayName + " is served at " + KlipperWebInterfaceRepository.LOCAL_URL +
                    ", but Moonraker API is not responding; start Moonraker and check " +
                    "printer_data/logs/moonraker-process.log"
        }
        return WebInterfaceProgress(
            WebInterfaceStage.Ready, message, client, version, proxiedApiReachable
        )
    }

    private fun ensureNginxInstalled() {
        if (File(rootfsDirectory, "usr/sbin/nginx").isFile) return
        state.value = WebInterfaceProgress(WebInterfaceStage.InstallingNginx, "Installing Nginx in Debian 13")
        val policyFile = File(rootfsDirectory, "usr/sbin/policy-rc.d")
        val previousPolicy = if (policyFile.isFile) policyFile.readBytes() else null
        if (previousPolicy == null) {
            ensureDirectoryExists(policyFile.parentFile!!)
            policyFile.writeText("#!/bin/sh\nexit 101\n")
            policyFile.setExecutable(true, false)
        }
        try {
            runInRootfs(
                listOf("/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "update"),
                "Refresh Debian package metadata for Nginx"
            )
            runInRootfs(
                listOf("/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "install", "-y", "--no-install-recommends", "nginx-light"),
                "Install Nginx"
            )
            runInRootfs(listOf("/usr/bin/apt-get", "check"), "Check Nginx dependencies")
            if (!File(rootfsDirectory, "usr/sbin/nginx").isFile) {
                throw IOException("The nginx-light package did not install /usr/sbin/nginx")
            }
        } finally {
            if (previousPolicy != null) {
                policyFile.writeBytes(previousPolicy)
            } else if (policyFile.exists() && !policyFile.delete()) {
                logger.log(this, LogType.BOOTSTRAP) { "Could not remove temporary policy-rc.d" }
            }
        }
    }

    private fun downloadArchive(asset: WebInterfaceAsset, destination: File, client: KlipperWebInterface) {
        val connection = URL(asset.url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/zip")
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException(client.displayName + " download returned HTTP " + status)
            }
            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                throw IOException(client.displayName + " download redirected away from HTTPS")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > asset.archiveSizeBytes) {
                            throw IOException(client.displayName + " archive is larger than its pinned size")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (downloaded != asset.archiveSizeBytes) {
                throw IOException(
                    client.displayName + " archive size mismatch: expected " + asset.archiveSizeBytes +
                        ", received " + downloaded
                )
            }
            val actualHash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (!actualHash.equals(asset.sha256, ignoreCase = true)) {
                throw IOException(client.displayName + " archive SHA-256 verification failed")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractArchive(archive: File, destination: File) {
        ensureDirectoryExists(destination)
        val canonicalRoot = destination.canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        var extractedBytes = 0L
        var entries = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ARCHIVE_ENTRIES) throw IOException("Web interface archive contains too many files")
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.contains('\u0000') ||
                    name.split('/').any { it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(name)
                ) {
                    throw IOException("Unsafe path in web interface archive")
                }
                val output = File(destination, name).canonicalFile
                if (output.path != canonicalRoot.path && !output.path.startsWith(rootPrefix)) {
                    throw IOException("Web interface archive attempted to write outside its install folder")
                }
                if (entry.isDirectory) {
                    ensureDirectoryExists(output)
                } else {
                    ensureDirectoryExists(output.parentFile!!)
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extractedBytes += count
                            if (extractedBytes > MAX_EXTRACTED_BYTES) {
                                throw IOException("Web interface archive expands beyond the safe size limit")
                            }
                            stream.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun configureClient(client: KlipperWebInterface, directory: File) {
        val configFile = File(directory, "config.json")
        if (client == KlipperWebInterface.Mainsail && !configFile.isFile) {
            throw IOException("Mainsail archive is missing config.json")
        }
        val config = if (configFile.isFile) {
            val parsed = JsonParser().parse(configFile.readText())
            if (!parsed.isJsonObject) throw IOException(client.displayName + " config.json is invalid")
            parsed.asJsonObject
        } else {
            JsonObject()
        }
        when (client) {
            KlipperWebInterface.Mainsail -> config.addProperty("instancesDB", "browser")
            KlipperWebInterface.Fluidd -> config.add("endpoints", JsonArray())
        }
        configFile.writeText(Gson().toJson(config))
    }

    private fun writeNginxConfiguration(client: KlipperWebInterface, asset: WebInterfaceAsset) {
        val uiRoot = guestPath(installedDirectory(client, asset.version))
        val pidPath = guestPath(File(nginxDirectory, "nginx.pid"))
        val errorLogPath = guestPath(File(sharedDirectory, "printer_data/logs/nginx-error.log"))
        val accessLogPath = guestPath(File(sharedDirectory, "printer_data/logs/nginx-access.log"))
        val clientBodyTempPath = guestPath(File(nginxDirectory, "client_body_temp"))
        val proxyTempPath = guestPath(File(nginxDirectory, "proxy_temp"))
        val config = """
            worker_processes 1;
            pid $pidPath;
            error_log $errorLogPath notice;

            events {
                worker_connections 128;
            }

            http {
                include /etc/nginx/mime.types;
                default_type application/octet-stream;
                access_log $accessLogPath;
                sendfile on;
                keepalive_timeout 65;
                client_body_temp_path $clientBodyTempPath;
                proxy_temp_path $proxyTempPath;

                map §http_upgrade §connection_upgrade {
                    default upgrade;
                    '' close;
                }

                upstream moonraker_api {
                    server 127.0.0.1:7125;
                }

                server {
                    listen 0.0.0.0:$WEB_PORT;
                    server_name _;
                    root $uiRoot;
                    index index.html;
                    server_tokens off;
                    client_max_body_size 0;
                    proxy_request_buffering off;

                    allow 127.0.0.1;
                    allow 10.0.0.0/8;
                    allow 172.16.0.0/12;
                    allow 192.168.0.0/16;
                    allow 169.254.0.0/16;
                    deny all;

                    location / {
                        try_files §uri §uri/ /index.html;
                    }

                    location = /index.html {
                        add_header Cache-Control "no-store, no-cache, must-revalidate";
                    }

                    location = /websocket {
                        proxy_pass http://moonraker_api/websocket;
                        proxy_http_version 1.1;
                        proxy_set_header Upgrade §http_upgrade;
                        proxy_set_header Connection §connection_upgrade;
                        proxy_set_header Host 127.0.0.1:7125;
                        proxy_set_header X-Real-IP §remote_addr;
                        proxy_set_header X-Forwarded-For §proxy_add_x_forwarded_for;
                        proxy_set_header X-Scheme §scheme;
                        proxy_read_timeout 86400;
                    }

                    location ~ ^/(printer|api|access|machine|server)/ {
                        proxy_pass http://moonraker_api§request_uri;
                        proxy_http_version 1.1;
                        proxy_set_header Upgrade §http_upgrade;
                        proxy_set_header Connection §connection_upgrade;
                        proxy_set_header Host 127.0.0.1:7125;
                        proxy_set_header X-Real-IP §remote_addr;
                        proxy_set_header X-Forwarded-For §proxy_add_x_forwarded_for;
                        proxy_set_header X-Scheme §scheme;
                    }
                }
            }
            """.trimIndent().replace('§', '$') + "\n"
        nginxConfigFile.writeText(config)
    }

    private fun prepareNginxDirectories() {
        listOf(
            nginxDirectory,
            File(nginxDirectory, "client_body_temp"),
            File(nginxDirectory, "proxy_temp"),
            File(sharedDirectory, "printer_data/logs")
        ).forEach { ensureDirectoryExists(it) }
        runtimeLog.writeText("")
    }

    private fun isStaticInterfaceHealthy(): Boolean = try {
        val connection = URL(KlipperWebInterfaceRepository.LOCAL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = HEALTH_TIMEOUT_MS
        connection.readTimeout = HEALTH_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        try {
            connection.responseCode == HttpURLConnection.HTTP_OK &&
                connection.inputStream.bufferedReader().use { it.readLine() != null }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private fun isMoonrakerApiHealthy(): Boolean =
        isApiEndpointHealthy(KlipperWebInterfaceRepository.LOCAL_URL + "server/info")

    private fun isMoonrakerDirectApiHealthy(): Boolean =
        isApiEndpointHealthy("http://127.0.0.1:7125/server/info")

    private fun isApiEndpointHealthy(endpoint: String): Boolean = try {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = HEALTH_TIMEOUT_MS
        connection.readTimeout = HEALTH_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                false
            } else {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                body.contains("\\\"moonraker_version\\\":") &&
                    !body.contains("\\\"error\\\":")
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private fun isPortAvailable(): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("0.0.0.0", WEB_PORT))
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun stopProcess() {
        val active = synchronized(processLock) {
            activeProcess.also { if (it != null) it.stopRequested = true }
        } ?: return
        state.value = WebInterfaceProgress(WebInterfaceStage.Stopping, "Stopping Nginx", active.client)
        try {
            runInRootfs(
                listOf(NGINX_BINARY, "-s", "quit", "-c", guestPath(nginxConfigFile), "-p", nginxPrefix()),
                "Stop Nginx"
            )
        } catch (error: Exception) {
            logger.logError(this, "Could not request a graceful Nginx stop", error)
        }
        try {
            if (!active.process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                active.process.destroy()
                if (!active.process.waitFor(STOP_FORCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    active.process.destroyForcibly()
                }
            }
        } catch (interrupted: InterruptedException) {
            active.process.destroy()
            Thread.currentThread().interrupt()
        } finally {
            synchronized(processLock) {
                if (activeProcess === active) activeProcess = null
            }
            state.value = WebInterfaceProgress(WebInterfaceStage.Stopped, "Nginx is stopped", active.client)
        }
    }

    private fun monitorRuntimeProcess(active: ActiveNginxProcess) {
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
                        WebInterfaceProgress(WebInterfaceStage.Stopped, "Nginx is stopped", active.client)
                    } else {
                        WebInterfaceProgress(
                            WebInterfaceStage.Failed,
                            "Nginx exited with code " + exitCode + "; see printer_data/logs/nginx-error.log",
                            active.client
                        )
                    }
                }
            }
        }, "nginx-runtime-monitor").apply {
            isDaemon = true
            start()
        }
    }

    private fun drainRuntimeOutput(active: ActiveNginxProcess) {
        Thread({
            try {
                active.process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(runtimeLog) { runtimeLog.appendText(line + "\n") }
                        logger.log(this, LogType.BOOTSTRAP) { "Nginx: " + line }
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read Nginx process output", error)
            }
        }, "nginx-runtime-output").apply {
            isDaemon = true
            start()
        }
    }

    private fun runInRootfs(command: List<String>, operation: String): String {
        val process = createProotProcess(command, "/var/lib/miracle").start()
        val output = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) { output.append(line).append('\n') }
                        appendInstallLog(operation, line)
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read " + operation + " output", error)
            }
        }, "nginx-install-output")
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
            throw CancellationException("Interrupted while running " + operation, interrupted)
        } finally {
            try {
                reader.join()
            } catch (_: InterruptedException) {
                reader.interrupt()
            }
        }
        val result = synchronized(output) { output.toString() }
        if (exitCode != 0) {
            val detail = result.trim()
            throw IOException(
                operation + " failed with exit code " + exitCode +
                    if (detail.isNotEmpty()) ": " + detail else ""
            )
        }
        return result
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
        if (serialPipe.exists()) args.addAll(listOf("-b", serialPipe.absolutePath + ":/dev/ttyOcto4a"))
        val fakeProcStat = ProotRuntime.fakeProcStat(context)
        if (fakeProcStat.isFile) args.addAll(listOf("-b", fakeProcStat.absolutePath + ":/proc/stat"))
        args.addAll(listOf("-L", "--link2symlink", "-b", sharedDirectory.absolutePath + ":/var/lib/miracle"))
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

    private fun guestPath(file: File): String {
        val relative = file.relativeTo(sharedDirectory).path.replace(File.separatorChar, '/')
        return "/var/lib/miracle/" + relative
    }

    private fun nginxPrefix(): String = guestPath(nginxDirectory).trimEnd('/') + "/"

    private fun release(client: KlipperWebInterface): WebInterfaceAsset =
        manifest.clients[client.key] ?: throw IOException("No pinned " + client.displayName + " release is configured")

    private fun installedDirectory(client: KlipperWebInterface, version: String): File =
        File(File(webRootDirectory, client.key), version)

    private fun loadManifest(): WebInterfaceManifest {
        val parsed = context.assets.open(MANIFEST_ASSET).bufferedReader().use { JsonParser().parse(it) }
        val value = Gson().fromJson(parsed, WebInterfaceManifest::class.java)
            ?: throw IOException("Web interface asset manifest is empty")
        value.validate()
        return value
    }

    private fun appendInstallLog(operation: String, line: String) {
        try {
            synchronized(installLog) { installLog.appendText(operation + ": " + line + "\n") }
            logger.log(this, LogType.BOOTSTRAP) { operation + ": " + line }
        } catch (error: Exception) {
            logger.logError(this, "Could not save Nginx installation output", error)
        }
    }

    private fun recordFailure(message: String, error: Throwable) {
        try {
            ensureDirectoryExists(installLog.parentFile!!)
            synchronized(installLog) {
                installLog.appendText("\n" + message + "\n" + Log.getStackTraceString(error) + "\n")
            }
        } catch (logError: Exception) {
            logger.logError(this, "Could not save web interface installation failure details", logError)
        }
    }

    private fun describeFailure(error: Throwable): String = generateSequence(error as Throwable?) { it.cause }
        .take(5)
        .map { cause -> cause.message?.trim()?.takeIf { it.isNotEmpty() } ?: cause.javaClass.simpleName }
        .joinToString(" <- ")

    private fun ensureDirectoryExists(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create " + directory.absolutePath)
        }
    }

    private fun deleteDirectoryIfPresent(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Could not remove " + directory.absolutePath)
        }
    }

    private fun processIsAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private class ActiveNginxProcess(
        val process: Process,
        val client: KlipperWebInterface,
        @Volatile var stopRequested: Boolean = false
    )

    companion object {
        private const val MANIFEST_ASSET = "klipper-web-interfaces.json"
        private const val RELEASE_MARKER = ".miracle-klipper-release"
        private const val NGINX_BINARY = "/usr/sbin/nginx"
        private const val WEB_PORT = 8080
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_EXTRACTED_BYTES = 256L * 1024L * 1024L
        private const val MAX_ARCHIVE_ENTRIES = 30000
        private const val DOWNLOAD_TIMEOUT_MS = 45000
        private const val HEALTH_TIMEOUT_MS = 1500
        private const val STARTUP_ATTEMPTS = 30
        private const val STARTUP_RETRY_MS = 500L
        private const val STOP_TIMEOUT_MS = 5000L
        private const val STOP_FORCE_TIMEOUT_MS = 2000L
    }
}
