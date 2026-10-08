package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.BottomSheet
import com.composables.ui.components.BottomSheetState
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.secondaryColor
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var categoryIndex by remember { mutableStateOf(0) }
    val content = Theme[colors][onPanelColor]
    val sheetHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * 0.62f).toDp()
    }
    BottomSheet(
        state = state,
        onDismissRequest = onDismiss,
        contentColor = content,
        contentPadding = PaddingValues(start = 12.dp, top = 28.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().height(sheetHeight)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("添加响应"), fontSize = 18.sp, color = content, modifier = Modifier.padding(horizontal = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                categories.forEachIndexed { index, category ->
                    Text(
                        reactionCategoryLabel(category.name),
                        color = if (categoryIndex == index) content else Theme[colors][mutedColor],
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(
                                if (categoryIndex == index) Theme[colors][secondaryColor]
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { categoryIndex = index }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            ReactionEmojiGrid(
                category = categories.getOrNull(categoryIndex),
                color = content,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onSelect = onSelect,
            )
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
