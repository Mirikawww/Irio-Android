package com.irio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFollowTest {
    @Test
    fun streamingReplyFollowsWhenPinnedToBottom() {
        assertTrue(
            shouldSnapTranscriptToBottom(
                stickToBottom = true,
                userScrollInProgress = false,
                force = false,
            ),
        )
    }

    @Test
    fun readingHistoryDoesNotSnap() {
        assertFalse(
            shouldSnapTranscriptToBottom(
                stickToBottom = false,
                userScrollInProgress = false,
                force = false,
            ),
        )
    }

    @Test
    fun userDragDoesNotSnapUnlessForced() {
        assertFalse(
            shouldSnapTranscriptToBottom(
                stickToBottom = true,
                userScrollInProgress = true,
                force = false,
            ),
        )
        assertTrue(
            shouldSnapTranscriptToBottom(
                stickToBottom = false,
                userScrollInProgress = true,
                force = true,
            ),
        )
    }

    @Test
    fun tailKeyChangesWhenStreamingTextOrFinishChanges() {
        val streaming = ChatMessage(id = 7, text = "hello", fromUser = false, isStreaming = true)
        val grown = streaming.copy(text = "hello!")
        val finished = grown.copy(isStreaming = false, finishedAt = 1_700_000_000_000)
        assertTrue(transcriptTailFollowKey(streaming) != transcriptTailFollowKey(grown))
        assertTrue(transcriptTailFollowKey(grown) != transcriptTailFollowKey(finished))
        assertEquals(transcriptTailFollowKey(finished), transcriptTailFollowKey(finished))
    }

    @Test
    fun pinnedRequiresTheTailEdgeInsideTheViewport() {
        assertEquals(true, transcriptViewportPinned(0, hasLayout = false, -1, 0, 100, 8))
        assertNull(transcriptViewportPinned(2, hasLayout = false, -1, 0, 100, 8))
        assertEquals(
            false,
            transcriptViewportPinned(
                itemCount = 4,
                hasLayout = true,
                lastVisibleIndex = 2,
                lastEdge = 100,
                viewportEnd = 100,
                slackPx = 8,
            ),
        )
        assertEquals(
            false,
            transcriptViewportPinned(
                itemCount = 4,
                hasLayout = true,
                lastVisibleIndex = 3,
                lastEdge = 180,
                viewportEnd = 100,
                slackPx = 8,
            ),
        )
        assertEquals(
            true,
            transcriptViewportPinned(
                itemCount = 4,
                hasLayout = true,
                lastVisibleIndex = 3,
                lastEdge = 104,
                viewportEnd = 100,
                slackPx = 8,
            ),
        )
    }

    @Test
    fun catchUpDoesNotScrollWhenTheTailIsAlreadyAtTheBottom() {
        assertEquals(0, transcriptBottomCatchUpDelta(4, 3, 100, 100))
        assertEquals(0, transcriptBottomCatchUpDelta(4, 3, 80, 100))
        assertEquals(0, transcriptBottomCatchUpDelta(0, -1, 0, 100))
    }

    @Test
    fun catchUpScrollsOnlyTheOverflowPastTheBottom() {
        assertEquals(36, transcriptBottomCatchUpDelta(4, 3, 136, 100))
    }

    @Test
    fun catchUpJumpsOnlyWhenTheTailIsNotLaidOut() {
        assertNull(transcriptBottomCatchUpDelta(4, 2, 100, 100))
        assertNull(transcriptBottomCatchUpDelta(4, -1, 0, 100))
    }

    @Test
    fun openingAConversationJumpsTheWholeDistanceInOneStep() {
        assertEquals(1_800, tailFollowStep(1_800, instant = true))
        assertEquals(0, tailFollowStep(-5, instant = true))
        assertEquals(smoothFollowStep(1_800), tailFollowStep(1_800, instant = false))
        assertTrue(tailFollowStep(1_800, instant = false) < 1_800)
    }

    @Test
    fun smoothFollowStepsInsteadOfJumpingTheWholeDistance() {
        assertEquals(0, smoothFollowStep(0))
        assertEquals(0, smoothFollowStep(-8))
        assertEquals(1, smoothFollowStep(1))
        assertEquals(1, smoothFollowStep(3))
        val step = smoothFollowStep(400)
        assertTrue(step > 1)
        assertTrue(step < 400)
        assertTrue(smoothFollowStep(20_000) in 2..96)
        var remaining = 500
        var frames = 0
        while (remaining > 0 && frames < 120) {
            val next = smoothFollowStep(remaining)
            assertTrue(next in 1..remaining)
            remaining -= next
            frames++
        }
        assertEquals(0, remaining)
        assertTrue(frames > 1)
    }

    @Test
    fun sendScrollFinishesInFixedProgressRegardlessOfDistance() {
        assertEquals(0f, sendScrollEase(0f), 0.001f)
        assertEquals(1f, sendScrollEase(1f), 0.001f)
        assertTrue(sendScrollEase(0.5f) > 0.5f)
        assertEquals(0, fixedDurationScrollStep(20_000, 0f, 0f))
        assertEquals(20_000, fixedDurationScrollStep(20_000, 0.2f, 1f))
        val halfway = fixedDurationScrollStep(20_000, 0f, sendScrollEase(0.5f))
        assertTrue(halfway > 10_000)
        assertTrue(halfway < 20_000)
    }

    @Test
    fun sendScrollsOnlyAMeasuredTail() {
        assertNull(measuredTailOverflow(4, 2, 80, 180, 100, measurementReady = true))
        assertNull(measuredTailOverflow(4, 3, 800, 900, 100, measurementReady = false))
        assertNull(measuredTailOverflow(4, 3, 0, 100, 100, measurementReady = true))
        assertEquals(80, measuredTailOverflow(4, 3, 120, 180, 100, measurementReady = true))
        assertEquals(0, measuredTailOverflow(4, 3, 40, 90, 100, measurementReady = true))
    }

    @Test
    fun unmeasuredTailDoesNotScrollAStaleOrEstimatedHeight() {
        assertEquals(0, unmeasuredTailPrepareStep(4, 3, 800, 900, 100, lastMeasuredIsTrustworthy = false))
        assertEquals(1, unmeasuredTailPrepareStep(4, 2, 400, 100, 100, lastMeasuredIsTrustworthy = false))
        assertEquals(1, unmeasuredTailPrepareStep(4, 2, 40, 100, 100, lastMeasuredIsTrustworthy = true))
        val step = unmeasuredTailPrepareStep(4, 2, 400, 500, 100, lastMeasuredIsTrustworthy = true)
        assertTrue(step in 1..96)
    }
}
