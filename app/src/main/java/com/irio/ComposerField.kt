package com.irio

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.colors
import com.composables.ui.theme.defaultTextSelectionColors
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.fieldColor
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.mutedColor
import com.composables.ui.theme.onFieldColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.shapes
import com.composables.ui.theme.textSelectionColors
import com.composeunstyled.theme.Theme

/**
 * 输入文本样式：显式关掉 lineHeight 裁剪并把多余行距均分到上下，
 * 这样 placeholder（BasicText）和输入文本（BasicTextField）的行盒完全一致，
 * 不需要再用 BaselineShift 手动补偿。
 */
internal val ComposerTextStyle = TextStyle(
    fontSize = 16.sp,
    lineHeight = 20.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * 主页输入框。自己组装装饰层，因为 com.composables:ui 的 TextField 在
 * lineLimits 非 SingleLine 时把整行按 Alignment.Top 对齐，单行状态下文本会
 * 相对 leading/trailing 按钮偏上。文本区按 [minContentHeight] 垂直居中，
 * 单行时与 32dp 按钮中线对齐；整行按底部对齐，换行后发送和附件留在最后一行。
 */
@Composable
internal fun ComposerField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.MultiLine(
        minHeightInLines = 1,
        maxHeightInLines = 4,
    ),
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    minContentHeight: Dp = 32.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val shape = Theme[shapes][fieldShape]
    val contentColor = Theme[colors][onFieldColor]
    val textStyle = ComposerTextStyle.copy(color = contentColor)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    CompositionLocalProvider(
        LocalTextSelectionColors provides Theme[textSelectionColors][defaultTextSelectionColors],
    ) {
        BasicTextField(
            state = state,
            modifier = modifier
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    }
                )
                .composerFocusRing(
                    color = if (focused) Theme[colors][primaryColor] else Theme[colors][borderColor],
                    shape = shape,
                )
                .clip(shape)
                .background(Theme[colors][fieldColor], shape)
                .then(
                    if (accessibilityLabel != null) {
                        Modifier.semantics { contentDescription = accessibilityLabel }
                    } else {
                        Modifier
                    }
                ),
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction,
            lineLimits = lineLimits,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(contentColor),
            decorator = TextFieldDecorator { innerTextField ->
                Row(
                    modifier = Modifier.padding(contentPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (leading != null) {
                        Box(
                            modifier = Modifier.sizeIn(minWidth = 16.dp, minHeight = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            leading()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = minContentHeight)
                            .clipToBounds(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.text.isEmpty()) {
                            Text(
                                placeholder,
                                style = textStyle,
                                color = Theme[colors][mutedColor],
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            innerTextField()
                        }
                    }
                    if (trailing != null) {
                        Box(
                            modifier = Modifier.sizeIn(minWidth = 16.dp, minHeight = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            trailing()
                        }
                    }
                }
            },
        )
    }
}

/**
 * 输入框直接描边：空闲和激活状态使用不同颜色，但描边始终贴合输入框边界。
 */
private fun Modifier.composerFocusRing(
    color: Color,
    shape: Shape,
): Modifier {
    return border(width = 1.dp, color = color, shape = shape)
}
