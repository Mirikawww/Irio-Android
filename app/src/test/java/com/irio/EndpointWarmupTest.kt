package com.irio

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EndpointWarmupTest {
    private val server = MockWebServer()

    @Before fun setUp() {
        server.start()
        IrioHttp.resetForTests(IrioHttp.defaultClient())
    }

    @After fun tearDown() {
        server.shutdown()
        IrioHttp.resetForTests()
    }

    private fun provider(url: String = server.url("/v1").toString()) = ProviderConfig(
        id = "p", name = "t", endpointUrl = url, apiKey = "SECRET",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
    )

    @Test
    fun warmupIsHeadToOriginWithoutAuth() {
        server.enqueue(MockResponse().setResponseCode(404))
        EndpointWarmup.warmBlocking(provider())
        val recorded = server.takeRequest()
        assertEquals("HEAD", recorded.method)
        assertEquals("/", recorded.path)
        assertNull(recorded.getHeader("Authorization"))
        assertFalse(recorded.path!!.contains("chat"))
        assertEquals(0, recorded.body.size)
    }

    @Test
    fun skipWhenCancelledOrBlank() {
        assertFalse(EndpointWarmup.shouldWarm(provider(""), inFlight = false))
        assertFalse(EndpointWarmup.shouldWarm(provider(), inFlight = true))
        assertTrue(EndpointWarmup.shouldWarm(provider(), inFlight = false))
    }

    @Test
    fun httpErrorStillCountsAsWarmed() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(EndpointWarmup.warmBlocking(provider()))
        assertEquals(1, server.requestCount)
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test
    fun headNotAllowedFallsBackToGetWithoutAuth() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        assertTrue(EndpointWarmup.warmBlocking(provider()))
        val head = server.takeRequest()
        val get = server.takeRequest()
        assertEquals("HEAD", head.method)
        assertEquals("/", head.path)
        assertNull(head.getHeader("Authorization"))
        assertEquals("GET", get.method)
        assertEquals("/", get.path)
        assertNull(get.getHeader("Authorization"))
        assertEquals(0, get.body.size)
        assertFalse(get.path!!.contains("chat"))
    }
}
