package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun stripsCodeMathAndKeepsLinkLabel() {
        val raw = """
            先看 [文档](https://example.com)。
            ```
            code()
            ```
            ${'$'}x^2${'$'} 结束
        """.trimIndent()
        val spoken = speechPlainText(raw)
        assertTrue(spoken.contains("文档"))
        assertTrue(spoken.contains("先看"))
        assertTrue(spoken.contains("结束"))
        assertFalse(spoken.contains("code"))
        assertFalse(spoken.contains("http"))
        assertFalse(spoken.contains("x^2"))
    }

    @Test
    fun stripsHeadingAndEmphasis() {
        assertEquals("标题", speechPlainText("# 标题"))
        assertEquals("重点", speechPlainText("**重点**"))
    }

    @Test
    fun blankAndCodeOnlyStayEmpty() {
        assertEquals("", speechPlainText("   "))
        assertEquals("", speechPlainText("```\nval x = 1\n```"))
    }

    @Test
    fun chunksStayWithinLimitAndCoverSentences() {
        val text = "第一句。第二句！第三句？" + "甲".repeat(80)
        val chunks = speechChunks(text, limit = 12)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 12 })
        assertEquals("第一句。", chunks.first())
    }

    @Test
    fun clockFormatsMinutesAndSeconds() {
        assertEquals("0:00", formatSpeechClock(0))
        assertEquals("1:05", formatSpeechClock(65_000))
    }

    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("你好"), speechChunks("  你好  ", limit = 20))
        assertTrue(speechChunks("   ").isEmpty())
    }

    @Test
    fun chunksKeepNumbersDecimalsAndClockTimesIntact() {
        val text = "甲".repeat(8) + "价格是3.14元，合计1,024人，用时10:30。" + "乙".repeat(40)
        val chunks = speechChunks(text, limit = 16)
        assertContainsToken(chunks, "3.14")
        assertContainsToken(chunks, "1,024")
        assertContainsToken(chunks, "10:30")
        assertEquals(compact(text), compact(chunks.joinToString("")))
    }

    @Test
    fun hardCutDoesNotSeparateDigitsOrLeaveThemOnTheNextChunk() {
        val text = "甲".repeat(SPEECH_CHUNK_LIMIT - 1) + "10" + "。后面。" + "乙".repeat(80)
        val chunks = speechChunks(text)
        assertContainsToken(chunks, "10")
        assertTrue(chunks.none { it.endsWith("1") })
        assertEquals(compact(text), compact(chunks.joinToString("")))
    }

    @Test
    fun decimalStraddlingTheLimitStaysInOneChunk() {
        val text = "甲".repeat(SPEECH_CHUNK_LIMIT - 2) + "3.14" + "。后面" + "乙".repeat(80)
        val chunks = speechChunks(text)
        assertContainsToken(chunks, "3.14")
        assertEquals(compact(text), compact(chunks.joinToString("")))
    }

    @Test
    fun sentenceBreakDoesNotWinOverAThousandsSeparator() {
        val text = "甲".repeat(120) + "合计1,024人。后面还有内容" + "乙".repeat(400)
        val chunks = speechChunks(text)
        assertContainsToken(chunks, "1,024")
        assertEquals(compact(text), compact(chunks.joinToString("")))
    }

    @Test
    fun numberAfterASentenceStaysWithThePrecedingClause() {
        val text = "甲".repeat(20) + "。10个苹果。" + "乙".repeat(40)
        val chunks = speechChunks(text, limit = 24)
        assertTrue(chunks.none { it.startsWith("10") })
        assertContainsToken(chunks, "10")
    }

    @Test
    fun timelineShowsTheWholeTextBeforeAnyAudioExists() {
        val chunks = listOf("甲".repeat(450), "乙".repeat(450))
        val total = speechTimelineMs(chunks, intArrayOf(-1, -1), 1f)
        assertEquals(180_000, total)
        assertEquals(45_000, estimateSpeechMs(chunks.first(), 2f))
    }

    @Test
    fun measuredAudioReplacesItsEstimateAndScalesTheRemainder() {
        val chunks = listOf("甲".repeat(450), "乙".repeat(450))
        val partial = speechTimelineMs(chunks, intArrayOf(60_000, -1), 1f)
        assertEquals(120_000, partial)
        assertEquals(130_000, speechTimelineMs(chunks, intArrayOf(60_000, 70_000), 1f))
    }

    private fun assertContainsToken(chunks: List<String>, token: String) {
        assertTrue(chunks.any { token in it })
    }

    private fun compact(text: String) = text.replace(Regex("\\s+"), "")
}
