package com.irio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope

/** Request-attempt-local FIFO. Never drop deltas; a full queue bounds event accumulation. */
internal const val STREAM_UPDATE_QUEUE_CAPACITY = 256

internal suspend fun <T> withQueuedStreamUpdates(
    onUpdate: suspend (ChatStreamUpdate) -> Unit,
    cancelProducer: () -> Unit,
    receive: suspend (suspend (ChatStreamUpdate) -> Unit) -> T,
): T = coroutineScope {
    val updates = Channel<ChatStreamUpdate>(STREAM_UPDATE_QUEUE_CAPACITY)
    val consumer = async(Dispatchers.Default) {
        for (update in updates) onUpdate(update)
    }
    consumer.invokeOnCompletion { error ->
        if (error != null) {
            // Also unblock execute/readLine if the callback fails or this scope is cancelled.
            updates.cancel(CancellationException("Stream update consumer stopped", error))
            cancelProducer()
        }
    }
    try {
        val result = runCatching { receive { updates.send(it) } }
        updates.close()
        // Finish delivery before returning a result, retrying, or executing a tool round.
        consumer.await()
        result.getOrThrow()
    } finally {
        consumer.cancel()
        updates.cancel()
    }
}
