package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun finishAllClosesThinkingAfterStreamEnds() {
        val tracker = ProcessTracker()
        tracker.onThinkingDelta("plan", now = 1_000)
        tracker.finishAll(now = 3_400)
        val event = tracker.snapshot().single()
        assertFalse(event.live)
        assertEquals(2_400L, event.millis)
        assertEquals(0, tracker.liveCount())
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
