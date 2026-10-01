package com.irio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

/** 输入框使用直接描边；获得焦点时仅切换描边颜色，不再绘制外扩焦点环。 */
@Composable
internal fun rememberInputFieldBorder(
    shape: Shape = Theme[shapes][fieldShape],
    errorColor: androidx.compose.ui.graphics.Color? = null,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = errorColor ?: if (focused) Theme[colors][primaryColor] else Theme[colors][borderColor]
    return Modifier
        .onFocusChanged { focused = it.isFocused }
        .border(width = 1.dp, color = color, shape = shape)
}