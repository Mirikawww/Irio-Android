package com.irio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class StreamUpdateQueueTest {
    @Test(timeout = 10_000)
    fun fullQueueWaitsWithoutDroppingOrReorderingUpdates() = runBlocking {
        val full = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val sent = AtomicInteger()
        val expected = List(STREAM_UPDATE_QUEUE_CAPACITY * 4) { ChatStreamUpdate(delta = "$it,") } +
            ChatStreamUpdate(done = true, usage = TokenUsage(10, 20, 30))
        val delivered = mutableListOf<ChatStreamUpdate>()
        val request = async {
            withQueuedStreamUpdates(
                onUpdate = { releaseConsumer.await(); delivered += it },
                cancelProducer = {},
            ) { publish ->
                expected.forEach { update ->
                    publish(update)
                    if (sent.incrementAndGet() == STREAM_UPDATE_QUEUE_CAPACITY + 1) full.complete(Unit)
                }
                "complete"
            }
        }
        try {
            withTimeout(3_000) { full.await() }
            assertEquals("Only one in-flight update plus the bounded queue may accumulate",
                STREAM_UPDATE_QUEUE_CAPACITY + 1, sent.get())
            assertFalse(request.isCompleted)
            releaseConsumer.complete(Unit)
            assertEquals("complete", withTimeout(3_000) { request.await() })
            assertEquals(expected, delivered)
        } finally {
            releaseConsumer.complete(Unit)
            request.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun consumerCancellationUnblocksAFullQueue() = runBlocking {
        val full = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val producerCancelled = CompletableDeferred<Unit>()
        val request = async {
            runCatching {
                withQueuedStreamUpdates(
                    onUpdate = {
                        releaseConsumer.await()
                        throw CancellationException("consumer cancelled")
                    },
                    cancelProducer = { producerCancelled.complete(Unit) },
                ) { publish ->
                    repeat(STREAM_UPDATE_QUEUE_CAPACITY * 2) { index ->
                        publish(ChatStreamUpdate(delta = index.toString()))
                        if (index == STREAM_UPDATE_QUEUE_CAPACITY) full.complete(Unit)
                    }
                }
            }.exceptionOrNull()
        }
        try {
            withTimeout(3_000) { full.await() }
            releaseConsumer.complete(Unit)
            val failure = withTimeoutOrNull(1_000) { request.await() }
            assertNotNull("A cancelled consumer must not leave the receiver blocked on a full queue", failure)
            assertTrue(failure is CancellationException)
            assertEquals("consumer cancelled", failure?.message)
            assertTrue(producerCancelled.isCompleted)
        } finally {
            releaseConsumer.complete(Unit)
            request.cancelAndJoin()
        }
    }
}
