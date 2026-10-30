package fr.atelierdesmiracles.miracleklipper.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportPolicyTest {
    @Test fun arm64DeviceWithRecommendedResourcesIsReady() {
        val result = DeviceSupportPolicy.evaluate(DeviceProfile(listOf("arm64-v8a"), 35, 8192, 12000, true))
        assertTrue(result.canRunV2Shell)
        assertTrue(result.readyForVmPrototype)
        assertTrue(result.usbAvailable)
    }
    @Test fun nonArm64DeviceIsRejected() {
        val result = DeviceSupportPolicy.evaluate(DeviceProfile(listOf("armeabi-v7a"), 33, 8192, 12000, true))
        assertFalse(result.arm64Supported)
        assertFalse(result.canRunV2Shell)
        assertFalse(result.readyForVmPrototype)
    }
    @Test fun lowResourcesWarnButDoNotMisclassifyAbi() {
        val result = DeviceSupportPolicy.evaluate(DeviceProfile(listOf("arm64-v8a"), 33, 2048, 1024, false))
        assertTrue(result.canRunV2Shell)
        assertFalse(result.memoryRecommended)
        assertFalse(result.storageRecommended)
        assertFalse(result.readyForVmPrototype)
        assertFalse(result.usbAvailable)
    }
    @Test fun unsupportedAndroidApiIsRejected() {
        val result = DeviceSupportPolicy.evaluate(DeviceProfile(listOf("arm64-v8a"), 25, 8192, 12000, true))
        assertFalse(result.apiSupported)
        assertFalse(result.canRunV2Shell)
    }
}
