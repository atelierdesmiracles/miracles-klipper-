package com.octo4a.repository

import java.io.File
import java.io.IOException

/** Safety checks shared by the legacy bootstrap installer and its unit tests. */
internal object BootstrapArchiveSafety {
    const val MINIMUM_FREE_SPACE_BYTES = 64L * 1024L * 1024L

    fun resolveEntryTarget(stagingDirectory: File, entryName: String): File {
        // Archive entry names must be relative paths. File(parent, child) can
        // normalize absolute child paths differently across platforms, so reject
        // them before resolving the canonical target.
        if (
            entryName.isBlank() ||
            entryName.any { it == 0.toChar() } ||
            entryName.contains(92.toChar()) ||
            File(entryName).isAbsolute ||
            entryName.startsWith('/')
        ) {
            throw IOException("Invalid bootstrap archive entry name")
        }

        val root = stagingDirectory.canonicalFile
        val target = File(root, entryName).canonicalFile
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (target != root && !target.path.startsWith(rootPrefix)) {
            throw IOException("Bootstrap archive entry escapes staging directory: $entryName")
        }
        return target
    }

    fun hasEnoughSpace(availableBytes: Long, additionalBytes: Long, reserveBytes: Long): Boolean {
        if (availableBytes < 0 || additionalBytes < 0 || reserveBytes < 0) return false
        if (additionalBytes > Long.MAX_VALUE - reserveBytes) return false
        return availableBytes >= additionalBytes + reserveBytes
    }

    @Throws(IOException::class)
    fun requireEnoughSpace(
        storageDirectory: File,
        additionalBytes: Long = 0,
        reserveBytes: Long = MINIMUM_FREE_SPACE_BYTES
    ) {
        val availableBytes = storageDirectory.usableSpace
        if (!hasEnoughSpace(availableBytes, additionalBytes, reserveBytes)) {
            val requiredBytes = if (additionalBytes > Long.MAX_VALUE - reserveBytes) {
                Long.MAX_VALUE
            } else {
                additionalBytes + reserveBytes
            }
            throw IOException(
                "Not enough free space for bootstrap installation " +
                    "(need at least $requiredBytes bytes, have $availableBytes)"
            )
        }
    }
}
