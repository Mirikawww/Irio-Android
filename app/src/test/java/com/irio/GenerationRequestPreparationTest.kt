package com.irio

import org.junit.Assert.*
import org.junit.Test

class GenerationRequestPreparationTest {
    private val source = listOf(ChatMessage(1, "old question", true), ChatMessage(2, "old answer", false), ChatMessage(3, "current question", true))

    @Test fun disabledPreflightDoesNotLookUpTheModelOrTokenize() {
        var counts = 0
        val request = GenerationRequestPreparation(source, estimateRequest = { counts++; 100 })
        assertNull(request.compressionPlan(false, 90, { error("No model lookup when disabled") }, { error("No window lookup when disabled") }))
        assertEquals(0, counts)
        assertEquals(100, request.requestTokens)
        assertEquals(100, request.requestTokens)
        assertEquals(1, counts)
    }

    @Test fun imageAndUnknownWindowPreflightsDoNotTokenize() {
        val request = GenerationRequestPreparation(source, estimateRequest = { error("Must not tokenize") })
        assertNull(request.compressionPlan(true, 90, { true }, { error("Image model has no chat preflight") }))
        assertNull(request.compressionPlan(true, 90, { false }, { 0 }))
    }

    @Test fun preflightCachesTheExactEstimateAndOnlyCountsToolsWhenRequested() {
        var historyCounts = 0
        var toolCounts = 0
        val request = GenerationRequestPreparation(source,
            estimateRequest = { historyCounts++; assertEquals(modelRequestMessages(source), it); 89 },
            estimateTools = { toolCounts++; assertEquals(source, it); 12 })
        assertNull(request.compressionPlan(true, 90, { false }, { 100 }))
        assertEquals(0, toolCounts)
        repeat(3) {
            assertEquals(89, request.requestTokens)
            assertEquals(12, request.toolContextTokens)
        }
        assertEquals(1, historyCounts)
        assertEquals(1, toolCounts)
    }

    @Test fun compressionThresholdKeepsTheNewUserTurnOutsideTheSummary() {
        val request = GenerationRequestPreparation(source, estimateRequest = { 90 })
        val plan = request.compressionPlan(true, 90, { false }, { 100 })!!
        assertEquals(source.dropLast(1), plan.summarize)
        assertEquals(2L, plan.untilId)
    }

    @Test fun aSuccessfulSummaryRequiresAFreshEstimateButFailedCardsDoNotCutTheHistory() {
        val initial = GenerationRequestPreparation(source)
        val summary = ChatMessage(4, "short summary", false, summaryState = SummaryState.SUCCESS, summaryUntilId = 2)
        val compressedSource = source.take(2) + summary + source.last()
        val compressed = GenerationRequestPreparation(compressedSource)
        assertEquals(modelRequestMessages(compressedSource), compressed.messages)
        assertEquals(estimateRequestTokens(modelRequestMessages(compressedSource)), compressed.requestTokens)
        assertNotEquals(initial.messages, compressed.messages)
        val failedSource = source.take(2) + summary.copy(summaryState = SummaryState.FAILED) + source.last()
        assertEquals(initial.requestTokens, GenerationRequestPreparation(failedSource).requestTokens)
    }

    @Test fun estimatesKeepThinkingToolResultsHiddenTurnsAndCompressionBoundaries() {
        val call = ChatToolCall("call", "local_tool", "{\"value\":123}")
        val history = listOf(
            ChatMessage(1, "not sent", true),
            ChatMessage(2, "prior summary", false, summaryState = SummaryState.SUCCESS),
            ChatMessage(3, "hidden context", true, hidden = true),
            ChatMessage(4, "answer", false, process = listOf(ProcessEvent(8, ProcessEventKind.THINKING, content = "thinking")),
                toolRecords = listOf(ChatToolRecord(call, "tool result"))),
            ChatMessage(5, "current", true, quote = "quoted context"),
        )
        val prepared = GenerationRequestPreparation(history)
        assertEquals(modelRequestMessages(history), prepared.messages)
        assertEquals(estimateRequestTokens(modelRequestMessages(history)), prepared.requestTokens)
        assertEquals(estimateToolContextTokens(history), prepared.toolContextTokens)
        assertTrue(prepared.messages.none { it.text == "not sent" })
        assertTrue(prepared.messages.any { it.text == "hidden context" })
    }

    @Test fun requestsAreIndependentAndRetainTheirOwnSourceSnapshot() {
        val mutableSource = source.toMutableList()
        var firstCounts = 0
        var secondCounts = 0
        val first = GenerationRequestPreparation(mutableSource, estimateRequest = { firstCounts++; 12 })
        mutableSource.clear()
        val second = GenerationRequestPreparation(listOf(ChatMessage(99, "other conversation", true)), estimateRequest = { secondCounts++; 25 })
        assertEquals(modelRequestMessages(source), first.messages)
        assertEquals(12, first.requestTokens)
        assertEquals(25, second.requestTokens)
        assertEquals(12, first.requestTokens)
        assertEquals(1, firstCounts); assertEquals(1, secondCounts)
    }
}
