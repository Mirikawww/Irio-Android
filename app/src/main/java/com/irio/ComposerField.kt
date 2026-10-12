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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.theme.backgroundColor as backgroundColorToken
import com.composables.ui.theme.colors
import com.composables.ui.theme.defaultTextSelectionColors
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.fieldShape
import com.composables.ui.theme.fieldColor
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
 * 展开后正文独占宽度，底部操作层悬浮在编辑区域上，并留出相应高度避免遮挡光标。
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
    expanded: Boolean = false,
    expansion: State<Float>? = null,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    expandedFooter: (@Composable () -> Unit)? = null,
) {
    val shape = Theme[shapes][fieldShape]
    val contentColor = Theme[colors][onFieldColor]
    val textStyle = ComposerTextStyle.copy(color = contentColor)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // Keep the editor composition alive across layouts: its focus collector cannot replay
    // the FocusInteraction that was emitted before an expansion/collapse.
    val editor = remember {
        movableContentOf<@Composable () -> Unit> { innerTextField -> innerTextField() }
    }
    var footerHeightPx by remember { mutableIntStateOf(0) }
    // 紧凑态两侧按钮的实测宽度：展开过渡时正文边距从这里出发，收起结束时才能无缝衔接紧凑布局。
    var leadingWidthPx by remember { mutableIntStateOf(0) }
    var trailingWidthPx by remember { mutableIntStateOf(0) }
    var textHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val footerHeight = with(density) { footerHeightPx.toDp() }
    val progress = expansion ?: rememberUpdatedState(if (expanded) 1f else 0f)
    val fullyExpanded by remember(progress) { derivedStateOf { progress.value >= 1f } }
    val showGhosts by remember(progress) { derivedStateOf { progress.value < 0.6f } }
    val ringColor = if (focused) Theme[colors][primaryColor] else Theme[colors][borderColor]
    val fillColor = Theme[colors][fieldColor]
    val footerBackground = Theme[colors][backgroundColorToken]
    val buttonGap = 8.dp
    val compactStart = contentPadding.calculateLeftPadding(layoutDirection) +
        (if (leading != null) with(density) { leadingWidthPx.toDp() }.coerceAtLeast(16.dp) + buttonGap else 0.dp)
    val compactEnd = contentPadding.calculateRightPadding(layoutDirection) +
        (if (trailing != null) with(density) { trailingWidthPx.toDp() }.coerceAtLeast(16.dp) + buttonGap else 0.dp)
    // 紧凑态文本在 minContentHeight 内垂直居中；多行撑满后顶边就是 contentPadding.top。
    val compactTop = contentPadding.calculateTopPadding() +
        ((minContentHeight - with(density) { textHeightPx.toDp() }) / 2).coerceAtLeast(0.dp)

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
                .then(
                    if (fullyExpanded) Modifier else Modifier
                        .composerAnimatedDecoration(progress, ringColor, fillColor, shape),
                )
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
            onTextLayout = { getResult ->
                getResult()?.let {
                    if (progress.value == 0f && textHeightPx != it.size.height) textHeightPx = it.size.height
                    onTextLayout(it)
                }
            },
            interactionSource = interactionSource,
            cursorBrush = SolidColor(contentColor),
            decorator = TextFieldDecorator { innerTextField ->
                if (expanded) {
                    // 展开/收起全程走同一套叠层布局：正文边距随进度插值，
                    // 紧凑态按钮淡出、全屏操作区淡入，没有"整体换布局"的那一帧。
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .composerAnimatedPadding(
                                    progress = progress,
                                    start = compactStart,
                                    end = compactEnd,
                                    top = compactTop,
                                    bottom = contentPadding.calculateBottomPadding(),
                                    expandedStart = 20.dp,
                                    expandedEnd = 20.dp,
                                    expandedTop = 16.dp,
                                    expandedBottom = footerHeight,
                                )
                                .clipToBounds(),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            if (state.text.isEmpty()) {
                                Text(placeholder, style = textStyle, color = Theme[colors][mutedColor])
                            }
                            editor(innerTextField)
                        }
                        if (showGhosts) {
                            if (leading != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(
                                            start = contentPadding.calculateLeftPadding(layoutDirection),
                                            bottom = contentPadding.calculateBottomPadding(),
                                        )
                                        .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                        .graphicsLayer { alpha = (1f - progress.value / 0.6f).coerceIn(0f, 1f) },
                                    contentAlignment = Alignment.Center,
                                ) { leading() }
                            }
                            if (trailing != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(
                                            end = contentPadding.calculateRightPadding(layoutDirection),
                                            bottom = contentPadding.calculateBottomPadding(),
                                        )
                                        .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                        .graphicsLayer { alpha = (1f - progress.value / 0.6f).coerceIn(0f, 1f) },
                                    contentAlignment = Alignment.Center,
                                ) { trailing() }
                            }
                        }
                        // 操作区始终参与布局（透明时不可见），这样 footerHeight 从第一帧起就是真实值，
                        // 正文底边距不会在淡入的瞬间跳变。
                        run {
                            val slidePx = with(density) { 12.dp.toPx() }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .onSizeChanged { footerHeightPx = it.height }
                                    .graphicsLayer {
                                        val footerAlpha = ((progress.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                                        alpha = footerAlpha
                                        translationY = (1f - footerAlpha) * slidePx
                                    }
                                    .background(footerBackground),
                            ) {
                                expandedFooter?.invoke()
                            }
                        }
                    }
                } else Row(
                    modifier = Modifier.padding(contentPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (leading != null) {
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                .onSizeChanged { leadingWidthPx = it.width },
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
                            editor(innerTextField)
                        }
                    }
                    if (trailing != null) {
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                .onSizeChanged { trailingWidthPx = it.width },
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
