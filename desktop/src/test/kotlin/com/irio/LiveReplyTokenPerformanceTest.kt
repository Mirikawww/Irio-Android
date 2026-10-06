package com.irio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.system.measureNanoTime

class LiveReplyTokenPerformanceTest {
    @Test
    fun measuresTheExistingSearchAfterOutputHotPath() {
        // A local fixture, not a captured provider response or the user's conversation.
        val search = ("网页正文：搜索完成之后，模型根据网页内容生成回答。 " +
            "Article content about streaming, rendering, and token estimation.\n").repeat(800)
        val process = listOf(ProcessEvent(id = 1, kind = ProcessEventKind.SEARCH, content = search))
        val body = "根据搜索结果，以下是回答。"
        val arguments = "web_search{\"query\":\"rendering performance\"}"
        // Exclude vocabulary initialization and warm up the exact tokenizer.
        TokenEstimator.count(search)
        var withoutSearch = 0
        val plainNs = measureNanoTime {
            repeat(20) { withoutSearch = estimateLiveReplyTokens(body.repeat(it + 1), emptyList(), arguments) }
        }
        var withSearch = 0
        val searchNs = measureNanoTime {
            repeat(20) { withSearch = estimateLiveReplyTokens(body.repeat(it + 1), process, arguments) }
        }
        assertEquals(withoutSearch + TokenEstimator.count(search), withSearch)
        val estimator = LiveReplyTokenEstimator()
        var cached = 0
        val cachedNs = measureNanoTime {
            repeat(20) { cached = estimator.estimate(body.repeat(it + 1), process, arguments) }
        }
        assertEquals(withSearch, cached)
        println("TOKEN_COMPARISON fixtureChars=${search.length} updates=20 plainMs=${plainNs / 1_000_000.0} searchMs=${searchNs / 1_000_000.0} cachedSearchMs=${cachedNs / 1_000_000.0}")
    }
}
