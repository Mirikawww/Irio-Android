package com.irio

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first

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
        process += event.bubbleColor.length
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

/**
 * 最后一项已经在布局里时，只补它超出视口底边的像素。
 * 正数才往下滚；0 表示已经贴住或还在底边之上（继续往下拉也算），不要再滚。
 * 已经贴底时 scrollToItem(Int.MAX_VALUE) / scrollToPosition 会把最后一项重新锚到视口起点，
 * 下一帧再拉回来，就是抽一下。最后一项还没量准时返回 null，不要改用滚动范围或平均行高去滚。
 */
internal fun transcriptBottomCatchUpDelta(
    itemCount: Int,
    lastVisibleIndex: Int,
    lastEdge: Int,
    viewportEnd: Int,
): Int? {
    if (itemCount <= 0) return 0
    if (lastVisibleIndex < itemCount - 1) return null
    val overflow = lastEdge - viewportEnd
    return if (overflow > 0) overflow else 0
}

/** 每帧追尾的像素。按剩余距离取比例，远的有上限，所以是滑过去而不是一帧跳完。 */
internal fun smoothFollowStep(
    remainingPx: Int,
    fraction: Float = 0.28f,
    maxStepPx: Int = 96,
): Int {
    if (remainingPx <= 0 || fraction <= 0f || maxStepPx <= 0) return 0
    val scaled = (remainingPx * fraction).toInt().coerceAtLeast(1)
    return scaled.coerceAtMost(maxStepPx).coerceAtMost(remainingPx)
}

/**
 * 尾部这一项已经用当前内容量完时，返回它超出视口底边的像素。
 * 0 表示已经贴住。还没量到（不在布局里、高度为 0、或还是回收来的旧高度）返回 null。
 * 不要改用滚动范围或平均行高：那两个数和新气泡的真高对不上，滚完会被拉回来。
 */
internal fun measuredTailOverflow(
    itemCount: Int,
    lastVisibleIndex: Int,
    lastSize: Int,
    lastEdge: Int,
    viewportEnd: Int,
    measurementReady: Boolean,
): Int? {
    if (itemCount <= 0) return 0
    if (!measurementReady || lastVisibleIndex != itemCount - 1 || lastSize <= 0) return null
    return (lastEdge - viewportEnd).coerceAtLeast(0)
}

/**
 * 尾部还没量到时，这一帧可以安全滚动的像素。
 * 尾部视图已经在了但高度还不可信：别动，等这一帧的内容量完。
 * 还没排到尾部时，只滚已经量准的最后一项超出底边的一段；它已经贴住就只探 1px，把下一项排进来。
 * 高度不可信时也只探 1px，不要按回收行的旧高度滚。
 */
internal fun unmeasuredTailPrepareStep(
    itemCount: Int,
    lastVisibleIndex: Int,
    lastSize: Int,
    lastEdge: Int,
    viewportEnd: Int,
    lastMeasuredIsTrustworthy: Boolean,
): Int {
    if (itemCount <= 0) return 0
    if (lastVisibleIndex >= itemCount - 1 && lastVisibleIndex >= 0) return 0
    if (lastVisibleIndex < 0 || lastSize <= 0 || !lastMeasuredIsTrustworthy) return 1
    val overflow = lastEdge - viewportEnd
    return if (overflow > 0) smoothFollowStep(overflow) else 1
}

/**
 * 贴底跟随。每帧只走一段剩余距离；人还在拖、[follow] 变 false 就停。
 * 程序滚动包在 [gate] 里，嵌套滚动不会把这次跟随当成用户离开了底部。
 * 已经贴住就挂起，等内容再变高，避免空转。
 */
internal suspend fun ScrollState.smoothFollowBottom(
    gate: TranscriptScrollGate,
    follow: () -> Boolean,
) {
    var idleConfirm = false
    var stuck = false
    while (follow()) {
        val remaining = maxValue - value
        if (remaining <= 0) {
            if (idleConfirm) {
                snapshotFlow { maxValue }.first { !follow() || maxValue - value > 0 }
                if (!follow()) return
                idleConfirm = false
                continue
            }
            idleConfirm = true
            withFrameNanos { }
            continue
        }
        idleConfirm = false
        val step = smoothFollowStep(remaining)
        var consumed = 0f
        gate.depth++
        try {
            scroll { consumed = scrollBy(step.toFloat()) }
        } finally {
            gate.depth--
        }
        if (consumed == 0f) {
            if (stuck) return
            stuck = true
            withFrameNanos { }
            continue
        }
        stuck = false
        withFrameNanos { }
    }
}

/** 发送消息时滑到底的时长。按时间走，不按距离，离得远也不会滑很久。 */
internal const val SendScrollDurationMillis = 300

/** 打开会话：列表隐藏到落底后淡入的淡入时长，以及对齐迟迟不收尾时的兜底显示时限。 */
internal const val OpenRevealFadeMillis = 120L
internal const val OpenRevealTimeoutMillis = 800L

/** 打开会话后持续把尾部贴底的时间窗。覆盖 Markdown/TeX/图片的异步渲染。 */
internal const val OpenPinWindowMillis = 1_500L

/** 追尾一步的像素。打开会话时一次补齐全部超出量，其余情况按比例逐帧滑。 */
internal fun tailFollowStep(remainingPx: Int, instant: Boolean): Int =
    if (instant) remainingPx.coerceAtLeast(0) else smoothFollowStep(remainingPx)

/** 先快后慢，固定时长结束时进度为 1。 */
internal fun sendScrollEase(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    val inv = 1f - x
    return 1f - inv * inv * inv
}

/**
 * 这一帧该消化的剩余距离比例。
 * 进度到 1 时吃掉全部剩余，所以总时长固定，不随距离变长。
 */
internal fun fixedDurationSlice(previousProgress: Float, progress: Float): Float {
    if (progress >= 1f) return 1f
    val span = 1f - previousProgress
    if (span <= 0.0001f) return 1f
    return ((progress - previousProgress) / span).coerceIn(0f, 1f)
}

internal fun fixedDurationScrollStep(remainingPx: Int, previousProgress: Float, progress: Float): Int {
    if (remainingPx <= 0) return 0
    val slice = fixedDurationSlice(previousProgress, progress)
    if (slice <= 0f) return 0
    return (remainingPx * slice).toInt().coerceAtLeast(1).coerceAtMost(remainingPx)
}

/** 程序滚动期间嵌套滚动回调不能清掉贴底锁。 */
internal class TranscriptScrollGate {
    var depth: Int = 0
}
