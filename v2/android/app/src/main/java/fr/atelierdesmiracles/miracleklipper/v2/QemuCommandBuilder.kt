package fr.atelierdesmiracles.miracleklipper.v2

import java.io.File

/**
 * Builds a QEMU TCG command for the first Alpine ARM64 boot prototype.
 * This class deliberately does not download or bundle QEMU/guest images.
 */
data class QemuGuestConfig(
    val qemuBinary: File,
    val kernelImage: File,
    val initrdImage: File,
    val diskImage: File,
    val qmpSocket: File,
    val serialLog: File,
    val memoryMb: Int = 1024,
    val cpuCount: Int = 2,
    val append: String = "console=ttyAMA0,115200 earlycon=pl011,0x09000000 rdinit=/init"
)

sealed class QemuPreflightResult {
    data class Ready(val command: List<String>) : QemuPreflightResult()
    data class Missing(val items: List<String>) : QemuPreflightResult()
    data class Invalid(val reason: String) : QemuPreflightResult()
}

object QemuCommandBuilder {
    fun build(config: QemuGuestConfig): QemuPreflightResult {
        if (config.memoryMb < 256 || config.memoryMb > 16384) {
            return QemuPreflightResult.Invalid("La mémoire VM doit être comprise entre 256 et 16384 Mo.")
        }
        if (config.cpuCount !in 1..8) {
            return QemuPreflightResult.Invalid("Le nombre de vCPU doit être compris entre 1 et 8.")
        }
        if (config.append.isBlank() || config.append.any { it == '\\n' || it == '\\r' }) {
            return QemuPreflightResult.Invalid("La ligne de démarrage du noyau est invalide.")
        }

        val paths = listOf(
            "Binaire QEMU" to config.qemuBinary,
            "Noyau Alpine" to config.kernelImage,
            "Initramfs Alpine" to config.initrdImage,
            "Disque virtuel" to config.diskImage
        )
        val missing = paths.filter { (_, file) -> !file.isFile }.map { it.first }
        if (!config.qemuBinary.canExecute() && config.qemuBinary.isFile) {
            return QemuPreflightResult.Invalid("Le binaire QEMU existe mais n'est pas exécutable.")
        }
        if (missing.isNotEmpty()) return QemuPreflightResult.Missing(missing)

        val socketPath = config.qmpSocket.absolutePath
        if (socketPath.any { it == ',' || it == '\\n' || it == '\\r' }) {
            return QemuPreflightResult.Invalid("Le chemin du socket QMP contient un caractère interdit.")
        }

        val command = listOf(
            config.qemuBinary.absolutePath,
            "-machine", "virt",
            "-accel", "tcg,thread=multi",
            "-cpu", "max",
            "-smp", config.cpuCount.toString(),
            "-m", config.memoryMb.toString(),
            "-kernel", config.kernelImage.absolutePath,
            "-initrd", config.initrdImage.absolutePath,
            "-append", config.append,
            "-drive", "if=none,id=vdisk,format=qcow2,file=${config.diskImage.absolutePath}",
            "-device", "virtio-blk-device,drive=vdisk",
            "-netdev", "user,id=net0",
            "-device", "virtio-net-device,netdev=net0",
            "-display", "none",
            "-serial", "file:${config.serialLog.absolutePath}",
            "-monitor", "none",
            "-qmp", "unix:$socketPath,server=on,wait=off"
        )
        return QemuPreflightResult.Ready(command)
    }
}
