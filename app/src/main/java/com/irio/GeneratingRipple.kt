package com.irio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.ui.theme.colors
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme

internal const val GeneratingRippleCount = 3

internal fun generatingRippleProgress(phase: Float, ring: Int): Float =
    (phase + ring.toFloat() / GeneratingRippleCount) % 1f

internal fun generatingRippleAlpha(progress: Float): Float {
    // Fade in at the centre so wrapping back from the outer edge never flashes.
    val fadeIn = (progress / 0.12f).coerceIn(0f, 1f)
    val fadeOut = 1f - progress
    return 0.55f * fadeIn * fadeOut * fadeOut
}

/** Compact concentric waves, entirely inside the space reserved at the row end. */
internal fun Modifier.generatingRipple(active: Boolean): Modifier = composed {
    if (!active) return@composed this
    observeIrioTheme()
    val accent = Theme[colors][primaryColor]
    val phase = rememberInfiniteTransition(label = "generatingRipple").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "generatingRipplePhase",
    )
    drawWithContent {
        drawContent()
        val edge = 28.dp.toPx()
        val origin = Offset(
            x = if (layoutDirection == LayoutDirection.Ltr) size.width - edge else edge,
            y = size.height / 2f,
        )
        val innerRadius = 4.dp.toPx()
        val outerRadius = minOf(18.dp.toPx(), size.height / 2f - 1.dp.toPx())
        val stroke = Stroke(width = 1.dp.toPx())
        val currentPhase = phase.value
        repeat(GeneratingRippleCount) { ring ->
            val progress = generatingRippleProgress(currentPhase, ring)
            drawCircle(
                color = accent.copy(alpha = accent.alpha * generatingRippleAlpha(progress)),
                radius = innerRadius + (outerRadius - innerRadius) * progress,
                center = origin,
                style = stroke,
            )
        }
    }
}
