package com.octo4a.bootstrap

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Downloads and safely installs the pinned Miracle Klipper Debian rootfs.
 *
 * NOTE: Installing/extracting this archive does not start the Linux services;
 * runtime/PRoot integration is a separate step.
 */
class DebianRootfsInstaller(
    context: Context,
    private val onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    data class Result(val rootfsDirectory: File, val archiveSha256: String)

    fun installAsync(callback: (Result) -> Unit, onError: (Throwable) -> Unit): Future<*> =
        executor.submit {
            try {
                val result = install()
                callback(result)
            } catch (error: Throwable) {
                onError(error)
            }
        }

    fun install(): Result {
        val base = File(appContext.filesDir, "miracle-klipper")
        val downloads = File(base, "downloads")
        val staging = File(base, "rootfs-staging")
        val active = File(base, "rootfs")
        if (!downloads.exists() && !downloads.mkdirs()) error("Impossible de créer le dossier de téléchargement.")
        if (!base.exists() && !base.mkdirs()) error("Impossible de créer le dossier de l'environnement.")

        val archive = File(downloads, "miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz")
        val expectedSha = EXPECTED_SHA256
        val activeSize = if (active.exists()) estimateDirectorySize(active) else 0L
        val requiredBytes = REQUIRED_FREE_BYTES + activeSize
        val availableBytes = downloads.usableSpace
        check(availableBytes > requiredBytes) {
            "Espace libre insuffisant. Libérer au moins ${requiredBytes / (1024 * 1024)} Mo avant l’installation."
        }
        downloadPinnedRootfs(archive)
        val actualSha = sha256(archive)
        check(actualSha.equals(expectedSha, ignoreCase = true)) {
            "La vérification SHA-256 du rootfs a échoué. Archive rejetée."
        }

        // Do not replace an existing rootfs until download and integrity checks pass.
        deleteRecursivelyChecked(staging)
        if (!staging.mkdirs()) error("Impossible de créer le dossier temporaire du rootfs.")
        extractTarXzSafely(archive, staging)

        check(File(staging, "opt/klipper/klippy/klippy.py").isFile) {
            "Rootfs invalide : sources Klipper manquantes."
        }
        check(File(staging, "opt/moonraker/moonraker/moonraker.py").isFile) {
            "Rootfs invalide : sources Moonraker manquantes."
        }
        check(File(staging, "var/www/mainsail/index.html").isFile) {
            "Rootfs invalide : fichiers Mainsail manquants."
        }
        check(File(staging, "opt/klipper-venv/bin/python").canExecute()) {
            "Rootfs invalide : environnement Python Klipper absent."
        }
        check(File(staging, "opt/moonraker-venv/bin/python").canExecute()) {
            "Rootfs invalide : environnement Python Moonraker absent."
        }
        check(File(staging, "home/klipper/printer_data/config/moonraker.conf").isFile) {
            "Rootfs invalide : configuration Moonraker manquante."
        }

        // Preserve user configuration, logs, and G-code while replacing the immutable rootfs.
        val activeData = File(active, "home/klipper/printer_data")
        val stagedData = File(staging, "home/klipper/printer_data")
        if (activeData.isDirectory) {
            listOf("config/printer.cfg", "config/moonraker.conf").forEach { relative ->
                val previous = File(activeData, relative)
                if (previous.isFile) copyChecked(previous, File(stagedData, relative))
            }
            listOf("gcodes", "logs").forEach { directory ->
                val previous = File(activeData, directory)
                if (previous.isDirectory) copyDirectoryContents(previous, File(stagedData, directory))
            }
        }

        val old = File(base, "rootfs-old")
        deleteRecursivelyChecked(old)
        if (active.exists() && !active.renameTo(old)) error("Impossible de sauvegarder le rootfs précédent.")
        if (!staging.renameTo(active)) {
            if (old.exists()) old.renameTo(active)
            error("Impossible d'activer le nouveau rootfs.")
        }
        deleteRecursivelyChecked(old)
        return Result(active, actualSha)
    }

    private fun downloadPinnedRootfs(destination: File) {
        if (destination.isFile && sha256(destination).equals(EXPECTED_SHA256, true)) return
        val temporary = File(destination.parentFile, destination.name + ".part")
        if (temporary.exists() && !temporary.delete()) error("Impossible de supprimer le téléchargement incomplet.")
        val connection = (URL(ROOTFS_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MiracleKlipper-Android")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Téléchargement rootfs échoué (HTTP ${connection.responseCode})." }
            // GitHub Releases may redirect to object storage where Content-Length can be
            // absent. Use the immutable release asset size as the expected length in that case.
            val responseLength = connection.contentLengthLong
            check(responseLength <= 0L || responseLength == EXPECTED_ARCHIVE_SIZE) {
                "Taille du rootfs inattendue dans la réponse HTTP : ${responseLength} octets."
            }
            val total = if (responseLength > 0L) responseLength else EXPECTED_ARCHIVE_SIZE
            var downloaded = 0L
            var lastProgressBytes = 0L
            var lastProgressAt = System.currentTimeMillis()
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        check(downloaded <= EXPECTED_ARCHIVE_SIZE) {
                            "Le téléchargement dépasse la taille officielle autorisée (${EXPECTED_ARCHIVE_SIZE} octets)."
                        }
                        output.write(buffer, 0, count)
                        val now = System.currentTimeMillis()
                        if (downloaded - lastProgressBytes >= PROGRESS_INTERVAL_BYTES ||
                            now - lastProgressAt >= PROGRESS_INTERVAL_MS || downloaded == total) {
                            onProgress?.invoke(downloaded, total)
                            lastProgressBytes = downloaded
                            lastProgressAt = now
                        }
                    }
                    output.fd.sync()
                }
            }
            check(downloaded == EXPECTED_ARCHIVE_SIZE) {
                "Téléchargement incomplet : ${downloaded}/${EXPECTED_ARCHIVE_SIZE} octets reçus."
            }
            check(sha256(temporary).equals(EXPECTED_SHA256, true)) {
                "SHA-256 incorrect après téléchargement. Le fichier n'est pas conservé."
            }
            if (destination.exists() && !destination.delete()) error("Impossible de remplacer l'archive locale.")
            if (!temporary.renameTo(destination)) error("Impossible de finaliser le téléchargement.")
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun extractTarXzSafely(archive: File, output: File) {
        val xzInput = org.apache.commons.compress.compressors.xz.XZCompressorInputStream(
            BufferedInputStream(FileInputStream(archive))
        )
        xzInput.use { xz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val name = entry.name
                    check(!name.startsWith("/") && !name.split('/').contains("..")) {
                        "Archive rejetée : chemin non sûr ($name)."
                    }

                    val rootPath = output.canonicalPath.trimEnd(File.separatorChar)
                    val target = File(output, name)
                    val targetPath = target.canonicalPath
                    check(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
                        "Archive rejetée : entrée hors du dossier rootfs ($name)."
                    }

                    // Symlinks are common inside Debian rootfs archives. Permit only relative
                    // targets that remain inside the rootfs when resolved from the link parent.
                    if (entry.isSymbolicLink) {
                        val linkName = entry.linkName
                        check(!File(linkName).isAbsolute) { "Lien absolu rejeté : $name" }
                        val linkTarget = File(target.parentFile, linkName).absoluteFile.normalizeCompat().path
                        check(linkTarget == rootPath || linkTarget.startsWith(rootPath + File.separator)) { "Lien sortant du rootfs rejeté : $name" }
                        target.parentFile?.mkdirs()
                        if (target.exists() || target.isSymbolicLinkCompat()) target.delete()
                        android.system.Os.symlink(linkName, target.absolutePath)
                        continue
                    }
                    if (entry.isLink) {
                        val linkName = entry.linkName.removePrefix("./")
                        check(!linkName.startsWith("/") && !linkName.split('/').contains("..")) {
                            "Lien physique non sûr rejeté : $name"
                        }
                        val linkTarget = File(output, linkName)
                        val resolvedLinkTarget = linkTarget.canonicalPath
                        check(resolvedLinkTarget == rootPath || resolvedLinkTarget.startsWith(rootPath + File.separator)) {
                            "Lien physique hors du rootfs rejeté : $name"
                        }
                        check(linkTarget.isFile) {
                            "La cible du lien physique n'a pas encore été extraite : $linkName"
                        }
                        target.parentFile?.mkdirs()
                        copyChecked(linkTarget, target)
                        target.setReadable((entry.mode and 0b100000000) != 0, false)
                        target.setWritable((entry.mode and 0b010000000) != 0, false)
                        target.setExecutable((entry.mode and 0b001001001) != 0, false)
                        continue
                    }

                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "Impossible de créer $name" }
                        target.setReadable((entry.mode and 0b100000000) != 0, false)
                        target.setWritable((entry.mode and 0b010000000) != 0, false)
                        target.setExecutable((entry.mode and 0b001001001) != 0, false)
                    } else if (entry.isFile) {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { outputStream ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var remaining = entry.size
                            while (remaining > 0) {
                                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                check(count > 0) { "Archive tronquée dans l'entrée $name." }
                                outputStream.write(buffer, 0, count)
                                remaining -= count
                            }
                            outputStream.fd.sync()
                        }
                        target.setReadable((entry.mode and 0b100000000) != 0, false)
                        target.setWritable((entry.mode and 0b010000000) != 0, false)
                        target.setExecutable((entry.mode and 0b001001001) != 0, false)
                    } else {
                        // Ignore only harmless metadata entries; reject special files.
                        if (!entry.isGlobalPaxHeader && !entry.isPaxHeader) {
                            error("Type d'entrée non pris en charge ($name).")
                        }
                    }
                }
            }
        }
    }

    private fun File.normalizeCompat(): File {
        val parts = mutableListOf<String>()
        absolutePath.split(File.separatorChar).forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return File(File.separator + parts.joinToString(File.separator))
    }

    private fun File.isSymbolicLinkCompat(): Boolean =
        try {
            android.system.Os.lstat(absolutePath).let { it.st_mode and android.system.OsConstants.S_IFMT == android.system.OsConstants.S_IFLNK }
        } catch (_: Exception) { false }

    private fun estimateDirectorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (!file.isDirectory) return file.length()
        return (file.listFiles() ?: emptyArray()).sumOf { estimateDirectorySize(it) }
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        val sourceRoot = source.canonicalPath
        if (!destination.exists() && !destination.mkdirs()) {
            error("Impossible de créer " + destination.absolutePath)
        }
        (source.listFiles() ?: emptyArray()).forEach { child ->
            val childCanonical = child.canonicalPath
            if (childCanonical != sourceRoot && !childCanonical.startsWith(sourceRoot + File.separator)) {
                return@forEach
            }
            val target = File(destination, child.name)
            when {
                child.isDirectory -> copyDirectoryContents(child, target)
                child.isFile -> copyChecked(child, target)
            }
        }
    }

    private fun copyChecked(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun deleteRecursivelyChecked(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursivelyChecked(it) }
        if (!file.delete()) error("Impossible de supprimer ${file.absolutePath}")
    }

    companion object {
        const val ROOTFS_VERSION = "v0.1.0"
        const val ROOTFS_RELEASE_URL = "https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/tag/v0.1.0"
        const val ROOTFS_URL = "https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz"
        const val EXPECTED_SHA256 = "86d001f4c6979364473616ce82fb2ae0865848b02bfb585ee3f4c5272bb67cc9"
        private const val EXPECTED_ARCHIVE_SIZE = 237_450_344L
        private const val PROGRESS_INTERVAL_BYTES = 512L * 1024L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val REQUIRED_FREE_BYTES = 1_500L * 1024L * 1024L
    }
}
