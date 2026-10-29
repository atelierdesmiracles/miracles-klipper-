package com.octo4a.serial

import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterProberTest {
    @Test
    fun bttBoardsListedInUsbAttachFilterHaveSerialDrivers() {
        assertTrue(
            "BIGTREETECH SKR Mini E3 must be recognized as CDC ACM",
            cdcDevices.contains(UsbDeviceId(1155, 22336))
        )
        assertTrue(
            "BIGTREETECH SKR 1.3 must be recognized as CDC ACM",
            cdcDevices.contains(UsbDeviceId(7504, 24910))
        )
    }
}
