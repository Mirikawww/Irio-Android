package com.irio

import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IrioHttpTest {
    @Test
    fun clientAllowsCleartextAndModernTls() {
        val specs = IrioHttp.defaultClient().connectionSpecs
        assertTrue(specs.contains(ConnectionSpec.CLEARTEXT))
        assertTrue(specs.contains(ConnectionSpec.MODERN_TLS))
    }

    @Test
    fun pingIntervalIs15s() {
        assertEquals(15_000, IrioHttp.defaultClient().pingIntervalMillis.toLong())
    }

    @Test
    fun sequentialRequestsReusePooledConnection() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            server.enqueue(MockResponse())
            val acquired = mutableListOf<Int>()
            val client = IrioHttp.defaultClient().newBuilder()
                .eventListener(object : EventListener() {
                    override fun connectionAcquired(call: Call, connection: Connection) {
                        acquired += System.identityHashCode(connection)
                    }
                })
                .build()
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            assertEquals(2, acquired.size)
            assertEquals(acquired[0], acquired[1])
        }
    }
}
