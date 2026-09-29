package com.irio

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ComposeRecyclerView(context, key, content).also { view ->
                view.updatePadding(padding)
                view.updateItemSpacing(spacingPx)
                view.updateItems(items)
            }
        },
        update = { view ->
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
    private var spacingDecoration: RecyclerSpacingDecoration? = null

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = listAdapter
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        clipChildren = true
        // 父级抽屉 graphicsLayer 不裁剪，Compose 的 clip 也盖不住 Android View。
        // 把绘制锁在视图矩形里，历史行才不会叠到「新对话」上或从设置按钮底下的缝漏出去。
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRect(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
        setItemViewCacheSize(4)
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
    keyOf: (T) -> Any,
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
        holder.bind(getItem(position))
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

    init {
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            val currentItem = item
            if (currentItem != null) {
                contentState.value(currentItem)
            }
        }
    }

    fun bind(next: T) {
        item = next
    }

    fun clear() {
        item = null
    }
}
