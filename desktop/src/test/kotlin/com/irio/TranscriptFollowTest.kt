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
}
