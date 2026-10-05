package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionTest {
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
    fun compressionStatusIsATranscriptLabel() {
        assertEquals("上下文压缩中", compressionStatusLabel(SummaryState.RUNNING))
        assertEquals("已压缩上下文", compressionStatusLabel(SummaryState.SUCCESS))
        assertEquals("上下文压缩失败", compressionStatusLabel(SummaryState.FAILED))
        assertNull(compressionStatusLabel(SummaryState.NONE))
    }
}
