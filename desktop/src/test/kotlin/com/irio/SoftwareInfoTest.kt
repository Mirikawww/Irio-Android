package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftwareInfoTest {
    @Test
    fun debugVersionDropsDebugSuffix() {
        assertEquals(
            "Irio, Desktop, 0.3.0, 3, debug",
            softwareInfoLine("Desktop", "0.3.0-debug", 3, "debug"),
        )
    }

    @Test
    fun cleanDebugNameStaysUnchanged() {
        assertEquals(
            "Irio, Desktop, 0.3.0, 3, debug",
            softwareInfoLine("Desktop", "0.3.0", 3, "debug"),
        )
    }
}
