package com.yavorradulov.jerrycam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryTest {
    @Test
    fun percentFromLevelAndScale() {
        assertEquals(BatteryState(82, charging = false), batteryState(level = 41, scale = 50, plugged = 0))
        assertEquals(BatteryState(100, charging = true), batteryState(level = 100, scale = 100, plugged = 1))
    }

    @Test
    fun missingReadingIsNull() {
        assertNull(batteryState(level = -1, scale = 100, plugged = 0))
        assertNull(batteryState(level = 50, scale = 0, plugged = 0))
    }
}
