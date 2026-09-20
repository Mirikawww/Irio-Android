package com.irio

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TexBitmapCacheTest {
    @Test
    fun everyRenderingInputParticipatesInKey() {
        val base = TexCacheKey("x", 1, true, 2f, 1f)
        assertNotEquals(base, base.copy(latex = "y"))
        assertNotEquals(base, base.copy(colorArgb = 2))
        assertNotEquals(base, base.copy(displayStyle = false))
        assertNotEquals(base, base.copy(pxPerPt = 3f))
        assertNotEquals(base, base.copy(texScale = 2f))
    }

    @Test
    fun sameFormulaStartsOneRenderer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SingleFlightCache<TexCacheKey, String>()
        val key = TexCacheKey("x", 1, false, 2f, 1f)
        var renders = 0

        cache.request(key, this, dispatcher) { renders++; "bitmap" }
        cache.request(key, this, dispatcher) { renders++; "bitmap" }
        testScheduler.advanceUntilIdle()

        assertEquals(1, renders)
        assertEquals("bitmap", cache.peek(key)?.value)
    }

    @Test
    fun failedFormulaDoesNotBecomeReadyOrPolluteAnotherKey() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SingleFlightCache<TexCacheKey, String>()
        val bad = TexCacheKey("bad", 1, false, 2f, 1f)
        val good = bad.copy(latex = "good")

        cache.request(bad, this, dispatcher) { error("invalid TeX") }
        testScheduler.advanceUntilIdle()
        cache.request(good, this, dispatcher) { "good bitmap" }
        testScheduler.advanceUntilIdle()

        assertEquals(AsyncCacheStatus.FAILED, cache.peek(bad)?.status)
        assertEquals("good bitmap", cache.peek(good)?.value)
    }

    @Test
    fun readyEntryIsEvictedAndCanBeRenderedAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SingleFlightCache<String, String>(maxEntries = 1)
        var renders = 0

        cache.request("first", this, dispatcher) { renders++; "1" }
        testScheduler.advanceUntilIdle()
        cache.request("second", this, dispatcher) { renders++; "2" }
        testScheduler.advanceUntilIdle()
        cache.request("first", this, dispatcher) { renders++; "1-again" }
        testScheduler.advanceUntilIdle()

        assertEquals(3, renders)
        assertEquals("1-again", cache.peek("first")?.value)
    }
}
