package com.irio

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.kyant.shapes.RoundedRectangle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.onBackgroundColor
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme

@Composable
fun ListRowButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    style: ButtonStyle = ButtonStyle.Secondary,
    shape: Shape = Theme[shapes][largeShape],
    contentPadding: PaddingValues? = null,
    onLongClick: (() -> Unit)? = null,
    experimental: Boolean = false,
    iconPainter: Painter? = null,
    labelFontWeight: FontWeight? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    searchId: String? = null,
) {
    val contentColor = if (style == ButtonStyle.Ghost) {
        Theme[colors][onBackgroundColor]
    } else {
        Theme[colors][onPanelColor]
    }
    // 当前行常驻底色：与背景同色系、低透明度，让当前对话在列表里一眼可辨
    val activeBackground = if (active) {
        Theme[colors][onBackgroundColor].copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    val content: @Composable RowScope.() -> Unit = {
        if (iconPainter != null) {
            Image(painter = iconPainter, contentDescription = null, modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(contentColor))
            Spacer(Modifier.width(12.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
            Spacer(Modifier.width(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = labelFontWeight)
            if (experimental) {
                Spacer(Modifier.width(8.dp))
                ExperimentalBadge()
            }
        }
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
    val buttonContent: @Composable RowScope.() -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            if (searchId != null) {
                Box(
                    Modifier.matchParentSize().settingsSearchAnchor(
                        searchId,
                        edge = SettingsSearchEdge.All,
                        verticalOutset = 0.dp,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
    var longPressConsumed by remember { mutableStateOf(false) }
    val handleClick = {
        if (longPressConsumed) {
            longPressConsumed = false
        } else {
            onClick()
        }
    }
    val clickModifier = modifier.fillMaxWidth().then(
        if (onLongClick != null) {
            Modifier.pointerInput(onLongClick) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val longPress = try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                            false
                        }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        true
                    }
                    if (longPress) {
                        longPressConsumed = true
                        onLongClick()
                        waitForUpOrCancellation()
                    }
                }
            }
        } else {
            Modifier
        },
    )
    if (contentPadding != null) {
        Button(
            onClick = handleClick,
            modifier = clickModifier.background(activeBackground, shape),
            enabled = enabled,
            style = style,
            shape = shape,
            buttonSize = ButtonSize.Large,
            contentColor = contentColor,
            contentPadding = contentPadding,
            content = if (searchId == null) content else buttonContent,
        )
    } else {
        Button(
            onClick = handleClick,
            modifier = clickModifier.background(activeBackground, shape),
            enabled = enabled,
            style = style,
            shape = shape,
            buttonSize = ButtonSize.Large,
            contentColor = contentColor,
            content = if (searchId == null) content else buttonContent,
        )
    }
}

@Composable
fun ExperimentalLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = color)
        ExperimentalBadge()
    }
}

@Composable
fun ExperimentalBadge() {
    Text(
        tr("实验性"),
        modifier = Modifier
            .background(Theme[colors][primaryColor], RoundedRectangle(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Theme[colors][onPrimaryColor],
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        singleLine = true,
        overflow = TextOverflow.Clip,
    )
}
