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

    @Test
    fun toolBetweenSpeechesStaysAtItsAnchor() {
        val tracker = ProcessTracker()
        val first = "先说一句"
        tracker.onOutputStarted(textLength = first.length)
        tracker.onToolStarted(TimeTool.NAME)
        tracker.onToolFinished("12:00")
        val continued = appendAssistantRoundBreak(first) + "再说一句"
        tracker.onOutputStarted(textLength = continued.length)
        val tool = tracker.snapshot().single()
        assertEquals(first.length, tool.textOffset)
        assertTrue(tool.belowText)
        assertEquals(
            listOf("text:$first", "TOOL", "text:\n\n再说一句"),
            transcriptLabels(interleaveTranscript(continued, tracker.snapshot())),
        )
        assertEquals(continued, settledAssistantText(continued, "再说一句"))
    }

    @Test
    fun legacyBelowTextWithoutOffsetStaysAfterTheWholeReply() {
        val event = ProcessEvent(
            id = 1,
            kind = ProcessEventKind.TOOL,
            belowText = true,
            toolName = TimeTool.NAME,
        )
        assertEquals(
            listOf("text:全部正文", "TOOL"),
            transcriptLabels(interleaveTranscript("全部正文", listOf(event))),
        )
    }

    @Test
    fun appendAssistantRoundBreakDoesNotStackBlankLines() {
        assertEquals("先说\n\n", appendAssistantRoundBreak("先说"))
        assertEquals("先说\n\n", appendAssistantRoundBreak("先说\n"))
        assertEquals("先说\n\n", appendAssistantRoundBreak("先说\n\n"))
    }

    @Test
    fun successfulImageGenerationDropsTheStatusRow() {
        val tracker = ProcessTracker()
        tracker.onToolStarted(ImageGenTool.NAME)
        tracker.dropLiveTool()
        assertTrue(tracker.snapshot().isEmpty())
    }

    private fun transcriptLabels(pieces: List<TranscriptPiece>): List<String> = pieces.map { piece ->
        when (piece) {
            is TranscriptPiece.Text -> "text:${piece.text}"
            is TranscriptPiece.Event -> piece.event.kind.name
        }
    }
}
