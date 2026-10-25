package com.octo4a.repository

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipInputStream

interface DebianTerminalRepository {
    fun start(): String
    fun startKiauh(): String
    fun stop()
}

class DebianTerminalRepositoryImpl(
    private val context: Context,
    private val linuxEnvironment: LinuxEnvironmentRepository,
    private val logger: LoggerRepository
) : DebianTerminalRepository {
    private val baseDirectory = File(context.filesDir, "miracle-klipper")
    private val environmentArchitecture = LinuxArchitecture.resolve(context)
    private val rootfsDirectory = File(baseDirectory, "linux/debian-trixie-$environmentArchitecture")
    private val sharedDirectory = File(baseDirectory, "shared")
    private val kiauhDirectory = File(sharedDirectory, "kiauh")
    private val kiauhSudoShim = File(sharedDirectory, "kiauh-support/bin/sudo")
    private val bootstrapBin = File(baseDirectory, "bootstrap/bin")
    private val proot = File(bootstrapBin, "proot")
    private val ttyd = File(rootfsDirectory, "usr/bin/ttyd")
    private val temporaryDirectory = File(baseDirectory, "tmp")
    private val linkDirectory = File(rootfsDirectory, ".l2s")
    private var activeProcess: Process? = null
    private var activePort: Int? = null
    private var activeKiauhMode: Boolean? = null
    private val processLock = Any()
    private val recentOutput = java.util.ArrayDeque<String>()

    override fun start(): String = startTerminal(kiauhMode = false)

    override fun startKiauh(): String = startTerminal(kiauhMode = true)

    private fun startTerminal(kiauhMode: Boolean): String = synchronized(processLock) {
        if (!linuxEnvironment.isReady) {
            throw IOException("Installe Debian 13 depuis les paramètres avant d'ouvrir son terminal.")
        }
        activeProcess?.takeIf { isProcessAlive(it) }?.let { process ->
            if (activeKiauhMode == kiauhMode) {
                val port = activePort
                    ?: throw IOException("Le terminal Debian est déjà démarré, mais son port est inconnu.")
                return@synchronized "http://127.0.0.1:$port/"
            }
            process.destroy()
            activeProcess = null
            activePort = null
            activeKiauhMode = null
        }

        requireFile(proot, "Le moteur PRoot Android est absent. Réinstalle le composant PRoot de Miracle Klipper.")
        requireFile(File(rootfsDirectory, "usr/bin/env"), "Debian 13 est incomplète. Réinstalle l'environnement Debian.")
        requireFile(ttyd, "Le terminal web ttyd manque dans Debian 13. Vérifie l’installation du paquet ttyd.")

        ensureDirectory(temporaryDirectory)
        ensureDirectory(linkDirectory)
        ensureDirectory(sharedDirectory)
        if (kiauhMode) ensureKiauhFiles()

        // ttyd lie l'interface par nom ("lo") via getifaddrs, souvent bloqué sur Android récent.
        // On essaie d'abord l'adresse 127.0.0.1, puis le nom "lo" si ttyd se ferme aussitôt.
        var lastError: IOException? = null
        for (iface in TERMINAL_INTERFACES) {
            try {
                return@synchronized launchTerminal(kiauhMode, iface)
            } catch (error: TerminalExitedException) {
                lastError = error
                logger.log(this, LogType.EXTENSION) { "ttyd s'est arrêté avec --interface $iface" }
            }
        }
        throw lastError ?: IOException("Le terminal Debian n'a pas pu démarrer.")
    }

    private fun launchTerminal(kiauhMode: Boolean, iface: String): String {
        val port = findFreePort()
        val args = mutableListOf(proot.absolutePath, "-r", rootfsDirectory.absolutePath, "-0")
        listOf("/dev", "/proc", "/storage", "/system", "/vendor", "/apex", "/sys")
            .filter { File(it).exists() }
            .forEach { args.addAll(listOf("-b", it)) }

        val serialPipe = File(context.filesDir, "serialpipe")
        if (serialPipe.exists()) {
            args.addAll(listOf("-b", "${serialPipe.absolutePath}:/dev/ttyOcto4a"))
        }
        args.addAll(listOf("-L", "--link2symlink"))
        args.addAll(listOf("-b", "${sharedDirectory.absolutePath}:/var/lib/miracle"))
        args.addAll(listOf("-w", "/root", "/usr/bin/env"))
        args.addAll(
            listOf(
                "HOME=/root",
                "PATH=" + if (kiauhMode) {
                    "/var/lib/miracle/kiauh-support/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                } else {
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                },
                "LANG=C.UTF-8",
                "TERM=xterm-256color",
                "TMPDIR=/tmp",
                "TMP=/tmp",
                "TEMP=/tmp",
                "/usr/bin/ttyd",
                "--interface", iface,
                "--port", port.toString(),
                "--writable",
                "--check-origin",
                "--max-clients", "1"
            )
        )
        if (kiauhMode) {
            args.addAll(
                listOf(
                    "/bin/bash", "--login", "-c",
                    "export PATH=/var/lib/miracle/kiauh-support/bin:\$PATH; " +
                        "cd /var/lib/miracle/kiauh && bash ./kiauh.sh; " +
                        "printf '\\nKIAUH est terminé. Tu peux encore saisir des commandes Debian.\\n'; " +
                        "exec /bin/bash --login -i"
                )
            )
        } else {
            args.addAll(listOf("/bin/bash", "--login", "-i"))
        }

        val builder = ProcessBuilder(args)
            .directory(bootstrapBin)
            .redirectErrorStream(true)
        builder.environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
        builder.environment()["PROOT_L2S_DIR"] = linkDirectory.absolutePath
        builder.environment()["PWD"] = bootstrapBin.absolutePath
        builder.environment().remove("LD_PRELOAD")
        builder.environment().remove("LD_LIBRARY_PATH")

        logger.log(this, LogType.EXTENSION) { "Starting the in-app Debian terminal on 127.0.0.1:$port (interface $iface)" }
        synchronized(recentOutput) { recentOutput.clear() }
        val process = builder.start()
        activeProcess = process
        activePort = port
        activeKiauhMode = kiauhMode
        startOutputReader(process)

        try {
            waitForServer(process, port)
        } catch (error: Exception) {
            process.destroy()
            activeProcess = null
            activePort = null
            activeKiauhMode = null
            throw error
        }
        return "http://127.0.0.1:$port/"
    }

    override fun stop() {
        synchronized(processLock) {
            activeProcess?.destroy()
            activeProcess = null
            activePort = null
            activeKiauhMode = null
        }
    }

    private fun ensureKiauhFiles() {
        if (!File(kiauhDirectory, "kiauh.sh").isFile) {
            val extractionDirectory = File(sharedDirectory, "kiauh-extract")
            if (extractionDirectory.exists() && !extractionDirectory.deleteRecursively()) {
                throw IOException("Impossible de nettoyer l'extraction KIAUH incomplète.")
            }
            ensureDirectory(extractionDirectory)
            val extractionRoot = extractionDirectory.canonicalFile
            val extractionPrefix = extractionRoot.path + File.separator

            try {
                context.assets.open(KIAUH_ASSET).use { asset ->
                    ZipInputStream(BufferedInputStream(asset)).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val entryName = entry.name.replace('\\', '/')
                            if (!entryName.startsWith(KIAUH_ZIP_ROOT) || entryName.contains("../")) {
                                throw IOException("L'archive KIAUH contient un chemin inattendu.")
                            }
                            val relativeName = entryName.removePrefix(KIAUH_ZIP_ROOT)
                            if (relativeName.isNotEmpty()) {
                                val output = File(extractionRoot, relativeName).canonicalFile
                                if (!output.path.startsWith(extractionPrefix)) {
                                    throw IOException("L'archive KIAUH tente de sortir de son dossier.")
                                }
                                if (entry.isDirectory) {
                                    ensureDirectory(output)
                                } else {
                                    ensureDirectory(output.parentFile)
                                    FileOutputStream(output).use { destination -> zip.copyTo(destination) }
                                    if (output.extension == "sh") output.setExecutable(true, false)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
            } catch (error: Exception) {
                extractionDirectory.deleteRecursively()
                throw IOException("Impossible de préparer KIAUH depuis l'application.", error)
            }

            if (!File(extractionDirectory, "kiauh.sh").isFile) {
                extractionDirectory.deleteRecursively()
                throw IOException("L'archive intégrée ne contient pas le lanceur KIAUH.")
            }
            if (kiauhDirectory.exists() && !kiauhDirectory.deleteRecursively()) {
                throw IOException("Impossible de remplacer l'extraction KIAUH incomplète.")
            }
            if (!extractionDirectory.renameTo(kiauhDirectory)) {
                extractionDirectory.deleteRecursively()
                throw IOException("Impossible d'installer KIAUH dans l'environnement Debian.")
            }
        }

        ensureDirectory(kiauhSudoShim.parentFile)
        kiauhSudoShim.writeText("#!/bin/sh\nexec \"\$@\"\n")
        if (!kiauhSudoShim.setExecutable(true, false)) {
            throw IOException("Impossible de préparer le lanceur sudo isolé de KIAUH.")
        }
    }

    private fun waitForServer(process: Process, port: Int) {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        var lastConnectionError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessAlive(process)) {
                try {
                    Thread.sleep(300) // laisse le lecteur de sortie récupérer les dernières lignes
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val tail = synchronized(recentOutput) { recentOutput.joinToString("\n") }
                throw TerminalExitedException(
                    "Le serveur du terminal Debian s'est arrêté au démarrage." +
                        if (tail.isBlank()) "" else "\n$tail"
                )
            }
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                        SOCKET_CONNECT_TIMEOUT_MS
                    )
                }
                return
            } catch (error: Exception) {
                lastConnectionError = error
                try {
                    Thread.sleep(START_POLL_INTERVAL_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Démarrage du terminal Debian interrompu", interrupted)
                }
            }
        }
        throw IOException(
            "Le serveur du terminal Debian n'a pas répondu. Vérifie que Debian 13 et ttyd sont installés.",
            lastConnectionError
        )
    }

    private fun startOutputReader(process: Process) {
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(recentOutput) {
                            recentOutput.addLast(line)
                            while (recentOutput.size > 15) recentOutput.removeFirst()
                        }
                        logger.log(this, LogType.EXTENSION) { "[debian-terminal] $line" }
                    }
                }
            } catch (error: Exception) {
                logger.logError(this, "Could not read Debian terminal output", error)
            }
        }, "debian-terminal-output").apply {
            isDaemon = true
            start()
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun requireFile(file: File, message: String) {
        if (!file.isFile || !file.canExecute()) {
            throw IOException(message)
        }
    }

    private fun isProcessAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Impossible de créer le dossier ${directory.absolutePath}.")
        }
    }

    private class TerminalExitedException(message: String) : IOException(message)

    companion object {
        private const val KIAUH_ASSET = "kiauh/kiauh-master.zip"
        private const val KIAUH_ZIP_ROOT = "kiauh-master/"
        private const val START_TIMEOUT_MS = 30_000L
        private const val START_POLL_INTERVAL_MS = 100L
        private val TERMINAL_INTERFACES = listOf("127.0.0.1", "lo")
        private const val SOCKET_CONNECT_TIMEOUT_MS = 200
    }
}
