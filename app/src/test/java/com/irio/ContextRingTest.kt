package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextRingTest {
    @Test
    fun missingUsageKeepsTheLocalEstimateAfterGeneration() {
        assertEquals(
            240,
            contextRingUsedTokens(apiUsed = 0, liveEstimate = 240, generatingReply = false),
        )
    }

    @Test
    fun restoredConversationWithoutUsageCanEstimateItsSelectedHistory() {
        val messages = listOf(
            ChatMessage(id = 1, text = "hello", fromUser = true),
            ChatMessage(id = 2, text = "hi", fromUser = false),
            ChatMessage(id = 3, text = "unselected".repeat(100), fromUser = true, selected = false),
            ChatMessage(id = 4, text = "error".repeat(100), fromUser = false, isError = true),
        )
        val request = modelRequestMessages(contextMessages(messages))
        assertEquals(listOf("hello", "hi"), request.map { it.text })
        val estimate = estimateRequestTokens(request)
        assertTrue(estimate > 0)
        assertEquals(estimate, contextRingUsedTokens(0, estimate, generatingReply = false))
    }

    @Test
    fun emptyConversationStillUsesZero() {
        val estimate = estimateRequestTokens(modelRequestMessages(emptyList()))
        assertEquals(0, contextRingUsedTokens(0, estimate, generatingReply = false))
    }

    @Test
    fun formatsCountsWithThousandsSeparators() {
        assertEquals("0", formatContextCount(0))
        assertEquals("999", formatContextCount(999))
        assertEquals("1,000", formatContextCount(1_000))
        assertEquals("1,048,576", formatContextCount(1_048_576))
    }

    @Test
    fun unknownWindowIsADash() {
        assertEquals("—", contextWindowLabel(0))
        assertEquals("128,000", contextWindowLabel(128_000))
    }

    @Test
    fun compressionKeepsTheLastReportedUsage() {
        assertEquals(
            90_000,
            contextRingUsedTokens(apiUsed = 90_000, liveEstimate = 12_800, generatingReply = false),
        )
        assertEquals(12_800, contextRingUsedTokens(apiUsed = 90_000, liveEstimate = 12_800, generatingReply = true))
        assertEquals(90_000, contextRingUsedTokens(apiUsed = 90_000, liveEstimate = 0, generatingReply = true))
        assertEquals(
            3_200,
            contextRingUsedTokens(
                apiUsed = 90_000,
                liveEstimate = 12_800,
                generatingReply = false,
                compressionEstimate = 3_200,
            ),
        )
        assertEquals(
            12_800,
            contextRingUsedTokens(
                apiUsed = 90_000,
                liveEstimate = 12_800,
                generatingReply = true,
                compressionEstimate = 3_200,
            ),
        )
    }
}
