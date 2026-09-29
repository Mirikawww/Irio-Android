package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftwareInfoTest {
    @Test
    fun debugVersionDropsDebugSuffix() {
        assertEquals("debug", softwareBuildType("0.3.0-debug"))
        assertEquals(
            "Irio, Android, 0.3.0, 3, debug",
            softwareInfoLine("Android", "0.3.0-debug", 3, "debug"),
        )
    }

    @Test
    fun releaseKeepsVersionName() {
        assertEquals("release", softwareBuildType("0.3.0"))
        assertEquals(
            "Irio, Android, 0.3.0, 3, release",
            softwareInfoLine("Android", "0.3.0", 3, "release"),
        )
    }

    @Test
    fun releaseDoesNotStripDebugSuffix() {
        assertEquals(
            "Irio, Android, 0.3.0-debug, 3, release",
            softwareInfoLine("Android", "0.3.0-debug", 3, "release"),
        )
    }
}
