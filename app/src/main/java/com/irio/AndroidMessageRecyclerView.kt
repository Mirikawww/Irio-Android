package com.irio

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
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
    private var programmaticPosts = 0

    fun attach(view: MessageRecyclerView) {
        recyclerView = view
    }

    fun requestScrollToBottom() {
        scrollRequested = true
        flushScrollToBottom()
    }

    fun isProgrammaticScroll(): Boolean = programmaticPosts > 0

    fun flushScrollToBottom() {
        if (!scrollRequested) return
        val view = recyclerView ?: return
        if (view.itemCountValue() == 0) return
        if (view.isMessageUpdatePending()) {
            view.post { flushScrollToBottom() }
            return
        }
        scrollRequested = false
        programmaticPosts++
        view.post {
            val count = view.itemCountValue()
            if (count == 0) {
                programmaticPosts--
                return@post
            }
            view.layoutManager?.scrollToPosition(count - 1)
            programmaticPosts++
            view.post {
                try {
                    val child = view.layoutManager?.findViewByPosition(count - 1) ?: return@post
                    val bottom = view.height - view.paddingBottom
                    val delta = child.bottom - bottom
                    if (delta != 0) view.scrollBy(0, delta)
                } finally {
                    programmaticPosts--
                }
            }
            programmaticPosts--
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
    private var lastNotifiedFirst = RecyclerView.NO_POSITION
    private var lastNotifiedLast = RecyclerView.NO_POSITION

    init {
        layoutManager = LinearLayoutManager(context).apply {
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
        messageAdapter.updateMessages(next, onCommitted)
    }

    fun isMessageUpdatePending(): Boolean = messageAdapter.isUpdatePending()

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
        val position = parent.getChildAdapterPosition(view)
        outRect.bottom = if (position != RecyclerView.NO_POSITION && position < (parent.adapter?.itemCount ?: 0) - 1) {
            spacing
        } else {
            0
        }
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

    init {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val currentMessage = message
            val currentCallbacks = callbacks.value
            if (currentMessage != null && currentCallbacks != null) {
                AndroidMessageRow(currentMessage, currentCallbacks)
            }
        }
    }

    fun bind(nextMessage: ChatMessage) {
        message = nextMessage
    }

    fun clear() {
        message = null
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
        )
    }
}
