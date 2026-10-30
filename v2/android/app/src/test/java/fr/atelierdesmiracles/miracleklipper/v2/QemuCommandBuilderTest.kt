package fr.atelierdesmiracles.miracleklipper.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QemuCommandBuilderTest {
    @Test fun reportsMissingRuntimeAndGuestFilesClearly() {
        val root = Files.createTempDirectory("mk-qemu-missing").toFile()
        val config = QemuGuestConfig(
            File(root, "qemu-system-aarch64"),
            File(root, "Image"),
            File(root, "initramfs"),
            File(root, "guest.qcow2"),
            File(root, "qmp.sock"),
            File(root, "serial.log")
        )
        val result = QemuCommandBuilder.build(config)
        assertTrue(result is QemuPreflightResult.Missing)
        assertEquals(4, (result as QemuPreflightResult.Missing).items.size)
    }

    @Test fun buildsTcgVirtioAndPrivateQmpArguments() {
        val root = Files.createTempDirectory("mk-qemu-ready").toFile()
        val binary = File(root, "qemu-system-aarch64").apply { writeText("#!/bin/sh\\nexit 0\\n"); setExecutable(true) }
        val kernel = File(root, "Image").apply { writeText("kernel") }
        val initrd = File(root, "initramfs").apply { writeText("initrd") }
        val disk = File(root, "guest.qcow2").apply { writeText("disk") }
        val result = QemuCommandBuilder.build(QemuGuestConfig(
            binary, kernel, initrd, disk, File(root, "run/qmp.sock"), File(root, "logs/serial.log")
        ))
        assertTrue(result is QemuPreflightResult.Ready)
        val command = (result as QemuPreflightResult.Ready).command
        assertTrue(command.contains("tcg,thread=multi"))
        assertTrue(command.contains("virt"))
        assertTrue(command.contains("virtio-blk-device,drive=vdisk"))
        assertTrue(command.any { it.startsWith("unix:") })
    }

    @Test fun rejectsUnsafeQmpSocketPath() {
        val root = Files.createTempDirectory("mk-qemu-invalid").toFile()
        val binary = File(root, "qemu").apply { writeText("binary"); setExecutable(true) }
        val kernel = File(root, "kernel").apply { writeText("kernel") }
        val initrd = File(root, "initrd").apply { writeText("initrd") }
        val disk = File(root, "disk").apply { writeText("disk") }
        val result = QemuCommandBuilder.build(QemuGuestConfig(
            binary, kernel, initrd, disk, File(root, "bad,path/qmp.sock"), File(root, "serial.log")
        ))
        assertTrue(result is QemuPreflightResult.Invalid)
    }

    @Test fun rejectsOutOfRangeResources() {
        val root = Files.createTempDirectory("mk-qemu-resources").toFile()
        val result = QemuCommandBuilder.build(QemuGuestConfig(
            File(root, "qemu"), File(root, "kernel"), File(root, "initrd"),
            File(root, "disk"), File(root, "qmp.sock"), File(root, "serial.log"),
            memoryMb = 128
        ))
        assertTrue(result is QemuPreflightResult.Invalid)
    }
}
