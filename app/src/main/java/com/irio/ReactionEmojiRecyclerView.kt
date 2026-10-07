package com.irio

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.roundToInt

/** The sheet stays Compose; scrolling and the simple emoji cells are native Android Views. */
@Composable
internal fun ReactionEmojiGrid(
    category: ReactionEmojiCategory?,
    color: Color,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { ReactionEmojiRecyclerView(it) },
        update = { view ->
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

    init {
        layoutManager = gridManager
        adapter = emojiAdapter
        itemAnimator = null
        setHasFixedSize(true)
        setItemViewCacheSize(16)
        isNestedScrollingEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
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
        // Claim the native gesture on DOWN, before the modal sheet's Compose drag detector
        // reaches touch slop and cancels AndroidView. Nested scroll still handles boundary handoff.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
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
