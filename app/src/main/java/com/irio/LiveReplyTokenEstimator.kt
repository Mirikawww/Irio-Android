package com.irio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Request-local live usage estimation; not shared between concurrent replies. */
internal class LiveReplyTokenEstimator(
    private val countTokens: (String) -> Int = TokenEstimator::count,
) {
    private val bodyCount = CachedCount(countTokens)
    private val toolCallCount = CachedCount(countTokens)
    private val processCounts = HashMap<Long, CachedCount>()

    suspend fun estimateOnWorker(
        enabled: Boolean,
        streamedText: String,
        process: List<ProcessEvent>,
        toolCallText: String,
    ): Pair<Int, Int>? = if (enabled) {
        withContext(Dispatchers.Default) {
            val total = estimate(streamedText, process, toolCallText)
            total to toolTokens(process, toolCallText)
        }
    } else {
        null
    }

    fun estimate(streamedText: String, process: List<ProcessEvent>, toolCallText: String): Int {
        var count = bodyCount.count(streamedText) + toolCallCount.count(toolCallText)
        processCounts.keys.retainAll(process.map { it.id }.toSet())
        process.forEach { event ->
            count += processCounts.getOrPut(event.id) { CachedCount(countTokens) }.count(event.content)
        }
        return count
    }

    /** 工具参数、工具结果和搜索结果。必须在 [estimate] 之后调用，好复用已经数过的缓存。 */
    fun toolTokens(process: List<ProcessEvent>, toolCallText: String): Int {
        var count = toolCallCount.count(toolCallText)
        process.forEach { event ->
            if (event.kind == ProcessEventKind.TOOL || event.kind == ProcessEventKind.SEARCH) {
                count += processCounts.getOrPut(event.id) { CachedCount(countTokens) }.count(event.content)
            }
        }
        return count
    }

    private class CachedCount(private val countTokens: (String) -> Int) {
        private var content: String? = null
        private var tokens = 0

        fun count(text: String): Int {
            if (content != text) {
                tokens = if (text.isEmpty()) 0 else countTokens(text)
                content = text
            }
            return tokens
        }
    }
}
