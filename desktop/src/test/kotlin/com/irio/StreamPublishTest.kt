package com.irio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPublishTest {
    @Test
    fun firstBodyTokenPublishesImmediately() {
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 10,
                lastPublishAt = 0,
                deltaLength = 4,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 0,
                publishedThinking = false,
            ),
        )
    }

    @Test
    fun firstThinkingTokenPublishesImmediately() {
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 10,
                lastPublishAt = 0,
                deltaLength = 0,
                thinkingDeltaLength = 8,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 0,
                publishedThinking = false,
            ),
        )
    }

    @Test
    fun laterTokensWaitForInterval() {
        assertFalse(
            shouldPublishStreamUpdate(
                nowMs = 20,
                lastPublishAt = 10,
                deltaLength = 1,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
            ),
        )
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 10 + STREAM_PUBLISH_INTERVAL_MS,
                lastPublishAt = 10,
                deltaLength = 1,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
            ),
        )
    }

    @Test
    fun imeAnimationDefersLaterTokensButKeepsFirstAndDone() {
        assertFalse(
            shouldPublishStreamUpdate(
                nowMs = 50,
                lastPublishAt = 10,
                deltaLength = 1,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
                deferNonEssential = true,
            ),
        )
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 10 + STREAM_PUBLISH_INTERVAL_MS * 4,
                lastPublishAt = 10,
                deltaLength = 1,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
                deferNonEssential = true,
            ),
        )
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 50,
                lastPublishAt = 10,
                deltaLength = 0,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = true,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = true,
                deferNonEssential = true,
            ),
        )
    }

    @Test
    fun doneAndSearchPublishEvenInsideInterval() {
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 12,
                lastPublishAt = 10,
                deltaLength = 0,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = true,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = true,
            ),
        )
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 12,
                lastPublishAt = 10,
                deltaLength = 0,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = true,
                searchPagesDelta = 0,
                publishedBodyLength = 0,
                publishedThinking = true,
            ),
        )
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 12,
                lastPublishAt = 10,
                deltaLength = 0,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = true,
                hasUsage = true,
            ),
        )
    }
}
