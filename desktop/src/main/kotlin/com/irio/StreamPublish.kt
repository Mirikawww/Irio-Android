package com.irio

internal const val STREAM_PUBLISH_INTERVAL_MS = 32L

internal fun shouldPublishStreamUpdate(
    nowMs: Long,
    lastPublishAt: Long,
    deltaLength: Int,
    thinkingDeltaLength: Int,
    hasAttachments: Boolean,
    done: Boolean,
    searchingChanged: Boolean,
    searchPagesDelta: Int,
    publishedBodyLength: Int,
    publishedThinking: Boolean,
    deferNonEssential: Boolean = false,
    toolChanged: Boolean = false,
    hasUsage: Boolean = false,
): Boolean {
    if (done || hasAttachments || searchingChanged || searchPagesDelta > 0 || toolChanged || hasUsage) return true
    if (deltaLength > 0 && publishedBodyLength == 0) return true
    if (thinkingDeltaLength > 0 && !publishedThinking) return true
    if (deltaLength == 0 && thinkingDeltaLength == 0) return false
    val interval = if (deferNonEssential) STREAM_PUBLISH_INTERVAL_MS * 4 else STREAM_PUBLISH_INTERVAL_MS
    return lastPublishAt == 0L || nowMs - lastPublishAt >= interval
}
