package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolStoreTest {
    @Test
    fun compressionSettingsUseNamedDefaultsAndClampToTheSliderRange() {
        assertEquals(90, ToolStore.COMPRESSION_TRIGGER_DEFAULT)
        assertEquals(10, ToolStore.COMPRESSION_TARGET_DEFAULT)
        assertEquals(1, ToolStore.clampCompressionPercent(0))
        assertEquals(100, ToolStore.clampCompressionPercent(101))
        assertEquals(90, ToolStore.clampCompressionPercent(90))
    }

    @Test
    fun zeroIsTheUnlimitedToolRoundSliderValue() {
        assertEquals(0, ToolStore.MAX_ROUNDS_MIN)
        assertEquals(0, ToolStore.clampMaxRounds(-1))
        assertEquals(0, ToolStore.clampMaxRounds(0))
        assertEquals(25, ToolStore.clampMaxRounds(26))
    }
}
