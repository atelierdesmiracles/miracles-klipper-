package fr.atelierdesmiracles.miracleklipper.v2

data class DeviceProfile(
    val supportedAbis: List<String>,
    val androidApi: Int,
    val totalMemoryMb: Long,
    val freeStorageMb: Long,
    val hasUsbHost: Boolean
)
data class DeviceReadiness(
    val arm64Supported: Boolean,
    val apiSupported: Boolean,
    val memoryRecommended: Boolean,
    val storageRecommended: Boolean,
    val usbAvailable: Boolean
) {
    val canRunV2Shell: Boolean get() = arm64Supported && apiSupported
    val readyForVmPrototype: Boolean
        get() = canRunV2Shell && memoryRecommended && storageRecommended
    companion object {
        fun evaluate(profile: DeviceProfile) = DeviceReadiness(
            arm64Supported = profile.supportedAbis.any { it == "arm64-v8a" },
            apiSupported = profile.androidApi >= 26,
            memoryRecommended = profile.totalMemoryMb >= 4096,
            storageRecommended = profile.freeStorageMb >= 4096,
            usbAvailable = profile.hasUsbHost
        )
    }
}
