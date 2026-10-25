package com.octo4a.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AppErrorTest {
    @Test
    fun includesOperationAndCauseMessage() {
        val error = AppError.from("USB", "Open device", IllegalStateException("permission denied"))

        assertEquals("USB", error.component)
        assertEquals("Open device", error.operation)
        assertEquals("Open device failed: permission denied", error.message)
    }

    @Test
    fun usesExceptionTypeWhenCauseHasNoMessage() {
        val error = AppError.from("Linux", "Start service", IllegalStateException())

        assertEquals("Start service failed: IllegalStateException", error.message)
    }
}
