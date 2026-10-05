package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionTest {
    @Test
    fun planCompressesEverythingAfterTheLastBoundary() {
        val history = (1L..4L).map { msg(it, "m$it", fromUser = it % 2L == 1L) }
        val plan = planCompression(history)
        assertEquals(listOf(1L, 2L, 3L, 4L), plan!!.summarize.map { it.id })
        assertEquals(4L, plan.untilId)
        assertNull(plan.priorSummary)
    }

    @Test
    fun nothingNewAfterASuccessfulBoundaryIsNotCompressedAgain() {
        val history = listOf(
            msg(1, "old", fromUser = true),
            msg(2, "已经压过", fromUser = false, state = SummaryState.SUCCESS),
        )
        assertNull(planCompression(history))
    }

    @Test
    fun aLaterSegmentIncludesThePreviousSummaryInTheCompressionInput() {
        val history = listOf(
            msg(1, "上一份", fromUser = false, state = SummaryState.SUCCESS),
            msg(2, "新问题", fromUser = true),
            msg(3, "新回答", fromUser = false),
        )
        val plan = planCompression(history)
        assertEquals(listOf(2L, 3L), plan!!.summarize.map { it.id })
        assertEquals("上一份", plan.priorSummary)
        val turns = summaryTurns(plan)
        assertEquals("上一份", turns.first().text)
        assertEquals("user", turns[1].role)
        assertEquals("assistant", turns[2].role)
        assertTrue(turns.last().text.contains("<app_control>"))
    }

    @Test
    fun requestStartsAFreshConversationFromTheSummary() {
        val success = msg(4, "交接记录", fromUser = false, state = SummaryState.SUCCESS)
        val later = msg(5, "继续", fromUser = true)
        val messages = listOf(msg(1, "很早"), msg(2, "也很早", fromUser = false), success, later)
        val request = modelRequestMessages(messages)
        assertEquals(listOf("交接记录", CONTINUATION_PROMPT, "继续"), request.map { it.text })
        assertEquals(listOf("user", "user", "user"), request.map { it.role })
        assertEquals("chat#4", modelConversationScope("chat", messages))
        assertEquals("chat", modelConversationScope("chat", listOf(msg(1, "很早"))))
    }

    @Test
    fun aFailedBoundaryDoesNotCutTheHistory() {
        val messages = listOf(
            msg(1, "before"),
            msg(2, "失败", fromUser = false, state = SummaryState.FAILED),
            msg(3, "after", fromUser = false),
        )
        assertEquals(listOf("before", "after"), modelRequestMessages(messages).map { it.text })
        assertFalse(isBeforeCompression(messages, 1))
    }

    @Test
    fun visualizeMarksOnlyMessagesBeforeTheBoundary() {
        val messages = listOf(
            msg(1, "旧"),
            msg(2, "记录", fromUser = false, state = SummaryState.SUCCESS),
            msg(3, "新", fromUser = true),
        )
        assertTrue(isBeforeCompression(messages, 1))
        assertFalse(isBeforeCompression(messages, 2))
        assertFalse(isBeforeCompression(messages, 3))
    }

    @Test
    fun compressionPromptStatesTheTarget() {
        val prompt = summarySystemPrompt(targetPercent = 10, budgetTokens = 8_000)
        assertTrue(prompt.contains("10%"))
        assertTrue(prompt.contains("8000"))
        assertTrue(summarySystemPrompt(10, 0).contains("10%"))
        assertFalse(summarySystemPrompt(10, 0).contains("token"))
    }

    @Test
    fun autoCompressNeedsAWindowAndTheTriggerPercent() {
        assertFalse(shouldAutoCompress(enabled = true, windowTokens = 0, triggerPercent = 90, estimatedTokens = 100))
        assertFalse(shouldAutoCompress(enabled = false, windowTokens = 1000, triggerPercent = 90, estimatedTokens = 900))
        assertFalse(shouldAutoCompress(enabled = true, windowTokens = 1000, triggerPercent = 90, estimatedTokens = 899))
        assertTrue(shouldAutoCompress(enabled = true, windowTokens = 1000, triggerPercent = 90, estimatedTokens = 900))
    }

    @Test
    fun compressionCountIsSuccessfulBoundariesOnly() {
        val messages = listOf(
            msg(1, "a"),
            msg(2, "one", fromUser = false, state = SummaryState.SUCCESS),
            msg(3, "b"),
            msg(4, "running", fromUser = false, state = SummaryState.RUNNING),
            msg(5, "failed", fromUser = false, state = SummaryState.FAILED),
            msg(6, "two", fromUser = false, state = SummaryState.SUCCESS),
        )
        assertEquals(2, compressionCount(messages))
        assertEquals(0, compressionCount(emptyList()))
    }

    @Test
    fun budgetIsTheTargetSliceOfTheWindow() {
        assertEquals(10_000, summaryBudgetTokens(windowTokens = 100_000, targetPercent = 10))
        assertEquals(0, summaryBudgetTokens(windowTokens = 0, targetPercent = 10))
    }

    @Test
    fun estimatorCountsText() {
        assertTrue(TokenEstimator.count("hello") > 0)
        assertEquals(0, TokenEstimator.count(""))
        assertTrue(TokenEstimator.count("上下文压缩") > 0)
    }

    @Test
    fun requestEstimateCountsThinkingAndToolCalls() {
        val textOnly = estimateRequestTokens(
            listOf(ChatRequestMessage(role = "assistant", text = "答案")),
        )
        val withThinking = estimateRequestTokens(
            listOf(
                ChatRequestMessage(
                    role = "assistant",
                    text = "答案",
                    thinking = "先把上下文里已经用掉的部分算清楚",
                ),
            ),
        )
        val withTool = estimateRequestTokens(
            listOf(
                ChatRequestMessage(
                    role = "assistant",
                    text = "答案",
                    toolCalls = listOf(
                        ChatToolCall(id = "1", name = "web_search", arguments = """{"query":"token estimation"}"""),
                    ),
                ),
            ),
        )
        assertTrue(withThinking > textOnly)
        assertTrue(withTool > textOnly)
        assertEquals(
            textOnly,
            estimateRequestTokens(listOf(ChatRequestMessage(role = "assistant", text = "答案", thinking = ""))),
        )
    }

    @Test
    fun storedThinkingIsPartOfTheNextRequestEstimate() {
        val plain = msg(1, "答案", fromUser = false)
        val thought = plain.copy(
            process = listOf(
                ProcessEvent(id = 1, kind = ProcessEventKind.THINKING, content = "想了很久才决定怎么回答"),
            ),
        )
        assertTrue(
            estimateRequestTokens(modelRequestMessages(listOf(thought))) >
                estimateRequestTokens(modelRequestMessages(listOf(plain))),
        )
    }

    @Test
    fun liveReplyEstimateAddsThinkingToolsAndSearch() {
        val text = estimateLiveReplyTokens("正文", emptyList(), "")
        assertEquals(TokenEstimator.count("正文"), text)
        val arguments = """web_search{"query":"irio"}"""
        val full = estimateLiveReplyTokens(
            streamedText = "正文",
            process = listOf(
                ProcessEvent(id = 1, kind = ProcessEventKind.THINKING, content = "推理过程"),
                ProcessEvent(id = 2, kind = ProcessEventKind.TOOL, content = "命令输出", toolName = "shell"),
                ProcessEvent(id = 3, kind = ProcessEventKind.SEARCH, content = "网页摘要"),
            ),
            toolCallText = arguments,
        )
        assertTrue(full > estimateLiveReplyTokens("正文", emptyList(), arguments))
    }

    @Test
    fun compressionStatusIsATranscriptLabel() {
        assertEquals("上下文压缩中", compressionStatusLabel(SummaryState.RUNNING))
        assertEquals("已压缩上下文", compressionStatusLabel(SummaryState.SUCCESS))
        assertEquals("上下文压缩失败", compressionStatusLabel(SummaryState.FAILED))
        assertNull(compressionStatusLabel(SummaryState.NONE))
    }

    @Test
    fun modelWrapperIsStrippedBeforeTheRecordIsStored() {
        assertEquals("用户要改设置。", stripContextSummary("<context_summary>\n用户要改设置。\n</context_summary>"))
        assertEquals("只有记录", stripContextSummary("只有记录"))
    }

    private fun msg(
        id: Long,
        text: String,
        fromUser: Boolean = true,
        state: SummaryState = SummaryState.NONE,
    ): ChatMessage = ChatMessage(
        id = id,
        text = text,
        fromUser = fromUser,
        summaryState = state,
        summaryUntilId = if (state == SummaryState.SUCCESS) id else 0L,
    )
}
