package com.irio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveReplyTokenEstimatorTest {
    @Test
    fun completedSearchIsCountedOnceWhileBodyKeepsGrowing() {
        val search = "unchanged search results".repeat(1000)
        var searchCounts = 0
        val estimator = LiveReplyTokenEstimator { text ->
            if (text === search) searchCounts++
            text.length
        }
        val process = listOf(ProcessEvent(id = 1, kind = ProcessEventKind.SEARCH, content = search))
        repeat(20) { chunk ->
            val body = "reply".repeat(chunk + 1)
            assertEquals(body.length + search.length, estimator.estimate(body, process, ""))
        }
        assertEquals("Completed search text must not be tokenized on every publish", 1, searchCounts)
    }

    @Test
    fun tokenizerDoesNotRunOnTheUiCallerThread() = runBlocking {
        val callerThread = Thread.currentThread()
        var tokenizerThread: Thread? = null
        val estimator = LiveReplyTokenEstimator { text ->
            tokenizerThread = Thread.currentThread()
            text.length
        }
        assertEquals(5, estimator.estimateOnWorker(true, "reply", emptyList(), ""))
        assertNotEquals("Tokenization must be dispatched off the UI caller", callerThread, tokenizerThread)
    }

    @Test
    fun disabledRingDoesNotInvokeTheTokenizer() = runBlocking {
        var calls = 0
        val estimator = LiveReplyTokenEstimator { calls++; it.length }
        assertNull(estimator.estimateOnWorker(false, "reply", emptyList(), "tool call"))
        assertEquals(0, calls)
    }

    @Test
    fun eventMetadataChangesDoNotRetokenizeUnchangedContent() {
        val counted = mutableListOf<String>()
        val estimator = LiveReplyTokenEstimator { counted += it; it.length }
        val event = ProcessEvent(1, ProcessEventKind.THINKING, content = "thinking", live = true)
        estimator.estimate("body", listOf(event), "tool")
        estimator.estimate("body", listOf(event.copy(live = false, millis = 100)), "tool")
        assertEquals(listOf("body", "tool", "thinking"), counted)
    }

    @Test
    fun changingToolOutputInvalidatesOnlyThatEvent() {
        val counted = mutableListOf<String>()
        val estimator = LiveReplyTokenEstimator { counted += it; it.length }
        val search = ProcessEvent(1, ProcessEventKind.SEARCH, content = "search")
        val tool = ProcessEvent(2, ProcessEventKind.TOOL, content = "first")
        estimator.estimate("body", listOf(search, tool), "arguments")
        counted.clear()
        assertEquals(
            "body".length + "search".length + "second".length + "arguments".length,
            estimator.estimate("body", listOf(search, tool.copy(content = "second")), "arguments"),
        )
        assertEquals(listOf("second"), counted)
    }

    @Test
    fun removedEventsStopContributingAndTheirCacheIsReleased() {
        var eventCounts = 0
        val estimator = LiveReplyTokenEstimator { if (it == "search") eventCounts++; it.length }
        val event = ProcessEvent(1, ProcessEventKind.SEARCH, content = "search")
        assertEquals(6, estimator.estimate("", listOf(event), ""))
        assertEquals(0, estimator.estimate("", emptyList(), ""))
        assertEquals(6, estimator.estimate("", listOf(event), ""))
        assertEquals(2, eventCounts)
    }

    @Test
    fun cachedEstimateMatchesTheExactTokenizerAcrossToolRounds() {
        val estimator = LiveReplyTokenEstimator()
        val thinking = ProcessEvent(1, ProcessEventKind.THINKING, content = "先分析用户问题", live = false)
        val search = ProcessEvent(2, ProcessEventKind.SEARCH, content = "网页标题\nhttps://example.com\n网页正文", live = false)
        repeat(8) { round ->
            val body = "模型正文".repeat(round + 1)
            val arguments = "web_search{\"query\":\"最新资料$round\"}"
            val process = listOf(thinking, search, ProcessEvent(3, ProcessEventKind.TOOL, content = "工具输出$round"))
            assertEquals(estimateLiveReplyTokens(body, process, arguments), estimator.estimate(body, process, arguments))
        }
    }

    @Test
    fun concurrentRepliesHaveIndependentRequestCaches() {
        val first = LiveReplyTokenEstimator { it.length }
        val second = LiveReplyTokenEstimator { it.length }
        val search = ProcessEvent(1, ProcessEventKind.SEARCH, content = "search")
        assertEquals(9, first.estimate("one", listOf(search), ""))
        assertEquals(3, second.estimate("two", emptyList(), ""))
        assertEquals(9, first.estimate("one", listOf(search), ""))
    }
}
