package com.octo4a.runtime

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Host-side contract for a persistent Linux guest.
 *
 * This contract deliberately does not start PRoot or a container daemon. A concrete QEMU
 * backend will be added only after the guest kernel, rootfs and native QEMU artifacts are
 * built and checksum-pinned.
 */
enum class VmBackend {
    QEMU_TCG
}

enum class VmRuntimeStage {
    STOPPED,
    PREPARING,
    STARTING,
    READY,
    STOPPING,
    FAILED
}

data class VmRuntimeState(
    val stage: VmRuntimeStage = VmRuntimeStage.STOPPED,
    val message: String = "",
    val guestAddress: String? = null
)

data class AlpineVmConfig(
    val androidAbi: String,
    val guestArchitecture: String,
    val diskImage: File,
    val diskSizeBytes: Long,
    val memoryBytes: Long,
    val cpuCount: Int,
    val backend: VmBackend = VmBackend.QEMU_TCG
) {
    fun validate() {
        require(androidAbi == "arm64-v8a") {
            "The Alpine VM prototype currently supports Android ARM64 only"
        }
        require(guestArchitecture == "aarch64") {
            "The Alpine guest architecture must be aarch64"
        }
        require(diskSizeBytes >= MIN_DISK_SIZE_BYTES) {
            "The VM disk must be at least 2 GiB"
        }
        require(memoryBytes >= MIN_MEMORY_BYTES) {
            "The VM must have at least 512 MiB of guest memory"
        }
        require(cpuCount in 1..8) {
            "The VM CPU count must be between 1 and 8"
        }
        require(diskImage.isAbsolute) {
            "The VM disk path must be absolute"
        }
    }

    companion object {
        const val MIN_DISK_SIZE_BYTES = 2L * 1024L * 1024L * 1024L
        const val MIN_MEMORY_BYTES = 512L * 1024L * 1024L
    }
}

/**
 * Implementations must own their child processes and release QEMU, QMP and USB resources
 * on stop(). Implementations must never expose QMP on a TCP interface.
 */
interface LinuxVmRuntime {
    val state: StateFlow<VmRuntimeState>

    suspend fun start(config: AlpineVmConfig)

    suspend fun stop()

    suspend fun diagnostics(): String
}
