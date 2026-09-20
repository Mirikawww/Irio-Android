package com.irio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

internal enum class AsyncCacheStatus {
    PENDING,
    READY,
    FAILED,
}

internal data class AsyncCacheResult<V>(
    val status: AsyncCacheStatus,
    val value: V?,
    val completion: Deferred<V?>?,
)

/** A bounded-lifetime, single-flight cache for CPU work owned by a Compose screen. */
internal class SingleFlightCache<K, V>(private val maxEntries: Int = 128) {
    private sealed interface Entry<V> {
        data class Pending<V>(val completion: Deferred<V?>) : Entry<V>
        data class Ready<V>(val value: V) : Entry<V>
        class Failed<V> : Entry<V>
    }

    private val entries = LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true)

    fun request(
        key: K,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        parser: () -> V,
    ): AsyncCacheResult<V> {
        var completion: CompletableDeferred<V?>? = null
        val result = synchronized(entries) {
            when (val entry = entries[key]) {
                is Entry.Pending -> return AsyncCacheResult(AsyncCacheStatus.PENDING, null, entry.completion)
                is Entry.Ready -> return AsyncCacheResult(AsyncCacheStatus.READY, entry.value, null)
                is Entry.Failed -> return AsyncCacheResult(AsyncCacheStatus.FAILED, null, null)
                null -> Unit
            }
            completion = CompletableDeferred()
            entries[key] = Entry.Pending(completion)
            trimReadyEntries()
            AsyncCacheResult(AsyncCacheStatus.PENDING, null, completion)
        }
        scope.launch(dispatcher) {
            val value = runCatching { parser() }.getOrNull()
            completion!!.complete(value)
            synchronized(entries) {
                if (entries[key] is Entry.Pending) {
                    entries[key] = if (value != null) Entry.Ready(value) else Entry.Failed()
                    trimReadyEntries()
                }
            }
        }
        return result
    }

    fun peek(key: K): AsyncCacheResult<V>? = synchronized(entries) {
        when (val entry = entries[key]) {
            is Entry.Pending -> AsyncCacheResult(AsyncCacheStatus.PENDING, null, entry.completion)
            is Entry.Ready -> AsyncCacheResult(AsyncCacheStatus.READY, entry.value, null)
            is Entry.Failed -> AsyncCacheResult(AsyncCacheStatus.FAILED, null, null)
            null -> null
        }
    }

    private fun trimReadyEntries() {
        while (entries.size > maxEntries) {
            val key = entries.entries.firstOrNull { it.value !is Entry.Pending }?.key ?: return
            entries.remove(key)
        }
    }
}
