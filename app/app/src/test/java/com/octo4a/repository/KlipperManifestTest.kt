package com.octo4a.repository

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KlipperManifestTest {
    @Test
    fun acceptsOfficialPinnedKlipperRevision() {
        validManifest().validate()
    }

    @Test
    fun rejectsFloatingKlipperRevision() {
        assertThrows(IllegalArgumentException::class.java) {
            validManifest().copy(revision = "master").validate()
        }
    }

    @Test
    fun rejectsNonOfficialKlipperRepository() {
        assertThrows(IllegalArgumentException::class.java) {
            validManifest().copy(repository = "https://example.invalid/klipper.git").validate()
        }
    }

    @Test
    fun requiresMcuAndPrinterSectionsBeforeStarting() {
        assertTrue(
            KlipperPrinterConfigurationValidator.isComplete(
                "[mcu]\nserial: /dev/ttyOcto4a\n\n[printer]\nkinematics: cartesian\n"
            )
        )
        assertTrue(!KlipperPrinterConfigurationValidator.isComplete("# [mcu]\n# [printer]\n"))
    }

    private fun validManifest() = KlipperManifest(
        schemaVersion = 1,
        repository = "https://github.com/Klipper3d/klipper.git",
        revision = "a08fc79fcfa342e5b95ac02a9a8b271f453523e9",
        minimumFreeStorageBytes = 536_870_912L
    )
}
