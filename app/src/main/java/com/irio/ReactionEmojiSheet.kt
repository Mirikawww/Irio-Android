package com.irio


import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState


import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.BottomSheetState
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composables.ui.theme.indications
import com.composables.ui.theme.defaultIndication
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import com.irio.ScaledBottomSheet as BottomSheet

@Composable
internal fun ReactionEmojiSheet(
    state: BottomSheetState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    // Parse the bundled catalog off-main; no network and no first-tap parsing stall.
    val categories by produceState<List<ReactionEmojiCategory>>(emptyList()) {
        value = withContext(Dispatchers.Default) { ReactionEmojiCatalog.categories }
    }
    val scope = rememberCoroutineScope()
    val pager = remember(scope) { ReactionEmojiPagerState(scope) }
    pager.count = categories.size
    val dragCallbacks = remember(pager) { ReactionEmojiDragCallbacks(pager::beginDrag, pager::dragBy, pager::endDrag) }
    val content = Theme[colors][onPanelColor]
    val sheetHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.62f).toDp()
    }
    BottomSheet(
        state = state,
        onDismissRequest = onDismiss,
        contentColor = content,
        contentPadding = PaddingValues(top = 28.dp, bottom = 8.dp),
        scrollableBody = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().height(sheetHeight),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("添加响应"), fontSize = 18.sp, color = content, modifier = Modifier.padding(horizontal = 16.dp))
            Box(Modifier.padding(horizontal = 12.dp)) {
                ReactionEmojiCategoryTabs(categories, pager.selected) { pager.select(it) }
            }
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            // Only the source and destination mount, even for distant category clicks.
            // Only content has horizontal breathing room; the moving viewport is edge-to-edge.
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                pager.pages.forEach { page -> key(page) {
                    ReactionEmojiGrid(
                        category = categories.getOrNull(page),
                        color = content,
                        modifier = Modifier.fillMaxSize().testTag("reaction-emoji-page-$page").graphicsLayer {
                            translationX = pager.offsetFor(page) * size.width * if (rtl) -1f else 1f
                        },
                        bottomPadding = 8.dp + LocalBottomSheetBottomPadding.current,
                        horizontalPadding = 12.dp,
                        dragCallbacks = dragCallbacks,
                        onSelect = { if (page == pager.selected && !pager.dragging) onSelect(it) },
                    )
                } }
            }
        }
    }
}

@Composable
internal fun ReactionEmojiCategoryTabs(
    categories: List<ReactionEmojiCategory>,
    selected: Int,
    indication: androidx.compose.foundation.Indication = Theme[indications][defaultIndication],
    onSelect: (Int) -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = irioSpring<Float>(stiffness = 520f),
        label = "reactionCategoryIndicator",
    )
    val ink = Theme[colors][onPanelColor]
    val muted = Theme[colors][mutedColor]
    val fill = Theme[colors][secondaryColor]
    // A single measured indicator follows variable-width labels, even on interrupted changes.
    Layout(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        content = {
            Box(Modifier.testTag("reaction-emoji-tab-indicator").background(fill, ToolbarCapsuleShape))
            categories.forEachIndexed { index, category ->
                val requester = remember { BringIntoViewRequester() }
                LaunchedEffect(selected, categories.size) {
                    if (index == selected) requester.bringIntoView()
                }
                val color by animateColorAsState(
                    if (index == selected) ink else muted,
                    irioSpring(),
                    label = "reactionCategoryInk",
                )
                Text(
                    reactionCategoryLabel(category.name),
                    color = color,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .testTag("reaction-emoji-tab-$index")
                        .bringIntoViewRequester(requester)
                        .clip(ToolbarCapsuleShape)
                        .selectable(selected = index == selected, role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = indication, onClick = { onSelect(index) })
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val labels = measurables.drop(1).map {
            it.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        val gap = 4.dp.roundToPx()
        val starts = IntArray(labels.size)
        labels.indices.drop(1).forEach { starts[it] = starts[it - 1] + labels[it - 1].width + gap }
        val height = labels.maxOfOrNull { it.height } ?: 0
        val width = constraints.constrainWidth(labels.sumOf { it.width } + gap * (labels.size - 1).coerceAtLeast(0))
        val fraction = progress.coerceIn(0f, (labels.size - 1).coerceAtLeast(0).toFloat())
        val from = floor(fraction).toInt()
        val to = (from + 1).coerceAtMost(labels.lastIndex)
        val part = fraction - from
        val indicatorWidth = if (labels.isEmpty()) 0 else
            (labels[from].width + (labels[to].width - labels[from].width) * part).roundToInt()
        val indicatorStart = if (labels.isEmpty()) 0 else
            (starts[from] + (starts[to] - starts[from]) * part).roundToInt()
        val indicator = measurables[0].measure(Constraints.fixed(indicatorWidth, height))
        layout(width, constraints.constrainHeight(height)) {
            indicator.placeRelative(indicatorStart, 0)
            labels.forEachIndexed { index, label -> label.placeRelative(starts[index], (height - label.height) / 2) }
        }
    }
}

internal fun reactionCategoryLabel(name: String): String = tr(when (name) {
    "Smileys & Emotion" -> "笑脸与情绪"
    "People & Body" -> "人物与身体"
    "Animals & Nature" -> "动物与自然"
    "Food & Drink" -> "食物与饮品"
    "Travel & Places" -> "旅行与地点"
    "Activities" -> "活动"
    "Objects" -> "物品"
    "Symbols" -> "符号"
    "Flags" -> "旗帜"
    else -> name
})
