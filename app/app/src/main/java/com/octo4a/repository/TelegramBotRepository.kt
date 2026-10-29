package com.octo4a.repository

import android.content.Context
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

enum class TelegramBotStage { NotInstalled, Installing, NeedsConfiguration, Installed, Starting, Running, Stopping, Stopped, Failed }
data class TelegramBotProgress(val stage: TelegramBotStage, val message: String)

interface TelegramBotRepository {
    val progress: StateFlow<TelegramBotProgress>
    val isInstalled: Boolean
    val isRunning: Boolean
    suspend fun install()
    fun configure(token: String, chatId: String)
    suspend fun start()
    fun stop()
}

/**
 * Runs the upstream Moonraker Telegram bot as an app-managed PRoot process.
 * It deliberately does not use systemd/init.d or require Android root.
 */
class TelegramBotRepositoryImpl(
    private val context: Context,
    private val linuxEnvironment: LinuxEnvironmentRepository,
    private val moonraker: MoonrakerRepository,
    private val logger: LoggerRepository
) : TelegramBotRepository {
    private val base = File(context.filesDir, "miracle-klipper")
    private val arch = LinuxArchitecture.resolve(context)
    private val rootfs = File(base, "linux/debian-trixie-" + arch)
    private val shared = File(base, "shared")
    private val software = File(shared, "software")
    private val stateDir = File(base, "state")
    private val logFile = File(base, "logs/telegram-install.log")
    private val runtimeLog = File(shared, "printer_data/logs/telegram-process.log")
    private val prefs = context.getSharedPreferences("miracle_telegram_bot", Context.MODE_PRIVATE)
    private val revision = "96a7fb91d2f27153f4424032c1e758074dabb9aa"
    private val repository = "https://github.com/nlef/moonraker-telegram-bot.git"
    private val source = File(software, "telegram-bot-" + revision)
    private val venv = File(software, "telegram-bot-venv-" + revision)
    private val config = File(shared, "printer_data/config/telegram.conf")
    private val lock = Any()
    private val installMutex = Mutex()
    private val startMutex = Mutex()
    private var process: Process? = null
    private val mutableProgress = MutableStateFlow(initialProgress())
    override val progress: StateFlow<TelegramBotProgress> get() = mutableProgress

    override val isInstalled: Boolean
        get() {
            val marker = File(stateDir, "telegram-bot-revision.txt")
            return marker.isFile && runCatching { marker.readText().trim() == revision }.getOrDefault(false) &&
                File(source, "bot/main.py").isFile && File(venv, "bin/python").isFile
        }
    override val isRunning: Boolean
        get() = synchronized(lock) { process?.let { runCatching { it.isAlive }.getOrDefault(false) } == true }

    private fun hasConfig() = !prefs.getString("token", null).isNullOrBlank() &&
        !prefs.getString("chat_id", null).isNullOrBlank()
    private fun initialProgress() = when {
        !isInstalled -> TelegramBotProgress(TelegramBotStage.NotInstalled, "Telegram bot is not installed")
        !hasConfig() -> TelegramBotProgress(TelegramBotStage.NeedsConfiguration, "Configure the Telegram bot token and chat ID")
        else -> TelegramBotProgress(TelegramBotStage.Installed, "Telegram bot is installed")
    }

    override fun configure(token: String, chatId: String) {
        require(token.matches(Regex("[0-9]{5,}:[A-Za-z0-9_-]{20,}"))) { "Telegram token format is invalid" }
        require(chatId.matches(Regex("-?[0-9]{1,20}"))) { "Telegram chat ID must be numeric" }
        prefs.edit().putString("token", token).putString("chat_id", chatId).apply()
        ensureDir(config.parentFile!!)
        writeConfig()
        mutableProgress.value = initialProgress()
    }

    override suspend fun install() = withContext(Dispatchers.IO) {
        installMutex.withLock {
            if (isInstalled) { mutableProgress.value = initialProgress(); return@withLock }
            if (!linuxEnvironment.isReady) throw IOException("Install the Debian 13 environment first")
            if (!moonraker.isInstalled) throw IOException("Install Moonraker before the Telegram bot")
            if (context.filesDir.usableSpace < 536870912L) throw IOException("At least 512 MiB of free storage is required")
            ensureDir(software); ensureDir(stateDir); ensureDir(logFile.parentFile!!)
            logFile.writeText("")
            try {
                mutableProgress.value = TelegramBotProgress(TelegramBotStage.Installing, "Downloading pinned Telegram bot source")
                deleteTree(source); deleteTree(venv)
                run(listOf("/usr/bin/git", "init", guest(source)), "Initialize Telegram bot")
                run(listOf("/usr/bin/git", "-C", guest(source), "remote", "add", "origin", repository), "Configure Telegram bot source")
                run(listOf("/usr/bin/git", "-C", guest(source), "fetch", "--depth=1", "origin", revision), "Download Telegram bot source")
                run(listOf("/usr/bin/git", "-C", guest(source), "checkout", "--detach", "FETCH_HEAD"), "Checkout Telegram bot source")
                val checked = run(listOf("/usr/bin/git", "-C", guest(source), "rev-parse", "HEAD"), "Verify Telegram bot source").trim()
                if (!checked.equals(revision, true)) throw IOException("Telegram bot source revision verification failed")
                mutableProgress.value = TelegramBotProgress(TelegramBotStage.Installing, "Installing Telegram bot dependencies")
                run(listOf("/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "update"), "Refresh Debian packages")
                run(listOf("/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0", "install", "-y", "--no-install-recommends",
                    "ffmpeg", "libjpeg62-turbo", "libwebp7", "libtiff6", "libopenjp2-7", "libuv1", "python3-venv"), "Install Telegram system dependencies")
                run(listOf("/usr/bin/python3", "-m", "venv", "--system-site-packages", guest(venv)), "Create Telegram virtual environment")
                run(listOf(guest(File(venv, "bin/python")), "-m", "pip", "install", "--disable-pip-version-check", "--no-input", "--no-cache-dir",
                    "-r", guest(File(source, "scripts/requirements.txt"))), "Install Telegram Python dependencies")
                run(listOf(guest(File(venv, "bin/python")), "-m", "pip", "check"), "Validate Telegram Python dependencies")
                ensureDir(config.parentFile!!)
                ensureDir(runtimeLog.parentFile!!)
                writeConfig()
                File(stateDir, "telegram-bot-revision.txt").writeText(revision + "\n")
                mutableProgress.value = initialProgress()
            } catch (cancelled: CancellationException) {
                mutableProgress.value = TelegramBotProgress(TelegramBotStage.Failed, "Telegram bot installation interrupted")
                throw cancelled
            } catch (error: Exception) {
                val message = "Telegram bot installation failed: " + (error.message ?: error.javaClass.simpleName)
                mutableProgress.value = TelegramBotProgress(TelegramBotStage.Failed, message)
                logger.logError(this@TelegramBotRepositoryImpl, message, error)
                throw IOException(message, error)
            }
        }
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        startMutex.withLock {
            if (!isInstalled) throw IOException("Install the Telegram bot first")
            if (!hasConfig()) throw IOException("Configure the Telegram bot token and chat ID first")
            if (!moonraker.isInstalled) throw IOException("Moonraker is not installed")
            if (isRunning) return@withLock
            writeConfig()
            ensureDir(runtimeLog.parentFile!!)
            runtimeLog.writeText("")
            mutableProgress.value = TelegramBotProgress(TelegramBotStage.Starting, "Starting Telegram bot")
            val command = listOf(guest(File(venv, "bin/python")), guest(File(source, "bot/main.py")), "-c", guest(config), "-l", guest(runtimeLog))
            val child = createProot(command, guest(source)).start()
            synchronized(lock) { process = child }
            Thread({
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> appendLog(runtimeLog, line); logger.log(this, LogType.BOOTSTRAP) { "Telegram bot: " + line } }
                    }
                } catch (error: Exception) { logger.logError(this, "Could not read Telegram bot output", error) }
            }, "telegram-bot-output").apply { isDaemon = true; start() }
            Thread({
                val code = try { child.waitFor() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); -1 }
                synchronized(lock) { if (process === child) process = null }
                mutableProgress.value = if (code == 0) TelegramBotProgress(TelegramBotStage.Stopped, "Telegram bot stopped")
                    else TelegramBotProgress(TelegramBotStage.Failed, "Telegram bot exited with code " + code + "; see telegram-process.log")
            }, "telegram-bot-monitor").apply { isDaemon = true; start() }
            delay(1200)
            if (!child.isAlive) throw IOException("Telegram bot exited during startup; inspect telegram-process.log")
            mutableProgress.value = TelegramBotProgress(TelegramBotStage.Running, "Telegram bot is running")
        }
    }

    override fun stop() {
        val child = synchronized(lock) { process.also { process = null } }
        if (child == null) { mutableProgress.value = TelegramBotProgress(TelegramBotStage.Stopped, "Telegram bot is stopped"); return }
        mutableProgress.value = TelegramBotProgress(TelegramBotStage.Stopping, "Stopping Telegram bot")
        Thread({
            child.destroy()
            try { if (!child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor() } }
            catch (_: InterruptedException) { child.destroyForcibly(); Thread.currentThread().interrupt() }
            mutableProgress.value = TelegramBotProgress(TelegramBotStage.Stopped, "Telegram bot is stopped")
        }, "telegram-bot-stop").apply { isDaemon = true; start() }
    }

    private fun writeConfig() {
        val token = prefs.getString("token", null) ?: return
        val chat = prefs.getString("chat_id", null) ?: return
        ensureDir(config.parentFile!!)
        config.writeText("[bot]\nserver: 127.0.0.1\nport: 7125\nchat_id: " + chat + "\nbot_token: " + token +
            "\nservices: moonraker, klipper\ndebug: false\n")
    }

    private fun guest(file: File) = "/var/lib/miracle/" + file.relativeTo(shared).path.replace(File.separatorChar, '/')
    private fun createProot(command: List<String>, workingDirectory: String): ProcessBuilder {
        val bin = ProotRuntime.nativeDirectory(context)
        val proot = ProotRuntime.proot(context)
        if (!File(rootfs, "usr/bin/env").isFile) throw IOException("Debian 13 environment is unavailable")
        ProotRuntime.requireAvailable(context)
        val temp = File(base, "tmp"); ensureDir(temp)
        val link = File(rootfs, ".l2s"); ensureDir(link); ensureDir(shared)
        val args = mutableListOf(proot.absolutePath, "-r", rootfs.absolutePath, "-0")
        listOf("/dev", "/proc", "/storage", "/system", "/vendor", "/apex", "/sys").filter { File(it).exists() }.forEach { args.addAll(listOf("-b", it)) }
        val serial = File(context.filesDir, "serialpipe")
        if (serial.exists()) args.addAll(listOf("-b", serial.absolutePath + ":/dev/ttyOcto4a"))
        val fake = ProotRuntime.fakeProcStat(context)
        if (fake.isFile) args.addAll(listOf("-b", fake.absolutePath + ":/proc/stat"))
        args.addAll(listOf("-L", "--link2symlink", "-b", shared.absolutePath + ":/var/lib/miracle", "-w", workingDirectory, "/usr/bin/env"))
        val env = linkedMapOf("HOME" to "/var/lib/miracle", "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8", "DEBIAN_FRONTEND" to "noninteractive", "TMPDIR" to "/tmp", "GIT_TERMINAL_PROMPT" to "0",
            "PIP_DISABLE_PIP_VERSION_CHECK" to "1", "PIP_NO_INPUT" to "1", "PIP_NO_CACHE_DIR" to "1", "PYTHONUNBUFFERED" to "1")
        env.forEach { (key, value) -> args.add(key + "=" + value) }; args.addAll(command)
        return ProcessBuilder(args).directory(bin).redirectErrorStream(true).apply {
            environment()["PROOT_TMP_DIR"] = temp.absolutePath
            environment()["PROOT_L2S_DIR"] = link.absolutePath
            environment().remove("LD_PRELOAD"); environment().remove("LD_LIBRARY_PATH")
            ProotRuntime.applyLoader(this, context)
        }
    }

    private fun run(command: List<String>, label: String): String {
        val child = createProot(command, "/var/lib/miracle").start()
        val output = StringBuilder()
        val reader = Thread({
            try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.append(it).append('\n'); appendLog(logFile, label + ": " + it) } } }
            catch (error: Exception) { logger.logError(this, "Could not read " + label, error) }
        }, "telegram-install-output").apply { isDaemon = true; start() }
        val code = try { child.waitFor() } catch (e: InterruptedException) {
            child.destroy(); Thread.currentThread().interrupt(); throw CancellationException("Telegram installation interrupted", e)
        } finally { try { reader.join() } catch (_: InterruptedException) { reader.interrupt() } }
        if (code != 0) throw IOException(label + " failed (exit " + code + "): " + output.lines().filter { it.isNotBlank() }.takeLast(10).joinToString("\n"))
        return output.toString()
    }
    private fun ensureDir(file: File) { if (!file.isDirectory && !file.mkdirs()) throw IOException("Could not create directory: " + file.absolutePath) }
    private fun deleteTree(file: File) { if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }; if (file.exists() && !file.delete()) throw IOException("Could not delete " + file.absolutePath) }
    private fun appendLog(file: File, line: String) { synchronized(file.absolutePath.intern()) { ensureDir(file.parentFile!!); file.appendText(line + "\n") } }
}
