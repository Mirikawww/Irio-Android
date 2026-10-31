package com.irio

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.ui.components.BottomSheet
import com.composables.ui.components.BottomSheetState
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme

/** Trailing space belongs inside a scrollable body's content, not outside its viewport. */
internal val LocalBottomSheetBottomPadding = compositionLocalOf { 0.dp }

/** Keep the sheet gestures and text scaling; optionally let the body own its bottom spacing. */
@Composable
internal fun ScaledBottomSheet(
    state: BottomSheetState,
    onDismissRequest: () -> Unit,
    backgroundColor: Color = Theme[colors][panelColor],
    contentColor: Color = Theme[colors][onPanelColor],
    contentPadding: PaddingValues? = null,
    scrollableBody: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val padding = contentPadding ?: PaddingValues(20.dp)
    val direction = LocalLayoutDirection.current
    val outerPadding = if (scrollableBody) PaddingValues(
        start = padding.calculateStartPadding(direction),
        top = padding.calculateTopPadding(),
        end = padding.calculateEndPadding(direction),
    ) else padding
    BottomSheet(state = state, onDismissRequest = onDismissRequest,
        backgroundColor = backgroundColor, contentColor = contentColor, contentPadding = outerPadding) {
        val bottom = if (scrollableBody) padding.calculateBottomPadding() +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
        CompositionLocalProvider(LocalBottomSheetBottomPadding provides bottom) {
            ProvideAppTextScale { content() }
        }
    }
}
