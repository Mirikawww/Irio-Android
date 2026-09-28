package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTimestampTest {
    @Test
    fun userMessageGetsLocalTimestampPrefix() {
        val createdAt = 1_700_000_000_000L
        val stamped = ChatApi.stampMessageText("hello", createdAt, fromUser = true)
        assertTrue(stamped.endsWith(" hello"))
        assertTrue(Regex("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}] hello$").matches(stamped))
    }

    @Test
    fun blankUserMessageIsTimestampOnly() {
        val stamped = ChatApi.stampMessageText("  ", 1_700_000_000_000L, fromUser = true)
        assertTrue(Regex("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}]$").matches(stamped))
    }

    @Test
    fun assistantAndDisabledTimestampsStayPlain() {
        val createdAt = 1_700_000_000_000L
        assertEquals("hello", ChatApi.stampMessageText("hello", createdAt, fromUser = false))
        assertEquals("hello", ChatApi.stampMessageText("hello", 0L, fromUser = true))
    }

    @Test
    fun replyClockIsHourMinuteSecondOfFinishTime() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val finishedAt = java.time.LocalDateTime.of(2024, 1, 15, 13, 4, 5)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals("13:04:05", formatReplyClock(finishedAt, zone))
        assertEquals("", formatReplyClock(0L, zone))
    }
}
