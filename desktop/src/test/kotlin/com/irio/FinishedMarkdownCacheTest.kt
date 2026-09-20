package com.irio

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinishedMarkdownCacheTest {
    @Test
    fun firstRequestIsPendingWithoutRunningParserOnCallingThread() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SingleFlightCache<String, String>()
        var calls = 0

        val result = cache.request("body", this, dispatcher) {
            calls++
            "parsed"
        }

        assertEquals(AsyncCacheStatus.PENDING, result.status)
        assertEquals(0, calls)
    }

    @Test
    fun equalTextsShareOneInFlightParser() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SingleFlightCache<String, String>()
        var calls = 0

        cache.request("第一段\n\n第二段", this, dispatcher) {
            calls++
            "parsed"
        }
        cache.request("第一段\n\n第二段", this, dispatcher) {
            calls++
            "parsed"
        }

        testScheduler.advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(
            AsyncCacheStatus.READY,
            cache.request("第一段\n\n第二段", this, dispatcher) { error("must be cached") }.status,
        )
    }

    @Test
    fun differentTextsWithSameHashDoNotShareState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SingleFlightCache<String, String>()
        assertEquals("Aa".hashCode(), "BB".hashCode())

        cache.request("Aa", this, dispatcher) { "first" }
        testScheduler.advanceUntilIdle()
        cache.request("BB", this, dispatcher) { "second" }
        testScheduler.advanceUntilIdle()

        assertEquals("first", cache.request("Aa", this, dispatcher) { error("wrong slot") }.value)
        assertEquals("second", cache.request("BB", this, dispatcher) { error("wrong slot") }.value)
    }

    @Test
    fun failedParseIsNotReadyAndDoesNotReuseAnotherKey() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = SingleFlightCache<String, String>()

        cache.request("bad", this, dispatcher) { error("parse failed") }
        testScheduler.advanceUntilIdle()
        assertEquals(
            AsyncCacheStatus.FAILED,
            cache.request("bad", this, dispatcher) { "must not retry" }.status,
        )
        assertNull(cache.request("bad", this, dispatcher) { "must not retry" }.value)

        cache.request("good", this, dispatcher) { "ready" }
        testScheduler.advanceUntilIdle()
        assertEquals("ready", cache.request("good", this, dispatcher) { error("wrong key") }.value)
    }
}