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
    fun imeAnimationUsesLongerInterval() {
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
    }

    @Test
    fun donePublishesEvenInsideInterval() {
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
    }

    @Test
    fun userScrollingUses120msInterval() {
        // 用户拖动/惯性滚动时间隔放宽到 120ms，避免和滚动抢帧。
        assertFalse(
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
                userActivelyScrolling = true,
            ),
        )
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 10 + STREAM_PUBLISH_INTERVAL_MS * 5 / 2,
                lastPublishAt = 10,
                deltaLength = 1,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
                userActivelyScrolling = true,
            ),
        )
    }

    @Test
    fun userScrollingDoesNotDelayEssentialUpdates() {
        // 滚动中 done / 附件 / 搜索状态变化仍立即发布。
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 11,
                lastPublishAt = 10,
                deltaLength = 0,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = true,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
                userActivelyScrolling = true,
            ),
        )
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 11,
                lastPublishAt = 10,
                deltaLength = 0,
                thinkingDeltaLength = 0,
                hasAttachments = true,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
                userActivelyScrolling = true,
            ),
        )
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 11,
                lastPublishAt = 10,
                deltaLength = 0,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
                hasUsage = true,
            ),
        )
    }

    @Test
    fun toolOutputPublishesImmediately() {
        assertTrue(
            shouldPublishStreamUpdate(
                nowMs = 11,
                lastPublishAt = 10,
                deltaLength = 0,
                thinkingDeltaLength = 0,
                hasAttachments = false,
                done = false,
                searchingChanged = false,
                searchPagesDelta = 0,
                publishedBodyLength = 4,
                publishedThinking = false,
                toolOutputChanged = true,
            ),
        )
    }
}
