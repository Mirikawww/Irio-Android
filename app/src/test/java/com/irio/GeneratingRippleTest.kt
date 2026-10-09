package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratingRippleTest {
    @Test
    fun threeWavesAreEvenlyStaggered() {
        assertEquals(3, GeneratingRippleCount)
        assertEquals(0f, generatingRippleProgress(0f, 0), 0.0001f)
        assertEquals(1f / 3f, generatingRippleProgress(0f, 1), 0.0001f)
        assertEquals(2f / 3f, generatingRippleProgress(0f, 2), 0.0001f)
    }

    @Test
    fun wavesExpandRatherThanReverse() {
        for (ring in 0 until GeneratingRippleCount) {
            assertTrue(generatingRippleProgress(0.2f, ring) > generatingRippleProgress(0.1f, ring))
        }
    }

    @Test
    fun cycleWrapsToTheSamePositions() {
        for (ring in 0 until GeneratingRippleCount) {
            assertEquals(generatingRippleProgress(0f, ring), generatingRippleProgress(1f, ring), 0.0001f)
        }
    }

    @Test
    fun waveIsInvisibleAtBothEndsOfItsCycle() {
        assertEquals(0f, generatingRippleAlpha(0f), 0f)
        assertEquals(0f, generatingRippleAlpha(1f), 0f)
        assertTrue(generatingRippleAlpha(0.1f) > 0f)
    }

    @Test
    fun outerWavesFadeAndStayWithinOpacityBounds() {
        assertTrue(generatingRippleAlpha(0.2f) > generatingRippleAlpha(0.5f))
        assertTrue(generatingRippleAlpha(0.5f) > generatingRippleAlpha(0.8f))
        for (step in 0..100) {
            assertTrue(generatingRippleAlpha(step / 100f) in 0f..0.55f)
        }
    }
}
