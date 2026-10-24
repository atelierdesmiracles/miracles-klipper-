package com.octo4a.bootstrap

import android.content.Context
import android.util.AtomicFile
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
import org.json.JSONObject

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
        check(File(staging, "home/klipper/printer_data/config/moonraker.conf").isFile) {
            "Rootfs invalide : configuration Moonraker manquante."
        }

        // Preserve printer-specific configuration across rootfs refreshes.
        val activeConfig = File(active, "home/klipper/printer_data/config")
        val stagedConfig = File(staging, "home/klipper/printer_data/config")
        if (activeConfig.isDirectory) {
            listOf("printer.cfg", "moonraker.conf").forEach { name ->
                val previous = File(activeConfig, name)
                if (previous.isFile) copyChecked(previous, File(stagedConfig, name))
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
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MiracleKlipper-Android")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Téléchargement rootfs échoué (HTTP ${connection.responseCode})." }
            val total = connection.contentLengthLong
            // Rootfs v0.1.0 is ~237 MB; reject implausibly small and unbounded response sizes.
            check(total in MIN_ARCHIVE_BYTES..MAX_ARCHIVE_BYTES) { "Taille du rootfs inattendue : ${total} octets." }
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        check(downloaded <= MAX_ARCHIVE_BYTES) { "Le téléchargement dépasse la taille maximale autorisée." }
                        output.write(buffer, 0, count)
                        onProgress?.invoke(downloaded, total)
                    }
                    output.fd.sync()
                }
            }
            check(downloaded == total) { "Téléchargement incomplet : ${downloaded}/${total} octets." }
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
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun extractTarXzSafely(archive: File, output: File) {
        // XZ + TAR extraction is handled with Apache Commons Compress to validate every entry path.
        val input = org.apache.commons.compress.compressors.xz.XZCompressorInputStream(
            BufferedInputStream(FileInputStream(archive))
        )
        input.use { xz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val target = File(output, entry.name)
                    val normalizedRoot = output.canonicalFile
                    val normalizedTarget = target.canonicalFile
                    check(normalizedTarget.toPath().startsWith(normalizedRoot.toPath())) {
                        "Archive rejetée : entrée hors du dossier rootfs (${entry.name})."
                    }
                    if (entry.isSymbolicLink || entry.isLink) {
                        val link = entry.linkName
                        check(!File(link).isAbsolute && !link.split('/').contains("..")) {
                            "Archive rejetée : lien symbolique non sûr (${entry.name})."
                        }
                        target.parentFile?.mkdirs()
                        if (target.exists()) target.delete()
                        android.system.Os.symlink(link, target.absolutePath)
                        continue
                    }
                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "Impossible de créer ${entry.name}" }
                    } else if (entry.isFile) {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            tar.copyTo(out)
                        }
                        if ((entry.mode and 0b001001001) != 0) target.setExecutable(true, false)
                    } else {
                        // Reject device nodes, FIFOs and unknown special entries on Android.
                        error("Archive rejetée : type d'entrée non pris en charge (${entry.name}).")
                    }
                }
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
        const val ROOTFS_URL = "https://github.com/atelierdesmiracles/boostrap-debian-monnraker-et-klipper-/releases/download/v0.1.0/miracle-klipper-debian-bookworm-arm64-rootfs.tar.xz"
        const val EXPECTED_SHA256 = "86d001f4c6979364473616ce82fb2ae0865848b02bfb585ee3f4c5272bb67cc9"
        private const val MIN_ARCHIVE_BYTES = 100L * 1024L * 1024L
        private const val MAX_ARCHIVE_BYTES = 700L * 1024L * 1024L
    }
}
