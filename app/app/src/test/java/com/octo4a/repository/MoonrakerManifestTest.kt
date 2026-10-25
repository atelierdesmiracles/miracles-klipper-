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
    fun rejectsPublicBindingAndAdditionalTrustedClients() {
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
        revision = "9e676eba6b02661a4dfa3ec6e7ac3f3504498e6d",
        minimumFreeStorageBytes = 536_870_912L
    )

    private fun safeConfiguration() = """
        [server]
        host: 127.0.0.1
        port: 7125
        klippy_uds_address: /var/lib/miracle/printer_data/comms/klippy.sock
        use_xheaders: False

        [authorization]
        enable_api_key: True
        trusted_clients:
         127.0.0.0/8
         ::1/128
        cors_domains:
         http://127.0.0.1:5000
         http://localhost:5000

        [machine]
        provider: none
        shutdown_action: halt
    """.trimIndent()
}
