package com.irio

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.composables.ui.components.BottomSheet
import com.composables.ui.components.BottomSheetState
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composeunstyled.theme.Theme

/** Keep the original sheet geometry and gestures; scale text inside its separate window. */
@Composable
internal fun ScaledBottomSheet(
    state: BottomSheetState,
    onDismissRequest: () -> Unit,
    backgroundColor: Color = Theme[colors][panelColor],
    contentColor: Color = Theme[colors][onPanelColor],
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (contentPadding == null) {
        BottomSheet(state = state, onDismissRequest = onDismissRequest,
            backgroundColor = backgroundColor, contentColor = contentColor) {
            ProvideAppTextScale { content() }
        }
    } else {
        BottomSheet(state = state, onDismissRequest = onDismissRequest,
            backgroundColor = backgroundColor, contentColor = contentColor, contentPadding = contentPadding) {
            ProvideAppTextScale { content() }
        }
    }
}
