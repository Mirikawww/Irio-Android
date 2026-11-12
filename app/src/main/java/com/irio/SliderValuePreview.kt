package com.irio

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.composables.ui.theme.borderColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import kotlinx.coroutines.delay

/** A thumb-anchored preview must not take focus or cancel the slider's gesture. */
@Composable
internal fun SliderValuePreview(dragging: Boolean, tapSequence: Int, valueBounds: Rect,
    content: @Composable (Boolean) -> Unit) {
    var tapVisible by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var covered by remember { mutableStateOf(false) }
    val latestValueBounds by rememberUpdatedState(valueBounds)
    LaunchedEffect(tapSequence) {
        if (tapSequence != 0) {
            dismissed = false
            tapVisible = true
            delay(3_000)
            tapVisible = false
        }
    }
    LaunchedEffect(dragging) {
        if (dragging) {
            tapVisible = false
            dismissed = false
        }
    }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = !dismissed && (dragging || tapVisible)
    val transition = rememberTransition(visibility, label = "slider preview visibility")
    val progress by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 180 else 140) }, label = "slider preview progress",
    ) { visible -> if (visible) 1f else 0f }
    // Retain the native window until exit finishes, including outside dismissal.
    if (!visibility.currentState && !visibility.targetState && visibility.isIdle) return
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemBars.union(WindowInsets.ime)
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 12.dp.roundToPx() }
    val left = insets.getLeft(density, direction) + margin
    val right = insets.getRight(density, direction) + margin
    val top = insets.getTop(density) + margin
    val bottom = insets.getBottom(density) + margin
    val window = LocalWindowInfo.current.containerSize
    val maxWidth = with(density) { (window.width - left - right).coerceAtLeast(1).toDp() }
    val maxHeight = with(density) { (window.height - top - bottom).coerceAtLeast(1).toDp() }
    val position = remember(left, right, top, bottom, gap) {
        SliderPreviewPositionProvider(left, top, right, bottom, gap) { bounds ->

            covered = bounds.overlaps(latestValueBounds)
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = { tapVisible = false; dismissed = true },
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true)) {
        val shape = Theme[shapes][largeShape]
        Box(Modifier.widthIn(max = minOf(224.dp, maxWidth)).heightIn(max = maxHeight)
            .graphicsLayer {
                alpha = progress
                scaleX = .92f + .08f * progress
                scaleY = scaleX
            }
            .shadow(8.dp, shape).background(Theme[colors][panelColor], shape)
            .border(1.dp, Theme[colors][borderColor], shape)) {
            ProvideAppTextScale { content(covered) }
        }
    }
}

internal class SliderPreviewPositionProvider(
    private val left: Int,
    private val top: Int,
    private val right: Int,
    private val bottom: Int,
    private val gap: Int,
    private val onPosition: (Rect) -> Unit = {},
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - right - popupContentSize.width).coerceAtLeast(left)
        val maxY = (windowSize.height - bottom - popupContentSize.height).coerceAtLeast(top)
        val above = anchorBounds.top - top - gap
        val below = windowSize.height - bottom - anchorBounds.bottom - gap
        val y = if (above >= below) anchorBounds.top - gap - popupContentSize.height else anchorBounds.bottom + gap
        val result = IntOffset((anchorBounds.center.x - popupContentSize.width / 2).coerceIn(left, maxX), y.coerceIn(top, maxY))
        onPosition(Rect(result.x.toFloat(), result.y.toFloat(),
            (result.x + popupContentSize.width).toFloat(), (result.y + popupContentSize.height).toFloat()))
        return result
    }
}
