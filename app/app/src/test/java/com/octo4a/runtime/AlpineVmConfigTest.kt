package com.octo4a.runtime

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class AlpineVmConfigTest {
    @Test
    fun acceptsArm64QemuConfiguration() {
        validConfig().validate()
    }

    @Test
    fun rejectsNonArm64Host() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(androidAbi = "armeabi-v7a").validate()
        }
    }

    @Test
    fun rejectsNonArm64Guest() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(guestArchitecture = "x86_64").validate()
        }
    }

    @Test
    fun rejectsTooSmallDisk() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(diskSizeBytes = 1024L).validate()
        }
    }

    @Test
    fun rejectsTooLittleMemory() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(memoryBytes = 1024L).validate()
        }
    }

    @Test
    fun rejectsInvalidCpuCount() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(cpuCount = 0).validate()
        }
    }

    @Test
    fun rejectsRelativeDiskPath() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfig().copy(diskImage = File("vm.img")).validate()
        }
    }

    private fun validConfig() = AlpineVmConfig(
        androidAbi = "arm64-v8a",
        guestArchitecture = "aarch64",
        diskImage = File("/data/user/0/fr.atelierdesmiracles.miracleklipper/files/alpine-vm.img"),
        diskSizeBytes = 8L * 1024L * 1024L * 1024L,
        memoryBytes = 1024L * 1024L * 1024L,
        cpuCount = 2
    )
}
