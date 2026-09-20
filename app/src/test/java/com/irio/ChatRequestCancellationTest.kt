package com.irio

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatRequestCancellationTest {
    @Test
    fun cancelAbortsOkHttpCall() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val client = OkHttpClient()
            val call = client.newCall(
                Request.Builder().url(server.url("/")).post(ByteArray(0).toRequestBody()).build(),
            )
            val cancellation = ChatRequestCancellation()
            cancellation.attach(call)
            val started = CountDownLatch(1)
            val failed = CountDownLatch(1)
            Thread {
                started.countDown()
                runCatching { call.execute().close() }.onFailure { failed.countDown() }
            }.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            cancellation.cancel()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(cancellation.isCancelled())
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
