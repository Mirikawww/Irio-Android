package com.irio

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** The sheet stays Compose; scrolling and the simple emoji cells are native Android Views. */
@Composable
internal fun ReactionEmojiGrid(
    category: ReactionEmojiCategory?,
    color: Color,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 8.dp,
    onCategorySwipe: ((Int) -> Unit)? = null,
    dragCallbacks: ReactionEmojiDragCallbacks? = null,
    horizontalPadding: Dp = 0.dp,
    onSelect: (String) -> Unit,
) {
    val bottomPaddingPx = with(LocalDensity.current) { bottomPadding.roundToPx() }
    val horizontalPaddingPx = with(LocalDensity.current) { horizontalPadding.roundToPx() }
    AndroidView(
        modifier = modifier,
        factory = { ReactionEmojiRecyclerView(it) },
        update = { view ->
            if (view.paddingBottom != bottomPaddingPx || view.paddingLeft != horizontalPaddingPx) {
                view.setPadding(horizontalPaddingPx, 0, horizontalPaddingPx, bottomPaddingPx)
            }
            view.onCategorySwipe = onCategorySwipe
            view.dragCallbacks = dragCallbacks
            view.update(category?.name.orEmpty(), category?.emojis.orEmpty(), color.toArgb(), onSelect)
        },
        onRelease = { it.release() },
    )
}

internal class ReactionEmojiRecyclerView(context: Context) : RecyclerView(context) {
    private val cellSize = (44 * resources.displayMetrics.density).roundToInt()
    private val spacing = (4 * resources.displayMetrics.density).roundToInt()
    private val gridManager = GridLayoutManager(context, 1)
    private val emojiAdapter = EmojiAdapter(cellSize)
    private var categoryKey: String? = null
    private var emojis: List<String> = emptyList()
    private var updateGeneration = 0L
    private var released = false
    var onCategorySwipe: ((Int) -> Unit)? = null
    var dragCallbacks: ReactionEmojiDragCallbacks? = null
    private var lastRawX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var touchActive = false
    private var touchAxis = TouchAxis.Pending

    private enum class TouchAxis { Pending, Horizontal, Vertical, Cancelled }

    init {
        layoutManager = gridManager
        adapter = emojiAdapter
        itemAnimator = null
        setHasFixedSize(true)
        setItemViewCacheSize(16)
        isNestedScrollingEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(0, 0, 0, (8 * resources.displayMetrics.density).roundToInt())
        addItemDecoration(object : ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: State) {
                val position = parent.getChildAdapterPosition(view)
                if (position == NO_POSITION) return
                val spans = gridManager.spanCount
                val column = position % spans
                val start = column * spacing / spans
                val end = spacing - (column + 1) * spacing / spans
                if (parent.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    outRect.left = end
                    outRect.right = start
                } else {
                    outRect.left = start
                    outRect.right = end
                }
                outRect.top = if (position >= spans) spacing else 0
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Retain the established AndroidView/BottomSheet ownership. Vertical gestures still
        // go through RecyclerView and nested scrolling; only a locked horizontal gesture is ours.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchActive = true
            downX = event.x
            downY = event.y
            touchAxis = TouchAxis.Pending
            lastRawX = event.rawX
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return try {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Never turn a pinch, pointer replacement or its coordinate jump into a page.
                    if (touchAxis == TouchAxis.Horizontal) dragCallbacks?.onEnd?.invoke(true)
                    cancelNativeTouch(event)
                    touchAxis = TouchAxis.Cancelled
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchAxis == TouchAxis.Pending && (onCategorySwipe != null || dragCallbacks != null)) {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        if (max(dx, dy) > touchSlop) {
                            touchAxis = if (dx > dy) TouchAxis.Horizontal else TouchAxis.Vertical
                            if (touchAxis == TouchAxis.Horizontal) {
                                // Cancel pressed TextViews and the native scroll before taking over.
                                cancelNativeTouch(event)
                                stopScroll()
                                parent?.requestDisallowInterceptTouchEvent(true)
                                dragCallbacks?.onStart?.invoke()
                            }
                        }
                    }
                    if (touchAxis == TouchAxis.Horizontal) {
                        dragCallbacks?.onDelta?.invoke(event.rawX - lastRawX, width.toFloat(), layoutDirection == View.LAYOUT_DIRECTION_RTL)
                        lastRawX = event.rawX
                        true
                    } else if (touchAxis == TouchAxis.Cancelled) true
                    else super.dispatchTouchEvent(event)
                }
                MotionEvent.ACTION_UP -> {
                    if (touchAxis == TouchAxis.Horizontal) {
                        if (dragCallbacks != null) {
                            dragCallbacks?.onEnd?.invoke(false)
                            return true
                        }
                        val dx = event.x - downX
                        val threshold = max(touchSlop * 2f, min(width * 0.15f, 64f * resources.displayMetrics.density))
                        if (abs(dx) >= threshold) {
                            val physicalStep = if (dx < 0) 1 else -1
                            onCategorySwipe?.invoke(if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -physicalStep else physicalStep)
                        }
                        true
                    } else if (touchAxis == TouchAxis.Cancelled) true
                    else super.dispatchTouchEvent(event)
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (touchAxis == TouchAxis.Horizontal) dragCallbacks?.onEnd?.invoke(true)
                    if (touchAxis == TouchAxis.Horizontal || touchAxis == TouchAxis.Cancelled) true
                    else super.dispatchTouchEvent(event)
                }
                else -> super.dispatchTouchEvent(event)
            }
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchActive = false
                touchAxis = TouchAxis.Pending
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun cancelNativeTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val available = View.MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        val spans = max(1, (available + spacing) / (cellSize + spacing))
        if (gridManager.spanCount != spans) {
            gridManager.spanCount = spans
            invalidateItemDecorations()
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    fun update(category: String, next: List<String>, color: Int, onSelect: (String) -> Unit) {
        if (released) return
        emojiAdapter.onSelect = onSelect
        emojiAdapter.updateColor(color)
        val categoryChanged = categoryKey != category
        if (!categoryChanged && emojis == next) return
        categoryKey = category
        emojis = next
        val generation = ++updateGeneration
        if (categoryChanged) stopScroll()
        emojiAdapter.prepareIds(next)
        emojiAdapter.submitList(next.toList()) {
            if (!released && generation == updateGeneration) {
                invalidateItemDecorations()
                if (categoryChanged) gridManager.scrollToPositionWithOffset(0, 0)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        updateGeneration++
        stopScroll()
        touchAxis = TouchAxis.Cancelled
        onCategorySwipe = null
        dragCallbacks = null
        if (touchActive) parent?.requestDisallowInterceptTouchEvent(false)
        touchActive = false
        emojiAdapter.onSelect = {}
        adapter = null
    }
}

private class EmojiAdapter(private val cellSize: Int) : ListAdapter<String, EmojiViewHolder>(
    object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    },
) {
    var onSelect: (String) -> Unit = {}
    private var color = android.graphics.Color.BLACK
    private val ids = mutableMapOf<String, Long>()
    private var nextId = 0L

    init {
        setHasStableIds(true)
    }

    fun prepareIds(emojis: List<String>) {
        emojis.forEach { ids.getOrPut(it) { nextId++ } }
    }

    override fun getItemId(position: Int): Long = ids.getValue(getItem(position))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
        val cell = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setSingleLine(true)
            isFocusable = true
            val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = attributes.getDrawable(0)
            attributes.recycle()
        }
        return EmojiViewHolder(cell) { onSelect(it) }
    }

    override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
        holder.bind(getItem(position), color)
    }

    override fun onViewRecycled(holder: EmojiViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    fun updateColor(next: Int) {
        if (color == next) return
        color = next
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, COLOR_PAYLOAD)
    }

    private companion object {
        val COLOR_PAYLOAD = Any()
    }
}

private class EmojiViewHolder(
    private val cell: TextView,
    onSelect: (String) -> Unit,
) : RecyclerView.ViewHolder(cell) {
    private var emoji = ""

    init {
        cell.setOnClickListener { if (emoji.isNotEmpty()) onSelect(emoji) }
    }

    fun bind(next: String, color: Int) {
        emoji = next
        cell.text = next
        cell.contentDescription = next
        cell.setTextColor(color)
    }

    fun clear() {
        emoji = ""
        cell.text = ""
        cell.contentDescription = null
    }
}
