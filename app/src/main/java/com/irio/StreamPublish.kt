package com.irio

internal const val STREAM_PUBLISH_INTERVAL_MS = 48L

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
    userActivelyScrolling: Boolean = false,
    hasUsage: Boolean = false,
    toolOutputChanged: Boolean = false,
): Boolean {
    if (done || hasAttachments || searchingChanged || searchPagesDelta > 0 || toolChanged || hasUsage || toolOutputChanged) return true
    if (deltaLength > 0 && publishedBodyLength == 0) return true
    if (thinkingDeltaLength > 0 && !publishedThinking) return true
    if (deltaLength == 0 && thinkingDeltaLength == 0) return false
    // 用户正在手指拖动/惯性滚动浏览时把重组隔到 120ms 一次：避免和滚动抢帧。
    val interval = when {
        userActivelyScrolling -> STREAM_PUBLISH_INTERVAL_MS * 5L / 2L
        deferNonEssential -> STREAM_PUBLISH_INTERVAL_MS * 4
        else -> STREAM_PUBLISH_INTERVAL_MS
    }
    return lastPublishAt == 0L || nowMs - lastPublishAt >= interval
}
