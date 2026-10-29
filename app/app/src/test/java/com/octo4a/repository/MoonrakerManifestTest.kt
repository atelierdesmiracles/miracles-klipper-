package com.octo4a.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonrakerManifestTest {
    @Test
    fun acceptsOfficialPinnedMoonrakerRevision() {
        validManifest().validate()
    }

    @Test
    fun acceptsOfficialRevisionFromOctober2026() {
        validManifest().copy(revision = "b559b9a81161f958a43c2f527b6ad96f73310d5d").validate()
    }

    @Test
    fun rejectsFloatingMoonrakerRevision() {
        try {
            validManifest().copy(revision = "master").validate()
            throw AssertionError("A floating Moonraker revision must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun acceptsLoopbackConfigurationAndKlipperSocket() {
        assertTrue(MoonrakerConfigurationValidator.isSafeLocalConfiguration(safeConfiguration()))
    }

    @Test
    fun acceptsKnownLegacyLoopbackConfigurationForSafeMigration() {
        val legacy = safeConfiguration()
            .replace("use_xheaders: True", "use_xheaders: False")
            .replace(
                "http://localhost:8080",
                "http://localhost:8080\n http://*.local:8080\n http://*.lan:8080"
            )
        assertTrue(MoonrakerConfigurationValidator.isLegacyLocalConfiguration(legacy))
        assertFalse(MoonrakerConfigurationValidator.isSafeLocalConfiguration(legacy))
    }

    @Test
    fun rejectsPublicBindingAndNonLoopbackTrustedClients() {
        assertFalse(
            MoonrakerConfigurationValidator.isSafeLocalConfiguration(
                safeConfiguration().replace("host: 127.0.0.1", "host: 0.0.0.0")
            )
        )
        assertFalse(
            MoonrakerConfigurationValidator.isSafeLocalConfiguration(
                safeConfiguration().replace(" ::1/128", " ::1/128\n 192.168.1.0/24")
            )
        )
    }

    @Test
    fun rejectsWildcardCorsOrigins() {
        assertFalse(
            MoonrakerConfigurationValidator.isSafeLocalConfiguration(
                safeConfiguration().replace("http://localhost:8080", "http://*.lan:8080")
            )
        )
    }

    @Test
    fun rejectsDuplicateServerSectionsAndWrongKlipperSocket() {
        assertFalse(
            MoonrakerConfigurationValidator.isSafeLocalConfiguration(
                safeConfiguration().replace("[authorization]", "[server]\nhost: 0.0.0.0\n\n[authorization]")
            )
        )
        assertFalse(
            MoonrakerConfigurationValidator.isSafeLocalConfiguration(
                safeConfiguration().replace("klippy.sock", "other.sock")
            )
        )
    }

    @Test
    fun parsesWrappedMoonrakerServerInfoResponse() {
        val info = MoonrakerServerInfoParser.parse(
            """{"result":{"moonraker_version":"v0.9.0","klippy_connected":true,"klippy_state":"ready"}}"""
        )
        assertEquals("v0.9.0", info.moonrakerVersion)
        assertTrue(info.klippyConnected)
        assertEquals("ready", info.klippyState)
    }

    @Test
    fun parsesDirectMoonrakerServerInfoResponse() {
        val info = MoonrakerServerInfoParser.parse(
            """{"moonraker_version":"v0.9.0","klippy_connected":false,"klippy_state":"disconnected"}"""
        )
        assertFalse(info.klippyConnected)
        assertEquals("disconnected", info.klippyState)
    }

    @Test
    fun reportsMoonrakerApiErrors() {
        try {
            MoonrakerServerInfoParser.parse("""{"error":{"message":"denied"}}""")
            throw AssertionError("A Moonraker API error must be surfaced")
        } catch (error: java.io.IOException) {
            assertTrue(error.message?.contains("denied") == true)
        }
    }

    private fun validManifest() = MoonrakerManifest(
        schemaVersion = 1,
        repository = "https://github.com/Arksine/moonraker.git",
        revision = "b559b9a81161f958a43c2f527b6ad96f73310d5d",
        minimumFreeStorageBytes = 536_870_912L
    )

    private fun safeConfiguration() = """
        [server]
        host: 127.0.0.1
        port: 7125
        klippy_uds_address: /var/lib/miracle/printer_data/comms/klippy.sock
        use_xheaders: True

        [authorization]
        enable_api_key: True
        trusted_clients:
         127.0.0.0/8
         ::1/128
        cors_domains:
         http://127.0.0.1:8080
         http://localhost:8080

        [machine]
        provider: none
        shutdown_action: halt
    """.trimIndent()
}
