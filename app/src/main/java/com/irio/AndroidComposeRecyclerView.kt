package com.irio

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Outline as ComposeOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView bridge for small Compose lists that are sensitive to LazyColumn scroll overhead.
 * Each recycled row still renders its existing Compose content inside a ComposeView.
 */
@Composable
internal fun <T : Any> AndroidComposeRecyclerList(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    itemSpacing: Dp = 0.dp,
    cornerRadius: Dp = 0.dp,
    opacity: State<Float>? = null,
    interactionEnabled: Boolean = true,
    prioritizeScrollGestures: Boolean = false,
    maintainEndPosition: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val padding = with(density) {
        RecyclerPadding(
            start = contentPadding.calculateLeftPadding(layoutDirection).roundToPx(),
            top = contentPadding.calculateTopPadding().roundToPx(),
            end = contentPadding.calculateRightPadding(layoutDirection).roundToPx(),
            bottom = contentPadding.calculateBottomPadding().roundToPx(),
        )
    }
    val spacingPx = with(density) { itemSpacing.roundToPx() }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ComposeRecyclerView(context, key, content).also { view ->
                view.maintainEndPosition = maintainEndPosition
                view.prioritizeScrollGestures = prioritizeScrollGestures
                view.updateVisibility(opacity?.value ?: 1f, interactionEnabled)
                view.updateCornerRadius(cornerRadiusPx)
                view.updatePadding(padding)
                view.updateItemSpacing(spacingPx)
                view.updateItems(items)
            }
        },
        update = { view ->
            view.maintainEndPosition = maintainEndPosition
            view.prioritizeScrollGestures = prioritizeScrollGestures
            view.updateVisibility(opacity?.value ?: 1f, interactionEnabled)
            view.updateCornerRadius(cornerRadiusPx)
            view.updatePadding(padding)
            view.updateItemSpacing(spacingPx)
            view.updateContent(content)
            view.updateItems(items)
        },
    )
}

private data class RecyclerPadding(
    val start: Int,
    val top: Int,
    val end: Int,
    val bottom: Int,
)

private class ComposeRecyclerView<T : Any>(
    context: Context,
    key: (T) -> Any,
    content: @Composable (T) -> Unit,
) : RecyclerView(context) {
    private val listAdapter = ComposeListAdapter(key, content)
    private val listLayoutManager = EndAnchoredLayoutManager(context)
    var maintainEndPosition: Boolean
        get() = listLayoutManager.maintainEndPosition
        set(value) { listLayoutManager.maintainEndPosition = value }
    private var spacingDecoration: RecyclerSpacingDecoration? = null
    private var cornerRadiusPx = 0f
    private var acceptsTouch = true
    var prioritizeScrollGestures = false
    private var lastTouchY = 0f

    init {
        layoutManager = listLayoutManager
        adapter = listAdapter
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        clipChildren = true
        // 父级抽屉 graphicsLayer 不裁剪，Compose 的 clip 也盖不住 Android View。
        // 把绘制锁在视图矩形里，历史行才不会叠到「新对话」上或从设置按钮底下的缝漏出去。
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val width = view.width
                val height = view.height
                if (width <= 0 || height <= 0 || cornerRadiusPx <= 0f) {
                    outline.setRect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
                    return
                }
                val density = Density(
                    view.resources.displayMetrics.density,
                    view.resources.configuration.fontScale,
                )
                val shapeOutline = RoundedRectangle(with(density) { cornerRadiusPx.toDp() }).createOutline(
                    androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()),
                    LayoutDirection.Ltr,
                    density,
                )
                when (shapeOutline) {
                    is ComposeOutline.Generic -> outline.setPath(shapeOutline.path.asAndroidPath())
                    is ComposeOutline.Rounded -> outline.setRoundRect(
                        0, 0, width, height, shapeOutline.roundRect.topLeftCornerRadius.x,
                    )
                    else -> outline.setRect(0, 0, width, height)
                }
            }
        }
        clipToOutline = true
        setItemViewCacheSize(4)
    }

    fun updateVisibility(opacity: Float, interactionEnabled: Boolean) {
        alpha = opacity.coerceIn(0f, 1f)
        acceptsTouch = interactionEnabled && alpha >= 0.5f
        importantForAccessibility = if (acceptsTouch) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!acceptsTouch) return false
        // A fresh gesture (including expanding a card) starts a new positioning intent.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) listLayoutManager.releaseEndAnchor()
        // AndroidView must claim DOWN before a Compose sheet's drag detector reaches
        // touch slop and cancels the list. Nested scrolling still hands off at edges.
        if (prioritizeScrollGestures && event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchY = event.y
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (prioritizeScrollGestures && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val deltaY = event.y - lastTouchY
            if (deltaY != 0f) {
                val direction = if (deltaY > 0f) -1 else 1
                parent?.requestDisallowInterceptTouchEvent(canScrollVertically(direction))
                lastTouchY = event.y
            }
        }
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (prioritizeScrollGestures &&
                (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            ) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    fun updateCornerRadius(next: Float) {
        if (cornerRadiusPx != next) {
            cornerRadiusPx = next
            invalidateOutline()
        }
    }

    fun updateItems(next: List<T>) {
        listAdapter.submitList(next)
    }

    fun updateContent(next: @Composable (T) -> Unit) {
        listAdapter.updateContent(next)
    }

    fun updatePadding(next: RecyclerPadding) {
        if (
            paddingLeft != next.start ||
            paddingTop != next.top ||
            paddingRight != next.end ||
            paddingBottom != next.bottom
        ) {
            setPadding(next.start, next.top, next.end, next.bottom)
        }
    }

    fun updateItemSpacing(next: Int) {
        if (spacingDecoration?.spacing == next) return
        spacingDecoration?.let(::removeItemDecoration)
        spacingDecoration = RecyclerSpacingDecoration(next).also(::addItemDecoration)
    }
}

/** Retain an already-reached end when Compose rows remeasure after a fast scroll. */
private class EndAnchoredLayoutManager(context: Context) : LinearLayoutManager(context) {
    var maintainEndPosition = false
    private var reachedEnd = false

    fun releaseEndAnchor() { reachedEnd = false }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (dy < 0) reachedEnd = false
        val consumed = super.scrollVerticallyBy(dy, recycler, state)
        if (maintainEndPosition && dy > 0) {
            val last = findViewByPosition(itemCount - 1)
            reachedEnd = last != null && getDecoratedBottom(last) <= height - paddingBottom
        }
        return consumed
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val anchorEnd = maintainEndPosition && reachedEnd && !state.isPreLayout
        super.onLayoutChildren(recycler, state)
        if (anchorEnd) {
            val last = findViewByPosition(itemCount - 1) ?: return
            val overflow = getDecoratedBottom(last) - (height - paddingBottom)
            if (overflow > 0) super.scrollVerticallyBy(overflow, recycler, state)
        }
    }
}

private class RecyclerSpacingDecoration(var spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        outRect.bottom = if (
            position != RecyclerView.NO_POSITION &&
            position < (parent.adapter?.itemCount ?: 0) - 1
        ) {
            spacing
        } else {
            0
        }
    }
}

private class ComposeListAdapter<T : Any>(
    private val keyOf: (T) -> Any,
    content: @Composable (T) -> Unit,
) : ListAdapter<T, ComposeListViewHolder<T>>(
    object : DiffUtil.ItemCallback<T>() {
        override fun areItemsTheSame(oldItem: T, newItem: T): Boolean = keyOf(oldItem) == keyOf(newItem)

        override fun areContentsTheSame(oldItem: T, newItem: T): Boolean = oldItem == newItem
    },
) {
    private val contentState = mutableStateOf(content)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ComposeListViewHolder<T> =
        ComposeListViewHolder(ComposeView(parent.context), contentState)

    override fun onBindViewHolder(holder: ComposeListViewHolder<T>, position: Int) {
        holder.bind(getItem(position), keyOf(getItem(position)))
    }

    override fun onViewRecycled(holder: ComposeListViewHolder<T>) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    fun updateContent(next: @Composable (T) -> Unit) {
        contentState.value = next
    }
}

private class ComposeListViewHolder<T : Any>(
    private val composeView: ComposeView,
    private val contentState: androidx.compose.runtime.MutableState<(@Composable (T) -> Unit)>,
) : RecyclerView.ViewHolder(composeView) {
    private var item by mutableStateOf<T?>(null)
    private var boundKey: Any? = null

    init {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val currentItem = item
            if (currentItem != null) {
                ProvideAppTextScale { contentState.value(currentItem) }
            }
        }
    }

    fun bind(next: T, key: Any) {
        val identityChanged = boundKey != key
        if (identityChanged) composeView.disposeComposition()
        boundKey = key
        item = next
        if (identityChanged) {
            // RecyclerView measures immediately after binding, before the next Compose
            // frame. Recreate only on identity changes so that traversal cannot reuse
            // another item's text, remembered animations, or measured height.
            if (composeView.isAttachedToWindow) composeView.createComposition()
            composeView.requestLayout()
        }
    }

    fun clear() {
        composeView.disposeComposition()
        boundKey = null
        item = null
    }
}
