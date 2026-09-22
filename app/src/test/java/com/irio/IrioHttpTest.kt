package com.irio

import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

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

    @Test
    fun conversationScopesDoNotShareConnections() {
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse()) }
            val acquired = mutableListOf<Int>()
            val base = IrioHttp.defaultClient().newBuilder()
                .eventListener(object : EventListener() {
                    override fun connectionAcquired(call: Call, connection: Connection) {
                        acquired += System.identityHashCode(connection)
                    }
                })
                .build()
            IrioHttp.resetForTests(base)
            try {
                val url = server.url("/")
                fun get(scope: String) {
                    IrioHttp.clientFor(scope).newCall(Request.Builder().url(url).build()).execute().close()
                }
                get("conv-a")
                get("conv-a")
                get("conv-b")
                assertEquals(3, acquired.size)
                assertEquals(acquired[0], acquired[1])
                assertNotEquals(acquired[0], acquired[2])
                assertNotSame(
                    IrioHttp.clientFor("conv-a").sslSocketFactory,
                    IrioHttp.clientFor("conv-b").sslSocketFactory,
                )
            } finally {
                IrioHttp.resetForTests()
            }
        }
    }

    @Test
    fun evictConversationDropsIdleConnectionWithoutBlockingTheCaller() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse())
            IrioHttp.resetForTests()
            try {
                val client = IrioHttp.clientFor("conv-evict")
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
                assertTrue(client.connectionPool.idleConnectionCount() >= 1)
                IrioHttp.evictConversation("conv-evict")
                assertNotSame(client, IrioHttp.clientFor("conv-evict"))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (client.connectionPool.idleConnectionCount() > 0 && System.nanoTime() < deadline) {
                    Thread.sleep(20)
                }
                assertEquals(0, client.connectionPool.idleConnectionCount())
            } finally {
                IrioHttp.resetForTests()
            }
        }
    }
}
