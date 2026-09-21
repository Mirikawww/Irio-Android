package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTrackerToolTest {
    @Test
    fun toolEventBeforeTextRendersAbove() {
        val tracker = ProcessTracker()
        tracker.onToolStarted()
        tracker.onToolFinished()
        val event = tracker.snapshot().single()
        assertEquals(ProcessEventKind.TOOL, event.kind)
        assertEquals("", event.toolName)
        assertFalse(event.live)
        assertFalse(event.belowText)
    }

    @Test
    fun toolEventAfterOutputStartedIsBelowText() {
        val tracker = ProcessTracker()
        tracker.onThinkingDelta("计划", now = 1_000)
        tracker.onOutputStarted(now = 1_500)
        tracker.onToolStarted()
        tracker.onToolFinished()
        val events = tracker.snapshot()
        val tool = events.last()
        assertEquals(ProcessEventKind.TOOL, tool.kind)
        assertFalse(tool.live)
        assertTrue(tool.belowText)
        // 输出开始前的思考在上方
        assertFalse(events.first().belowText)
    }

    @Test
    fun toolEventFinishAllClosesLive() {
        val tracker = ProcessTracker()
        tracker.onOutputStarted()
        tracker.onToolStarted()
        tracker.finishAll()
        val tool = tracker.snapshot().last()
        assertFalse(tool.live)
        assertTrue(tool.belowText)
    }

    @Test
    fun toolEventsAfterSearchDoNotBreakSearchAccounting() {
        val tracker = ProcessTracker()
        tracker.onSearchStarted()
        tracker.onSearchFinished(2, "内容")
        tracker.onOutputStarted()
        tracker.onToolStarted()
        tracker.onToolFinished()
        val events = tracker.snapshot()
        assertEquals(ProcessEventKind.SEARCH, events[0].kind)
        assertEquals(2, events[0].pages)
        assertEquals(ProcessEventKind.TOOL, events[1].kind)
        assertTrue(events[1].belowText)
    }

    @Test
    fun toolEventKeepsName() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(MemoryTool.UPDATE)
        tracker.onToolFinished()
        val event = tracker.snapshot().single()
        assertEquals(MemoryTool.UPDATE, event.toolName)
        assertFalse(event.live)
    }

    @Test
    fun webFetchToolEventKeepsFetchedContentForDetails() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(WebFetchTool.NAME)
        tracker.onToolFinished("网页正文")
        val event = tracker.snapshot().single()
        assertEquals(WebFetchTool.NAME, event.toolName)
        assertEquals("网页正文", event.content)
        assertFalse(event.live)
    }
}
