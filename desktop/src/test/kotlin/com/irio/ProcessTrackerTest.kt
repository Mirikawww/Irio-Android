package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTrackerTest {
    @Test
    fun stopDuringSearchClosesSearchAndKeepsPages() {
        val tracker = ProcessTracker()
        tracker.onSearchStarted()
        tracker.onSearchFinished(3, "结果")
        tracker.finishAll(now = 2_000)
        val event = tracker.snapshot().single()
        assertFalse(event.live)
        assertEquals(3, event.pages)
        assertEquals(0, tracker.liveCount())
    }

    @Test
    fun splitsThinkingAroundSearch() {
        val tracker = ProcessTracker()
        tracker.onThinkingDelta("first", now = 1_000)
        tracker.onSearchStarted()
        tracker.onSearchFinished(3, "a\nb\nc")
        tracker.onThinkingDelta("second", now = 4_000)
        tracker.onOutputStarted(now = 4_800)
        val events = tracker.snapshot()
        assertEquals(3, events.size)
        assertEquals(ProcessEventKind.THINKING, events[0].kind)
        assertEquals("first", events[0].content)
        assertFalse(events[0].live)
        assertEquals(ProcessEventKind.SEARCH, events[1].kind)
        assertEquals(3, events[1].pages)
        assertEquals("a\nb\nc", events[1].content)
        assertEquals(ProcessEventKind.THINKING, events[2].kind)
        assertEquals("second", events[2].content)
        assertEquals(800L, events[2].millis)
    }

    @Test
    fun keepsElapsedWhenStoppedDuringThinking() {
        val tracker = ProcessTracker()
        tracker.onThinkingDelta("plan", now = 1_000)
        tracker.finishAll(now = 3_400)
        val event = tracker.snapshot().single()
        assertFalse(event.live)
        assertEquals(2_400L, event.millis)
    }

    @Test
    fun thinkingBlockOffKeepsOnlyTheLiveStatus() {
        assertEquals(ThinkingRowMode.Full, thinkingRowMode(ProcessEventKind.THINKING, live = true, blockEnabled = true))
        assertEquals(ThinkingRowMode.Full, thinkingRowMode(ProcessEventKind.THINKING, live = false, blockEnabled = true))
        assertEquals(ThinkingRowMode.StatusOnly, thinkingRowMode(ProcessEventKind.THINKING, live = true, blockEnabled = false))
        assertEquals(ThinkingRowMode.Hidden, thinkingRowMode(ProcessEventKind.THINKING, live = false, blockEnabled = false))
        // 搜索和工具行不受影响。
        assertEquals(ThinkingRowMode.Full, thinkingRowMode(ProcessEventKind.SEARCH, live = false, blockEnabled = false))
        assertEquals(ThinkingRowMode.Full, thinkingRowMode(ProcessEventKind.TOOL, live = true, blockEnabled = false))
    }
}
