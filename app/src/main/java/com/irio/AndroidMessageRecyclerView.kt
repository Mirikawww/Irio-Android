package com.irio

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope



/**
 * Callbacks owned by MainScreen. The adapter only renders a message and never owns chat state.
 */
internal data class AndroidMessageCallbacks(
    val markdownScope: CoroutineScope,
    val onOpenProcess: (Long, ProcessEvent) -> Unit,
    val onOpenAttachment: (ChatAttachment) -> Unit,
    val onCopy: (ChatMessage) -> Unit,
    val onRetry: (ChatMessage) -> Unit,
    val canRetry: (ChatMessage) -> Boolean,
    val onPrevVersion: (ChatMessage) -> (() -> Unit)?,
    val onNextVersion: (ChatMessage) -> (() -> Unit)?,
    val isEditing: (ChatMessage) -> Boolean,
    val onEdit: (ChatMessage) -> (() -> Unit)?,
    val onPrevEditVersion: (ChatMessage) -> (() -> Unit)?,
    val onNextEditVersion: (ChatMessage) -> (() -> Unit)?,
    val versionInfo: (ChatMessage) -> VersionInfo,
    val editVersionInfo: (ChatMessage) -> VersionInfo,
    val newUserMessageId: Long?,
    val outsideContext: (ChatMessage) -> Boolean = { false },
)

internal data class VersionInfo(val index: Int, val count: Int)

/**
 * A small bridge around RecyclerView. The screen remains Compose, but the transcript no longer
 * uses LazyColumn for Android. Each recycled row hosts the existing Compose MessageRow.
 */
@Composable
internal fun AndroidMessageList(
    messages: List<ChatMessage>,
    controller: MessageRecyclerController,
    modifier: Modifier = Modifier,
    contentPaddingTop: Int,
    contentPaddingBottom: Int,
    callbacks: AndroidMessageCallbacks,
    onVisibleRangeChanged: (first: Int, last: Int) -> Unit,
    onScrollStateChanged: (scrollState: Int) -> Unit,
    onUserScrollSample: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MessageRecyclerView(context).also { view ->
                controller.attach(view)
                view.onVisibleRangeChanged = onVisibleRangeChanged
                view.onScrollStateChanged = onScrollStateChanged
                view.onUserScrollSample = onUserScrollSample
                view.setTranscriptPadding(contentPaddingTop, contentPaddingBottom)
                view.updateCallbacks(callbacks)
                view.updateMessages(messages) { controller.flushScrollToBottom() }
                controller.flushScrollToBottom()
                view.notifyVisibleRange()
            }
        },
        update = { view ->
            controller.attach(view)
            view.onVisibleRangeChanged = onVisibleRangeChanged
            view.onScrollStateChanged = onScrollStateChanged
            view.onUserScrollSample = onUserScrollSample
            view.setTranscriptPadding(contentPaddingTop, contentPaddingBottom)
            view.updateCallbacks(callbacks)
            view.updateMessages(messages) { controller.flushScrollToBottom() }
            controller.flushScrollToBottom()
            view.notifyVisibleRange()
        },
    )
}

internal class MessageRecyclerController {
    private var recyclerView: MessageRecyclerView? = null
    private var scrollRequested = false
        set(value) {
            field = value
            if (!value) {
                recyclerView?.tailPrefetch = false
                finishOpen()
            }
        }
    // 打开会话：列表先隐藏，直接跳到尾部再淡入，不让用户看到从顶上滚下来。
    private var instantOpen = false
    private var openJumpPending = false
    private var openHidden = false
    private var alignScheduled = false
    private var chasing = false
    private var chasePosted = false
    private var gliding = false
    private var glidePosted = false
    private var glideClockStarted = false
    private var glideStartUptime = 0L
    private var glidePreviousProgress = 0f
    private var glideDeferred = false
    private var glideUnmeasuredWaits = 0
    private var unlaidWaits = 0
    private var idleConfirms = 0
    private var programmaticPosts = 0

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        onViewLayoutPass()
    }

    // 打开会话后的一小段时间内，尾部因异步渲染（Markdown/TeX/图片）长高时继续贴底。
    private var openPinUntil = 0L
    private var pinPosted = false

    fun attach(view: MessageRecyclerView) {
        if (recyclerView === view) return
        recyclerView?.removeOnLayoutChangeListener(layoutListener)
        recyclerView = view
        view.addOnLayoutChangeListener(layoutListener)
    }

    private fun onViewLayoutPass() {
        if (openPinUntil == 0L || pinPosted) return
        val view = recyclerView ?: return
        pinPosted = true
        view.postOnAnimation {
            pinPosted = false
            pinOpenTail()
        }
    }

    /** 窗口内：尾部不在布局里就重新跳过去；在布局里但超出底边就立刻补齐。用户一碰列表就撤销。 */
    private fun pinOpenTail() {
        val view = recyclerView ?: return
        if (openPinUntil == 0L) return
        if (SystemClock.uptimeMillis() > openPinUntil ||
            view.scrollState != RecyclerView.SCROLL_STATE_IDLE
        ) {
            openPinUntil = 0L
            return
        }
        if (gliding || view.isMessageUpdatePending()) return
        val manager = view.layoutManager as? LinearLayoutManager ?: return
        val count = view.itemCountValue()
        if (count == 0) return
        val tail = manager.findViewByPosition(count - 1)
        if (tail == null) {
            programmaticPosts++
            try {
                manager.scrollToPositionWithOffset(count - 1, 0)
            } finally {
                programmaticPosts--
            }
            view.requestLayout()
            return
        }
        val overflow = tail.bottom - (view.height - view.paddingBottom)
        if (overflow > 0) scrollByProgrammatic(view, overflow)
    }

    fun requestScrollToBottom() {
        scrollRequested = true
        recyclerView?.tailPrefetch = true
        flushScrollToBottom()
    }

    /** 切换/恢复会话：不做滚动动画，直接落在底部。量准之前列表保持透明，落稳后 alpha 淡入。 */
    fun openAtBottom() {
        val view = recyclerView ?: return
        scrollRequested = true
        instantOpen = true
        openJumpPending = true
        chasing = false
        gliding = false
        idleConfirms = 0
        unlaidWaits = 0
        view.tailPrefetch = true
        programmaticPosts++
        try {
            view.stopScroll()
        } finally {
            programmaticPosts--
        }
        view.animate().cancel()
        view.alpha = 0f
        openHidden = true
        openPinUntil = SystemClock.uptimeMillis() + OpenPinWindowMillis
        // 兜底：无论对齐是否收尾，都不让列表一直透明。
        view.postDelayed({ if (recyclerView === view) finishOpen() }, OpenRevealTimeoutMillis)
        flushScrollToBottom()
    }

    private fun finishOpen() {
        instantOpen = false
        openJumpPending = false
        if (!openHidden) return
        openHidden = false
        val view = recyclerView ?: return
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(OpenRevealFadeMillis).start()
    }

    /** 直接把最后一项排进布局（贴视口顶），之后由追尾一次性补齐超出底边的像素。 */
    private fun applyOpenJump(view: MessageRecyclerView): Boolean {
        if (!openJumpPending) return false
        val count = view.itemCountValue()
        if (count == 0) return false
        openJumpPending = false
        val manager = view.layoutManager as? LinearLayoutManager ?: return false
        programmaticPosts++
        try {
            manager.scrollToPositionWithOffset(count - 1, 0)
        } finally {
            programmaticPosts--
        }
        view.requestLayout()
        return true
    }

    /** 发送消息：固定时长滑到新尾部，并让之后的流式输出继续贴底。 */
    fun revealTail() {
        val view = recyclerView ?: return
        openPinUntil = 0L
        scrollRequested = true
        chasing = false
        idleConfirms = 0
        gliding = true
        glideClockStarted = false
        glidePreviousProgress = 0f
        glideDeferred = false
        glideUnmeasuredWaits = 0
        unlaidWaits = 0
        view.tailPrefetch = true
        programmaticPosts++
        try {
            view.stopScroll()
        } finally {
            programmaticPosts--
        }
        if (!glidePosted) postGlide()
    }

    fun isProgrammaticScroll(): Boolean = programmaticPosts > 0

    fun flushScrollToBottom() {
        if (gliding || chasing || chasePosted || alignScheduled) return
        scheduleAlign(attempt = 0)
    }

    /**
     * 最后一项已经在屏幕上时，每帧只滚一段超出底边的距离。
     * 一次滚完会跳。高度可能晚一帧才跟上，所以贴住时再看一次；第二次仍没超出就当已经贴底。
     */
    private fun scheduleAlign(attempt: Int) {
        if (!scrollRequested || alignScheduled) return
        val view = recyclerView ?: return
        if (view.itemCountValue() == 0) {
            scrollRequested = false
            return
        }
        if (view.isMessageUpdatePending()) {
            alignScheduled = true
            view.post {
                alignScheduled = false
                scheduleAlign(attempt)
            }
            return
        }
        alignScheduled = true
        view.post {
            alignScheduled = false
            if (!scrollRequested || gliding) return@post
            val current = recyclerView ?: return@post
            if (current.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                // 手指拖动或惯性还在走就丢掉这次。松手后如果仍贴底，effect 会再请求。
                // 逐帧 scrollBy 不会离开 IDLE，所以不会把自己停掉。
                scrollRequested = false
                chasing = false
                idleConfirms = 0
                return@post
            }
            if (applyOpenJump(current)) {
                scheduleAlign(attempt = 1)
                return@post
            }
            val count = current.itemCountValue()
            if (count == 0) {
                scrollRequested = false
                return@post
            }
            val child = (current.layoutManager as? LinearLayoutManager)
                ?.findViewByPosition(count - 1)
            // 这一帖在本帧 layout 之前跑，底边还是旧高度。先等布局，再决定滚不滚。
            if (attempt == 0) {
                current.requestLayout()
                scheduleAlign(attempt = 1)
                return@post
            }
            if (child == null) {
                idleConfirms = 0
                bringUnlaidTail(current)
                return@post
            }
            chasing = true
            stepChase(current)
        }
    }

    /**
     * 最后一项已经在屏幕上时，每帧只滚一段超出底边的距离。
     * 一次 scrollBy 完全部会跳；smoothScrollBy 又会把滚动状态变成 SETTLING，
     * 跟随会被当成手指在滚。这里用 scrollBy 逐帧追，滚动状态保持 IDLE。
     */
    private fun stepChase(current: MessageRecyclerView) {
        if (gliding) {
            chasing = false
            return
        }
        if (!scrollRequested) {
            chasing = false
            return
        }
        if (current.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            scrollRequested = false
            chasing = false
            idleConfirms = 0
            return
        }
        if (current.isMessageUpdatePending()) {
            postChase()
            return
        }
        if (applyOpenJump(current)) {
            postChase()
            return
        }
        val count = current.itemCountValue()
        if (count == 0) {
            scrollRequested = false
            chasing = false
            return
        }
        val child = (current.layoutManager as? LinearLayoutManager)?.findViewByPosition(count - 1)
        val sample = current.tailSample()
        if (child == null || !sample.ready) {
            idleConfirms = 0
            bringUnlaidTail(current)
            return
        }
        val catchUp = transcriptBottomCatchUpDelta(
            itemCount = count,
            lastVisibleIndex = count - 1,
            lastEdge = child.bottom,
            viewportEnd = current.height - current.paddingBottom,
        )
        if (catchUp == null || catchUp <= 0) {
            if (idleConfirms < 1) {
                idleConfirms++
                postChase()
                return
            }
            idleConfirms = 0
            scrollRequested = false
            chasing = false
            return
        }
        idleConfirms = 0
        val step = tailFollowStep(catchUp, instantOpen)
        val beforeBottom = child.bottom
        programmaticPosts++
        try {
            current.scrollBy(0, step)
        } finally {
            programmaticPosts--
        }
        val after = (current.layoutManager as? LinearLayoutManager)?.findViewByPosition(count - 1)
        if (after == null || after.bottom == beforeBottom) {
            scrollRequested = false
            chasing = false
            return
        }
        postChase()
    }

    private fun postChase() {
        if (chasePosted) return
        val view = recyclerView ?: run {
            chasing = false
            return
        }
        chasePosted = true
        view.postOnAnimation {
            chasePosted = false
            val current = recyclerView
            if (!scrollRequested || current == null) {
                chasing = false
                return@postOnAnimation
            }
            stepChase(current)
        }
    }

    private fun postGlide() {
        if (glidePosted) return
        val view = recyclerView ?: run {
            gliding = false
            return
        }
        glidePosted = true
        view.postOnAnimation {
            glidePosted = false
            val current = recyclerView
            if (!gliding || !scrollRequested || current == null) {
                gliding = false
                return@postOnAnimation
            }
            stepGlide(current)
        }
    }

    /**
     * 固定时长滑到已经量准的尾部。高度还没量准时不按滚动范围估，
     * 免得滚过了再被拉回来。量准后先空一帧，再按真实超出像素走。
     */
    private fun stepGlide(current: MessageRecyclerView) {
        if (!gliding || !scrollRequested) {
            gliding = false
            current.tailPrefetch = false
            return
        }
        if (current.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            gliding = false
            scrollRequested = false
            return
        }
        if (current.isMessageUpdatePending()) {
            postGlide()
            return
        }
        current.tailPrefetch = true
        val sample = current.tailSample()
        val overflow = measuredTailOverflow(
            itemCount = sample.itemCount,
            lastVisibleIndex = sample.lastIndex,
            lastSize = sample.lastSize,
            lastEdge = sample.lastEdge,
            viewportEnd = sample.viewportEnd,
            measurementReady = sample.ready,
        )
        if (!glideClockStarted) {
            if (overflow == null) {
                if (glideUnmeasuredWaits++ >= 24) {
                    gliding = false
                    scrollRequested = false
                    return
                }
                scrollByProgrammatic(
                    current,
                    unmeasuredTailPrepareStep(
                        itemCount = sample.itemCount,
                        lastVisibleIndex = sample.lastIndex,
                        lastSize = sample.lastSize,
                        lastEdge = sample.lastEdge,
                        viewportEnd = sample.viewportEnd,
                        lastMeasuredIsTrustworthy = sample.ready,
                    ),
                )
                current.requestLayout()
                postGlide()
                return
            }
            if (!glideDeferred) {
                glideDeferred = true
                postGlide()
                return
            }
            if (overflow == 0) {
                gliding = false
                scrollRequested = false
                return
            }
            glideClockStarted = true
            glideStartUptime = SystemClock.uptimeMillis()
            glidePreviousProgress = 0f
            postGlide()
            return
        }
        val elapsed = SystemClock.uptimeMillis() - glideStartUptime
        val t = (elapsed.toFloat() / SendScrollDurationMillis).coerceIn(0f, 1f)
        val progress = sendScrollEase(t)
        val step = fixedDurationScrollStep(overflow ?: 0, glidePreviousProgress, progress)
        glidePreviousProgress = progress
        scrollByProgrammatic(current, step)
        if (t < 1f) {
            postGlide()
            return
        }
        val restSample = current.tailSample()
        val rest = measuredTailOverflow(
            itemCount = restSample.itemCount,
            lastVisibleIndex = restSample.lastIndex,
            lastSize = restSample.lastSize,
            lastEdge = restSample.lastEdge,
            viewportEnd = restSample.viewportEnd,
            measurementReady = restSample.ready,
        ) ?: 0
        scrollByProgrammatic(current, rest)
        gliding = false
        scrollRequested = false
    }

    private fun scrollByProgrammatic(view: MessageRecyclerView, dy: Int) {
        if (dy <= 0) return
        programmaticPosts++
        try {
            view.scrollBy(0, dy)
        } finally {
            programmaticPosts--
        }
    }

    /**
     * 新尾部还没量准时不要按滚动范围滚。视图还不可信就等着；
     * 已经量准的只滚真实超出像素。scrollToPosition 会把最后一项锚到视口顶上。
     */
    private fun bringUnlaidTail(view: MessageRecyclerView) {
        if (gliding) return
        view.tailPrefetch = true
        val sample = view.tailSample()
        val overflow = measuredTailOverflow(
            itemCount = sample.itemCount,
            lastVisibleIndex = sample.lastIndex,
            lastSize = sample.lastSize,
            lastEdge = sample.lastEdge,
            viewportEnd = sample.viewportEnd,
            measurementReady = sample.ready,
        )
        if (overflow != null) {
            if (overflow <= 0) {
                scrollRequested = false
                chasing = false
                return
            }
            unlaidWaits = 0
            scrollByProgrammatic(view, tailFollowStep(overflow, instantOpen))
            scrollRequested = true
            chasing = true
            postChase()
            return
        }
        if (unlaidWaits++ >= 24) {
            unlaidWaits = 0
            scrollRequested = false
            chasing = false
            return
        }
        scrollByProgrammatic(
            view,
            unmeasuredTailPrepareStep(
                itemCount = sample.itemCount,
                lastVisibleIndex = sample.lastIndex,
                lastSize = sample.lastSize,
                lastEdge = sample.lastEdge,
                viewportEnd = sample.viewportEnd,
                lastMeasuredIsTrustworthy = sample.ready,
            ),
        )
        view.requestLayout()
        view.postOnAnimation {
            if (recyclerView !== view || gliding) return@postOnAnimation
            bringUnlaidTail(view)
        }
    }

    fun isScrollInProgress(): Boolean =
        recyclerView?.scrollState?.let { it != RecyclerView.SCROLL_STATE_IDLE } ?: false

    /** 最后一条的底边还在列表视口里才算贴底。还没布局返回 null。 */
    fun isPinnedToBottom(slackPx: Int): Boolean? {
        val view = recyclerView ?: return null
        val count = view.adapter?.itemCount ?: return null
        if (count == 0) return true
        if (view.childCount == 0) return null
        val manager = view.layoutManager as? LinearLayoutManager ?: return null
        val lastView = manager.findViewByPosition(count - 1)
        return transcriptViewportPinned(
            itemCount = count,
            hasLayout = true,
            lastVisibleIndex = if (lastView != null) count - 1 else -1,
            lastEdge = lastView?.bottom ?: 0,
            viewportEnd = view.height - view.paddingBottom,
            slackPx = slackPx,
        )
    }
}

internal class MessageRecyclerView(context: Context) : RecyclerView(context) {
    private val messageAdapter = AndroidMessageAdapter()
    var onVisibleRangeChanged: ((first: Int, last: Int) -> Unit)? = null
    var onScrollStateChanged: ((scrollState: Int) -> Unit)? = null
    var onUserScrollSample: (() -> Unit)? = null
    var tailPrefetch: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    private var lastNotifiedFirst = RecyclerView.NO_POSITION
    private var lastNotifiedLast = RecyclerView.NO_POSITION

    init {
        layoutManager = object : LinearLayoutManager(context) {
            override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
                super.calculateExtraLayoutSpace(state, extraLayoutSpace)
                if (tailPrefetch) {
                    val extra = this@MessageRecyclerView.height
                    if (extra > 0) extraLayoutSpace[1] = maxOf(extraLayoutSpace[1], extra)
                }
            }
        }.apply {
            stackFromEnd = false
        }
        adapter = messageAdapter
        itemAnimator = null
        setItemViewCacheSize(8)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addItemDecoration(VerticalMessageSpacing(context, 12))
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                onScrollStateChanged?.invoke(newState)
                notifyVisibleRange()
                // 手指拖动或惯性结束时采一次。程序 scrollBy 不改变 scroll state。
                if (newState == RecyclerView.SCROLL_STATE_IDLE) onUserScrollSample?.invoke()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                notifyVisibleRange()
                if (scrollState != RecyclerView.SCROLL_STATE_IDLE) onUserScrollSample?.invoke()
            }
        })
    }

    fun updateCallbacks(next: AndroidMessageCallbacks) {
        messageAdapter.updateCallbacks(next)
    }

    fun updateMessages(next: List<ChatMessage>, onCommitted: (() -> Unit)? = null) {
        val current = messageAdapter.currentList
        var structural = current.size != next.size
        if (!structural) {
            for (index in next.indices) {
                if (current[index].id != next[index].id) {
                    structural = true
                    break
                }
            }
        }
        messageAdapter.updateMessages(next) {
            // 条目增删会改变「谁是第一条」。装饰间距按位置缓存，不标脏的话
            // 新气泡会沿用上一条还是末尾时的 0 间距，直到整表重绑。
            if (structural) invalidateItemDecorations()
            onCommitted?.invoke()
        }
    }

    fun isMessageUpdatePending(): Boolean = messageAdapter.isUpdatePending()

    fun tailSample(): TailSample {
        val count = itemCountValue()
        val viewportEnd = height - paddingBottom
        if (count <= 0) return TailSample(0, -1, 0, 0, viewportEnd, true)
        val manager = layoutManager as? LinearLayoutManager
            ?: return TailSample(count, -1, 0, 0, viewportEnd, false)
        val tail = manager.findViewByPosition(count - 1)
        if (tail == null) {
            val position = manager.findLastVisibleItemPosition()
            if (position == RecyclerView.NO_POSITION) {
                return TailSample(count, -1, 0, 0, viewportEnd, false)
            }
            val child = manager.findViewByPosition(position)
                ?: return TailSample(count, -1, 0, 0, viewportEnd, false)
            return TailSample(
                count,
                position,
                child.height,
                child.bottom,
                viewportEnd,
                childIsReady(child, position),
            )
        }
        val ready = childIsReady(tail, count - 1) && neighborReady(count - 2)
        return TailSample(count, count - 1, tail.height, tail.bottom, viewportEnd, ready)
    }

    private fun neighborReady(position: Int): Boolean {
        if (position < 0) return true
        val child = (layoutManager as? LinearLayoutManager)?.findViewByPosition(position) ?: return true
        return childIsReady(child, position)
    }

    private fun childIsReady(child: View, position: Int): Boolean {
        val holder = getChildViewHolder(child) as? AndroidMessageViewHolder ?: return false
        val id = adapter?.getItemId(position) ?: return false
        return holder.readyMessageId == id && child.height > 0
    }

    fun itemCountValue(): Int = messageAdapter.itemCount

    fun setTranscriptPadding(top: Int, bottom: Int) {
        if (paddingTop != top || paddingBottom != bottom) {
            setPadding(paddingLeft, top, paddingRight, bottom)
            post { notifyVisibleRange() }
        }
    }

    fun notifyVisibleRange() {
        val manager = layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            if (first == lastNotifiedFirst && last == lastNotifiedLast) return
            lastNotifiedFirst = first
            lastNotifiedLast = last
            onVisibleRangeChanged?.invoke(first, last)
        }
    }
}

internal data class TailSample(
    val itemCount: Int,
    val lastIndex: Int,
    val lastSize: Int,
    val lastEdge: Int,
    val viewportEnd: Int,
    val ready: Boolean,
)

private class VerticalMessageSpacing(
    context: Context,
    spacingDp: Int,
) : RecyclerView.ItemDecoration() {
    private val spacing = (spacingDp * context.resources.displayMetrics.density).toInt()

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        // 间距放在这一条的顶上，而不是上一条的底下。末尾项的底边距会被
        // RecyclerView 缓存；后面再插入时上一条不会重测，新气泡就会贴上去。
        val adapterPosition = parent.getChildAdapterPosition(view)
        val position = if (adapterPosition != RecyclerView.NO_POSITION) {
            adapterPosition
        } else {
            parent.getChildLayoutPosition(view)
        }
        outRect.top = if (position > 0) spacing else 0
    }
}

private class AndroidMessageAdapter : ListAdapter<ChatMessage, AndroidMessageViewHolder>(DIFF) {
    private val callbacks = mutableStateOf<AndroidMessageCallbacks?>(null)
    private var renderedMessages = emptyList<ChatMessage>()
    private var updatePending = false
    private var submitGeneration = 0

    init {
        setHasStableIds(true)
    }

    fun updateCallbacks(next: AndroidMessageCallbacks) {
        callbacks.value = next
    }

    fun updateMessages(next: List<ChatMessage>, onCommitted: (() -> Unit)?) {
        // Structural changes must be compared with the list RecyclerView has
        // committed. For an unchanged structure, renderedMessages is the
        // latest snapshot (ListAdapter's currentList intentionally stays at
        // the last submit), so payloads can update streaming/final text.
        val sameStructure = currentList.size == next.size &&
            currentList.indices.all { currentList[it].id == next[it].id }
        if (sameStructure) {
            val changed = next.indices.filter { renderedMessages.getOrNull(it) != next[it] }
            renderedMessages = next
            changed.forEach { notifyItemChanged(it, STREAMING_PAYLOAD) }
            onCommitted?.invoke()
        } else {
            renderedMessages = next
            submitGeneration += 1
            val generation = submitGeneration
            updatePending = true
            submitList(next) {
                if (generation == submitGeneration) {
                    updatePending = false
                    onCommitted?.invoke()
                }
            }
        }
    }

    fun isUpdatePending(): Boolean = updatePending

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AndroidMessageViewHolder =
        AndroidMessageViewHolder(ComposeView(parent.context), callbacks)

    override fun onBindViewHolder(holder: AndroidMessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: AndroidMessageViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        // A payload update does not change ListAdapter.currentList. Bind the
        // adapter's latest rendered snapshot, otherwise a completed reply can
        // remain visually stuck at the old "请求中" placeholder until the
        // conversation is reopened.
        holder.bind(renderedMessages.getOrNull(position) ?: getItem(position))
    }

    override fun onViewRecycled(holder: AndroidMessageViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    private companion object {
        val STREAMING_PAYLOAD = Any()
        val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem == newItem
        }
    }
}

private class AndroidMessageViewHolder(
    private val composeView: ComposeView,
    private val callbacks: androidx.compose.runtime.MutableState<AndroidMessageCallbacks?>,
) : RecyclerView.ViewHolder(composeView) {
    private var message by mutableStateOf<ChatMessage?>(null)
    var readyMessageId: Long = Long.MIN_VALUE
        private set

    init {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val currentMessage = message
            val currentCallbacks = callbacks.value
            if (currentMessage != null && currentCallbacks != null) {
                Box(
                    Modifier.onSizeChanged { size ->
                        val shown = message
                        if (size.height > 0 && shown?.id == currentMessage.id) {
                            readyMessageId = shown.id
                        }
                    },
                ) {
                    AndroidMessageRow(currentMessage, currentCallbacks)
                }
            }
        }
    }

    fun bind(nextMessage: ChatMessage) {
        if (message?.id != nextMessage.id) readyMessageId = Long.MIN_VALUE
        message = nextMessage
    }

    fun clear() {
        message = null
        readyMessageId = Long.MIN_VALUE
    }
}

@Composable
private fun AndroidMessageRow(
    message: ChatMessage,
    callbacks: AndroidMessageCallbacks,
) {
    val version = callbacks.versionInfo(message)
    val editVersion = callbacks.editVersionInfo(message)
    key(message.id) {
        MessageRow(
            chatMessage = message,
            newUserMessageId = callbacks.newUserMessageId,


            markdownScope = callbacks.markdownScope,
            onOpenProcess = { event -> callbacks.onOpenProcess(message.id, event) },
            onOpenAttachment = callbacks.onOpenAttachment,
            onCopy = { callbacks.onCopy(message) },
            onRetry = { callbacks.onRetry(message) },
            canRetry = callbacks.canRetry(message),
            versionIndex = version.index,
            versionCount = version.count,
            onPrevVersion = callbacks.onPrevVersion(message),
            onNextVersion = callbacks.onNextVersion(message),
            isEditing = callbacks.isEditing(message),
            onEdit = callbacks.onEdit(message),
            editVersionIndex = editVersion.index,
            editVersionCount = editVersion.count,
            onPrevEditVersion = callbacks.onPrevEditVersion(message),
            onNextEditVersion = callbacks.onNextEditVersion(message),
            outsideContext = callbacks.outsideContext(message),
        )
    }
}
