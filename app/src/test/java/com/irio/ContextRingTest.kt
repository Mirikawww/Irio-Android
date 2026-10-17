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

    @Test
    fun apiUsageAlreadyIncludesSystemPromptAndTools() {
        val occupied = contextRingOccupied(apiUsed = 90_000, liveEstimate = 12_800, generatingReply = false)
        assertEquals(true, occupied.includesOverhead)
        val breakdown = contextUsageBreakdown(
            windowTokens = 128_000,
            occupiedTokens = occupied.tokens,
            occupiedIncludesOverhead = occupied.includesOverhead,
            systemTokens = 618,
            toolTokens = 4_100,
            messageTokens = 90_000 - 618 - 4_100,
            reservedBudget = contextReservedBudget(128_000, compressionEnabled = true, triggerPercent = 90),
        )
        assertEquals(90_000, breakdown.usedTokens)
        assertEquals(618, breakdown.systemTokens)
        assertEquals(4_100, breakdown.toolTokens)
        assertEquals(90_000 - 618 - 4_100, breakdown.messageTokens)
        assertEquals(12_800, breakdown.reservedTokens)
        assertEquals(128_000 - 90_000 - 12_800, breakdown.freeTokens)
        assertEquals(70, breakdown.percent)
    }

    @Test
    fun completedUsageKeepsMessageShareWhenLocalToolsExceedApiTotal() {
        val localTotal = 900
        val system = 100
        val tools = 600
        val messages = localTotal - tools
        val live = contextRingOccupied(apiUsed = 500, liveEstimate = localTotal, generatingReply = true)
        val completed = contextRingOccupied(apiUsed = 500, liveEstimate = localTotal, generatingReply = false)
        val breakdowns = listOf(live, completed).map { occupied ->
            contextUsageBreakdown(
                windowTokens = 2_000,
                occupiedTokens = occupied.tokens,
                occupiedIncludesOverhead = occupied.includesOverhead,
                systemTokens = system,
                toolTokens = tools,
                messageTokens = messages,
                reservedBudget = 0,
            )
        }
        assertEquals(300, breakdowns[0].messageTokens)
        assertEquals(500, breakdowns[1].usedTokens)
        assertEquals(150, breakdowns[1].messageTokens)
        assertEquals(50, breakdowns[1].systemTokens)
        assertEquals(300, breakdowns[1].toolTokens)
        assertEquals(500, breakdowns[1].systemTokens + breakdowns[1].toolTokens + breakdowns[1].messageTokens)
    }

    @Test
    fun apiCalibrationPreservesCategoriesAndTotalsAcrossUsageScales() {
        val cases = listOf(
            Triple(100, 600, 300),
            Triple(100, 0, 300),
            Triple(0, 600, 300),
            Triple(100, 600, 0),
            Triple(100, 0, 0),
            Triple(0, 0, 0),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
        )
        cases.forEach { (system, tools, messages) ->
            listOf(0, 1, 500, 2_000, Int.MAX_VALUE).forEach { apiUsed ->
                val breakdown = contextUsageBreakdown(
                    windowTokens = 2_000,
                    occupiedTokens = apiUsed,
                    occupiedIncludesOverhead = true,
                    systemTokens = system,
                    toolTokens = tools,
                    messageTokens = messages,
                    reservedBudget = 200,
                )
                assertEquals(apiUsed, breakdown.usedTokens)
                assertEquals(apiUsed.toLong(), breakdown.systemTokens.toLong() + breakdown.toolTokens + breakdown.messageTokens)
                assertTrue(breakdown.systemTokens >= 0)
                assertTrue(breakdown.toolTokens >= 0)
                assertTrue(breakdown.messageTokens >= 0)
                if (tools == 0) assertEquals(0, breakdown.toolTokens)
                if (messages == 0 && system.toLong() + tools > 0) assertEquals(0, breakdown.messageTokens)
                assertEquals(maxOf(2_000L, apiUsed.toLong()), apiUsed.toLong() + breakdown.freeTokens + breakdown.reservedTokens)
            }
        }
    }

    @Test
    fun restoredToolHistoryRetainsMessageTokensWithSmallerApiUsage() {
        val history = listOf(
            ChatMessage(1L, "ignore old tools", false, toolRecords = listOf(
                ChatToolRecord(ChatToolCall("old", "get_time", "{}"), "old result".repeat(100)),
            )),
            ChatMessage(2L, "此前对话摘要", false, summaryState = SummaryState.SUCCESS),
            ChatMessage(3L, "请查询时间，并解释时区", true),
            ChatMessage(4L, "当前时间和时区说明".repeat(20), false, toolRecords = listOf(
                ChatToolRecord(ChatToolCall("now", "get_time", "{}"), "12:00 UTC+8".repeat(100)),
            )),
        )
        val included = contextMessages(history)
        val localTotal = estimateRequestTokens(modelRequestMessages(included))
        val tools = estimateToolContextTokens(included)
        assertEquals(TokenEstimator.count(history.last().toolContextText()), tools)
        assertTrue(localTotal > tools)
        val occupied = contextRingOccupied(apiUsed = tools, liveEstimate = localTotal, generatingReply = false)
        val breakdown = contextUsageBreakdown(
            windowTokens = 8_000,
            occupiedTokens = occupied.tokens,
            occupiedIncludesOverhead = occupied.includesOverhead,
            systemTokens = 40,
            toolTokens = tools,
            messageTokens = localTotal - tools,
            reservedBudget = 0,
        )
        assertEquals(tools, breakdown.usedTokens)
        assertTrue(breakdown.messageTokens > 0)
        assertTrue(breakdown.toolTokens < breakdown.usedTokens)
    }

    @Test
    fun localEstimateKeepsToolContextInsideTheMessages() {
        val occupied = contextRingOccupied(apiUsed = 0, liveEstimate = 500, generatingReply = true)
        assertEquals(false, occupied.includesOverhead)
        val breakdown = contextUsageBreakdown(
            windowTokens = 1_000,
            occupiedTokens = occupied.tokens,
            occupiedIncludesOverhead = occupied.includesOverhead,
            systemTokens = 100,
            toolTokens = 120,
            messageTokens = 380,
            reservedBudget = 0,
        )
        assertEquals(600, breakdown.usedTokens)
        assertEquals(100, breakdown.systemTokens)
        assertEquals(120, breakdown.toolTokens)
        assertEquals(380, breakdown.messageTokens)
        assertEquals(400, breakdown.freeTokens)
    }

    @Test
    fun reservedTailShrinksOnceUsageEatsIt() {
        val breakdown = contextUsageBreakdown(
            windowTokens = 1_000,
            occupiedTokens = 950,
            occupiedIncludesOverhead = true,
            systemTokens = 10,
            toolTokens = 10,
            messageTokens = 930,
            reservedBudget = contextReservedBudget(1_000, compressionEnabled = true, triggerPercent = 90),
        )
        assertEquals(50, breakdown.reservedTokens)
        assertEquals(0, breakdown.freeTokens)
        assertEquals(950, breakdown.systemTokens + breakdown.toolTokens + breakdown.messageTokens)
    }

    @Test
    fun unknownWindowHasNoFreeOrReservedSlice() {
        val breakdown = contextUsageBreakdown(
            windowTokens = 0,
            occupiedTokens = 40,
            occupiedIncludesOverhead = false,
            systemTokens = 10,
            toolTokens = 5,
            messageTokens = 35,
            reservedBudget = 100,
        )
        assertEquals(50, breakdown.usedTokens)
        assertEquals(10, breakdown.systemTokens)
        assertEquals(5, breakdown.toolTokens)
        assertEquals(35, breakdown.messageTokens)
        assertEquals(0, breakdown.freeTokens)
        assertEquals(0, breakdown.reservedTokens)
        assertEquals(null, breakdown.percent)
    }

    @Test
    fun overheadLargerThanApiUsageDoesNotInventMessages() {
        val breakdown = contextUsageBreakdown(
            windowTokens = 1_000,
            occupiedTokens = 30,
            occupiedIncludesOverhead = true,
            systemTokens = 100,
            toolTokens = 80,
            messageTokens = 0,
            reservedBudget = 0,
        )
        assertEquals(30, breakdown.usedTokens)
        assertEquals(16, breakdown.systemTokens)
        assertEquals(14, breakdown.toolTokens)
        assertEquals(0, breakdown.messageTokens)
    }

    @Test
    fun defaultHeadroomMatchesTheStackedWindow() {
        val window = 1_024_000
        val used = 661_818
        val breakdown = contextUsageBreakdown(
            windowTokens = window,
            occupiedTokens = used,
            occupiedIncludesOverhead = true,
            systemTokens = 618,
            toolTokens = 4_100,
            messageTokens = used - 618 - 4_100,
            reservedBudget = contextReservedBudget(window, compressionEnabled = true, triggerPercent = 90),
        )
        assertEquals(657_100, breakdown.messageTokens)
        assertEquals(102_400, breakdown.reservedTokens)
        assertEquals(259_782, breakdown.freeTokens)
        assertEquals(65, breakdown.percent)
        assertEquals("661.8K", formatContextCompact(used))
        assertEquals("1M", formatContextCompact(window))
        assertEquals("4.1K", formatContextCompact(4_100))
        assertEquals("657.1K", formatContextCompact(breakdown.messageTokens))
        assertEquals("259.8K", formatContextCompact(breakdown.freeTokens))
        assertEquals("102.4K", formatContextCompact(breakdown.reservedTokens))
        assertEquals("618", formatContextCompact(618))
    }

    @Test
    fun compactCountsUseDecimalUnits() {
        assertEquals("0", formatContextCompact(0))
        assertEquals("999", formatContextCompact(999))
        assertEquals("1K", formatContextCompact(1_000))
        assertEquals("1.5K", formatContextCompact(1_499))
        assertEquals("-4.1K", formatContextCompact(-4_100))
        assertEquals("1.5M", formatContextCompact(1_500_000))
    }

    @Test
    fun systemPromptTokensIncludeToolDefinitions() {
        val tokens = estimateSystemPromptTokens("hello", """{"name":"get_time"}""")
        assertTrue(tokens > 4 + TokenEstimator.count("hello"))
        assertEquals(0, estimateSystemPromptTokens(null, ""))
        assertEquals(0, estimateSystemPromptTokens("   ", "  "))
    }

    @Test
    fun toolExecutionRecordsAreNotMessageBody() {
        val reply = ChatMessage(
            2L,
            "完成",
            false,
            toolRecords = listOf(ChatToolRecord(ChatToolCall("c", "get_time", "{}"), "12:00")),
        )
        val messages = listOf(ChatMessage(1L, "现在几点", true), reply)
        val toolTokens = estimateToolContextTokens(messages)
        val total = estimateRequestTokens(modelRequestMessages(messages))
        assertTrue(toolTokens > 0)
        assertTrue(reply.toolContextText().contains("12:00"))
        assertEquals(false, reply.messageBodyText().contains("12:00"))
        assertEquals(reply.contextText(), reply.messageBodyText() + "\n\n" + reply.toolContextText())
        assertTrue(total > toolTokens)
        val breakdown = contextUsageBreakdown(
            windowTokens = 10_000,
            occupiedTokens = total,
            occupiedIncludesOverhead = false,
            systemTokens = 30,
            toolTokens = toolTokens,
            messageTokens = total - toolTokens,
            reservedBudget = 0,
        )
        assertEquals(toolTokens, breakdown.toolTokens)
        assertEquals(total - toolTokens, breakdown.messageTokens)
        assertEquals(total + 30, breakdown.usedTokens)
    }
}
