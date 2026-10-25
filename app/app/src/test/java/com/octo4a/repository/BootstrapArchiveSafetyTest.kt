package com.octo4a.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BootstrapArchiveSafetyTest {
    @Test
    fun resolvesEntriesInsideStagingDirectory() {
        val staging = Files.createTempDirectory("bootstrap-safety").toFile()

        try {
            val target = BootstrapArchiveSafety.resolveEntryTarget(staging, "etc/profile")

            assertEquals(File(staging, "etc/profile").canonicalFile, target)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun rejectsEntriesThatEscapeStagingDirectory() {
        val staging = Files.createTempDirectory("bootstrap-safety").toFile()

        try {
            BootstrapArchiveSafety.resolveEntryTarget(staging, "../../outside")
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun rejectsBackslashPaths() {
        val staging = Files.createTempDirectory("bootstrap-safety").toFile()

        try {
            BootstrapArchiveSafety.resolveEntryTarget(staging, "..\\outside")
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun rejectsAbsolutePaths() {
        val staging = Files.createTempDirectory("bootstrap-safety").toFile()

        try {
            BootstrapArchiveSafety.resolveEntryTarget(
                staging,
                File(staging.parentFile, "outside").absolutePath
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun rejectsNullBytesInEntryNames() {
        val staging = Files.createTempDirectory("bootstrap-safety").toFile()

        try {
            BootstrapArchiveSafety.resolveEntryTarget(staging, "etc/\\u0000profile")
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun checksAvailableSpaceWithoutOverflow() {
        assertTrue(BootstrapArchiveSafety.hasEnoughSpace(100, 40, 60))
        assertFalse(BootstrapArchiveSafety.hasEnoughSpace(99, 40, 60))
        assertFalse(BootstrapArchiveSafety.hasEnoughSpace(Long.MAX_VALUE, Long.MAX_VALUE, 1))
    }
}
