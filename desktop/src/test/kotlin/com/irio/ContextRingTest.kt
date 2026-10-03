package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextRingTest {
    @Test
    fun formatsCountsWithThousandsSeparators() {
        assertEquals("0", formatContextCount(0))
        assertEquals("999", formatContextCount(999))
        assertEquals("1,000", formatContextCount(1_000))
        assertEquals("1,048,576", formatContextCount(1_048_576))
    }

    @Test
    fun unknownWindowIsADash() {
        assertEquals("—", contextWindowLabel(0))
        assertEquals("128,000", contextWindowLabel(128_000))
    }
}
