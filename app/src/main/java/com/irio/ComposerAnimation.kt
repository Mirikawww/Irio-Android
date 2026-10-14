package com.irio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale as insetScale
import androidx.compose.ui.graphics.drawscope.translate as insetTranslate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import com.composables.ui.theme.colors
import com.composables.ui.theme.backgroundColor as backgroundColorToken
import com.composeunstyled.theme.Theme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.ceil
import kotlin.math.min

/** Own the animation in a restartable scope; its slot never receives a per-frame Float. */
@Composable
internal fun AnimatedComposerContainer(
    expanded: Boolean,
    availableHeight: Dp,
    compactHeight: Dp,
    hazeState: HazeState,
    onCompactHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(State<Float>, Boolean) -> Unit,
) {
    val progress = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = irioSpring<Float>(stiffness = if (expanded) 420f else 560f),
        label = "composerExpansion",
    )
    val expandedLayout by remember(expanded, progress) {
        derivedStateOf { expanded || progress.value > 0f }
    }
    ComposerAnimationSurface(
        progress = progress,
        expandedLayout = expandedLayout,
        availableHeight = availableHeight,
        compactHeight = compactHeight,
        hazeState = hazeState,
        onCompactHeightChanged = onCompactHeightChanged,
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun ComposerAnimationSurface(
    progress: State<Float>,
    expandedLayout: Boolean,
    availableHeight: Dp,
    compactHeight: Dp,
    hazeState: HazeState,
    onCompactHeightChanged: (Int) -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.(State<Float>, Boolean) -> Unit,
) {
    val background = Theme[colors][backgroundColorToken]
    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (expandedLayout) 2f else 0f)
            .clipToBounds()
            .then(if (expandedLayout) Modifier.composerAnimatedHeight(progress, compactHeight, availableHeight) else Modifier)
            .then(
                if (expandedLayout) Modifier.background(background)
                else Modifier.hazeBlur(
                    input = HazeInput.Sources(hazeState),
                    style = chatComposerHazeStyle(),
                ),
            )
            .onSizeChanged { if (!expandedLayout) onCompactHeightChanged(it.height) },
    ) {
        content(progress, expandedLayout)
    }
}

/** Read progress during measurement, with the same constraint and rounding rules as height(). */
internal fun Modifier.composerAnimatedHeight(
    progress: State<Float>,
    collapsed: Dp,
    expanded: Dp,
): Modifier = layout { measurable, constraints ->
    val height = constraints.constrainHeight(lerp(collapsed, expanded, progress.value.coerceIn(0f, 1f)).roundToPx())
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** Animate real padding without rebuilding the composition on every frame. */
internal fun Modifier.composerAnimatedPadding(
    progress: State<Float>,
    start: Dp = 0.dp,
    top: Dp = 0.dp,
    end: Dp = 0.dp,
    bottom: Dp = 0.dp,
    expandedStart: Dp = 0.dp,
    expandedTop: Dp = 0.dp,
    expandedEnd: Dp = 0.dp,
    expandedBottom: Dp = 0.dp,
): Modifier = layout { measurable, constraints ->
    val fraction = progress.value.coerceIn(0f, 1f)
    val startPx = lerp(start, expandedStart, fraction).roundToPx()
    val topPx = lerp(top, expandedTop, fraction).roundToPx()
    val endPx = lerp(end, expandedEnd, fraction).roundToPx()
    val bottomPx = lerp(bottom, expandedBottom, fraction).roundToPx()
    val horizontal = startPx + endPx
    val vertical = topPx + bottomPx
    val placeable = measurable.measure(constraints.offset(-horizontal, -vertical))
    val width = constraints.constrainWidth(placeable.width + horizontal)
    val height = constraints.constrainHeight(placeable.height + vertical)
    layout(width, height) { placeable.placeRelative(startPx, topPx) }
}

/** Cache the outline; fade the fill and border in draw without fading the editor content. */
internal fun Modifier.composerAnimatedDecoration(
    progress: State<Float>,
    ring: Color,
    fill: Color,
    shape: Shape,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val strokeWidth = min(ceil(1.dp.toPx()), ceil(size.minDimension / 2))
    val halfStroke = strokeWidth / 2
    val topLeft = Offset(halfStroke, halfStroke)
    val borderSize = Size(size.width - strokeWidth, size.height - strokeWidth)
    val fillArea = strokeWidth * 2 > size.minDimension
    val stroke = Stroke(strokeWidth)
    val genericBorder = (outline as? Outline.Generic)?.takeUnless { fillArea }?.let {
        GenericBorderMaskCache(this, layoutDirection, it.path, strokeWidth)
    }
    val roundedPath = (outline as? Outline.Rounded)?.roundRect?.takeUnless { it.isSimple }?.let { rect ->
        Path().apply {
            addRoundRect(rect)
            if (!fillArea) {
                val inner = Path().apply {
                    addRoundRect(RoundRect(
                        left = strokeWidth,
                        top = strokeWidth,
                        right = rect.width - strokeWidth,
                        bottom = rect.height - strokeWidth,
                        topLeftCornerRadius = rect.topLeftCornerRadius.inset(strokeWidth),
                        topRightCornerRadius = rect.topRightCornerRadius.inset(strokeWidth),
                        bottomLeftCornerRadius = rect.bottomLeftCornerRadius.inset(strokeWidth),
                        bottomRightCornerRadius = rect.bottomRightCornerRadius.inset(strokeWidth),
                    ))
                }
                op(this, inner, PathOperation.Difference)
            }
        }
    }
    onDrawWithContent {
        val fade = 1f - progress.value.coerceIn(0f, 1f)
        drawContent()
        if (size.minDimension > 0f) {
            val brush = SolidColor(ring.copy(alpha = ring.alpha * fade))
            when (outline) {
                is Outline.Rectangle -> if (fillArea) drawRect(brush) else drawRect(brush, topLeft, borderSize, style = stroke)
                is Outline.Rounded -> {
                    val radius = outline.roundRect.topLeftCornerRadius
                    when {
                        roundedPath != null -> drawPath(roundedPath, brush)
                        fillArea -> drawRoundRect(brush, cornerRadius = radius)
                        radius.x < halfStroke -> clipRect(
                            strokeWidth, strokeWidth, size.width - strokeWidth, size.height - strokeWidth,
                            clipOp = ClipOp.Difference,
                        ) { drawRoundRect(brush, cornerRadius = radius) }
                        else -> drawRoundRect(brush, topLeft, borderSize, cornerRadius = radius.inset(halfStroke), style = stroke)
                    }
                }
                is Outline.Generic -> if (fillArea) drawPath(outline.path, brush) else {
                    val mask = genericBorder?.mask(ring.alpha * fade) ?: return@onDrawWithContent
                    drawImage(
                        mask.bitmap,
                        topLeft = mask.offset,
                        colorFilter = ColorFilter.tint(ring.copy(alpha = 1f)),
                    )
                }
            }
        }
    }
}.clip(shape).drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawBehind {
        val fade = 1f - progress.value.coerceIn(0f, 1f)
        drawOutline(outline, fill.copy(alpha = fill.alpha * fade))
    }
}

private fun CornerRadius.inset(amount: Float) = CornerRadius((x - amount).coerceAtLeast(0f), (y - amount).coerceAtLeast(0f))

/** Same offscreen mask as Compose's generic border, so a G2 outline matches `Modifier.border`. */
private fun genericBorderMask(
    density: androidx.compose.ui.unit.Density,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    path: Path,
    strokeWidth: Float,
    alpha: Float,
): GenericBorderMask {
    val pathBounds = path.getBounds()
    val maskPath = Path().apply {
        addRect(pathBounds)
        op(this, path, PathOperation.Difference)
    }
    val width = ceil(pathBounds.width).toInt().coerceAtLeast(1)
    val height = ceil(pathBounds.height).toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height, ImageBitmapConfig.Alpha8)
    val coverage = SolidColor(Color.Black.copy(alpha = alpha))
    CanvasDrawScope().draw(
        density,
        layoutDirection,
        Canvas(bitmap),
        Size(width.toFloat(), height.toFloat()),
    ) {
        drawRect(color = Color.Black, size = size, blendMode = BlendMode.Clear)
        insetTranslate(-pathBounds.left, -pathBounds.top) {
            drawPath(path, coverage, style = Stroke(strokeWidth * 2))
            insetScale((size.width + 1f) / size.width, (size.height + 1f) / size.height) {
                drawPath(maskPath, coverage, blendMode = BlendMode.Clear)
            }
        }
    }
    bitmap.prepareToDraw()
    return GenericBorderMask(bitmap, Offset(pathBounds.left, pathBounds.top))
}

private class GenericBorderMaskCache(
    private val density: androidx.compose.ui.unit.Density,
    private val layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    private val path: Path,
    private val strokeWidth: Float,
) {
    private var alpha = Float.NaN
    private var cached: GenericBorderMask? = null

    fun mask(alpha: Float): GenericBorderMask {
        val current = cached
        if (current != null && this.alpha == alpha) return current
        return genericBorderMask(density, layoutDirection, path, strokeWidth, alpha).also {
            this.alpha = alpha
            cached = it
        }
    }
}

private class GenericBorderMask(
    val bitmap: ImageBitmap,
    val offset: Offset,
)
