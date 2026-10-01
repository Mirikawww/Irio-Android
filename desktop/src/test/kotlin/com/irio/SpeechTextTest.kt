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
}
