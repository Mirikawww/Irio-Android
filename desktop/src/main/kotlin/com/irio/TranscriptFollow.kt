package com.irio

import androidx.compose.foundation.lazy.LazyListState

/**
 * 贴在底部时，模型回复长高也要跟着落到尾部。
 * 流式本身不是拒绝滚动的理由；人正在拖动、或已经离开底部时才不抢位置。
 * 新发送的用户消息和刚打开的对话强制贴底。
 */
internal fun shouldSnapTranscriptToBottom(
    stickToBottom: Boolean,
    userScrollInProgress: Boolean,
    force: Boolean,
): Boolean {
    if (force) return true
    if (userScrollInProgress) return false
    return stickToBottom
}

/** 尾部内容变了才需要重新贴底。长度不变的重组不要滚动。 */
internal fun transcriptTailFollowKey(message: ChatMessage): String {
    var process = 0
    for (event in message.process) {
        process += event.content.length
        process += event.pages
        process += event.toolName.length
        process += if (event.live) 1 else 0
        process += (event.millis % Int.MAX_VALUE).toInt()
    }
    return "${message.id}:${message.text.length}:$process:${message.attachments.size}:${message.isStreaming}:${message.isPending}:${message.finishedAt}"
}

/**
 * 最后一项的底边还在视口底边（含 slack）之内才算贴底。
 * 没布局过返回 null，调用方保持原来的贴底锁，不要当成用户离开了底部。
 */
internal fun transcriptViewportPinned(
    itemCount: Int,
    hasLayout: Boolean,
    lastVisibleIndex: Int,
    lastEdge: Int,
    viewportEnd: Int,
    slackPx: Int,
): Boolean? {
    if (itemCount == 0) return true
    if (!hasLayout) return null
    if (lastVisibleIndex < itemCount - 1) return false
    return lastEdge <= viewportEnd + slackPx
}

internal fun lazyTranscriptPinnedToBottom(state: LazyListState, slackPx: Int): Boolean? {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    return transcriptViewportPinned(
        itemCount = info.totalItemsCount,
        hasLayout = info.totalItemsCount == 0 || last != null,
        lastVisibleIndex = last?.index ?: -1,
        lastEdge = last?.let { it.offset + it.size } ?: 0,
        viewportEnd = info.viewportEndOffset,
        slackPx = slackPx,
    )
}

/** 程序滚动期间嵌套滚动回调不能清掉贴底锁。 */
internal class TranscriptScrollGate {
    var depth: Int = 0
}
