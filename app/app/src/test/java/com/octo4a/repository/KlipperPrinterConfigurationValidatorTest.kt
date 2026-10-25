package com.octo4a.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KlipperPrinterConfigurationValidatorTest {
    @Test
    fun acceptsConfigurationWithMcuAndPrinterSections() {
        val configuration = """
            [mcu]
            serial: /dev/serial/by-id/example

            [printer]
            kinematics: cartesian
            max_velocity: 200
            max_accel: 3000
        """.trimIndent()

        assertTrue(KlipperPrinterConfigurationValidator.isComplete(configuration))
    }

    @Test
    fun acceptsNamedMcuSection() {
        val configuration = """
            [mcu toolhead]
            serial: /dev/serial/by-id/example

            [printer]
            kinematics: cartesian
        """.trimIndent()

        assertTrue(KlipperPrinterConfigurationValidator.isComplete(configuration))
    }

    @Test
    fun rejectsConfigurationWithoutMcuSection() {
        val configuration = """
            [printer]
            kinematics: cartesian
        """.trimIndent()

        assertFalse(KlipperPrinterConfigurationValidator.isComplete(configuration))
    }

    @Test
    fun rejectsConfigurationWithoutPrinterSection() {
        val configuration = """
            [mcu]
            serial: /dev/serial/by-id/example
        """.trimIndent()

        assertFalse(KlipperPrinterConfigurationValidator.isComplete(configuration))
    }

    @Test
    fun ignoresSectionNamesInsideComments() {
        val configuration = """
            # [mcu]
            # [printer]
            [heater_bed]
            sensor_type: Generic 3950
        """.trimIndent()

        assertFalse(KlipperPrinterConfigurationValidator.isComplete(configuration))
    }
}
