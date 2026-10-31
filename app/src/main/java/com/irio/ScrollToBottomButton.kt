package com.irio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.ui.components.Icon
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.primaryColor
import com.composeunstyled.theme.Theme

internal const val ScrollToBottomRippleCount = 1

/** An overlay above the composer. Never participates in transcript measurement or scrolling. */
@Composable
internal fun BoxScope.ScrollToBottomButton(
    visible: Boolean,
    followingBottom: Boolean,
    responding: Boolean,
    bottomInset: Dp,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        // Tail growth can temporarily move the viewport off bottom between follow frames.
        visible = visible && !followingBottom,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomInset + 12.dp),
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 4 },
        label = "scrollToBottomVisibility",
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (responding) ScrollToBottomRipple(Modifier.matchParentSize())
            // A neutral surface leaves the accent-colored ring visible.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Theme[colors][panelColor], ContinuousCapsule)
                    .clip(ContinuousCapsule)
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = tr("滚动到底部") }
                    .testTag("scroll-to-bottom"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = IrioIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = Theme[colors][onPanelColor],
                )
            }
        }
    }
}

@Composable
private fun ScrollToBottomRipple(modifier: Modifier) {
    val accent = Theme[colors][primaryColor]
    val phase = rememberInfiniteTransition(label = "scrollToBottomRipple").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "scrollToBottomRipplePhase",
    )
    Canvas(modifier.testTag("scroll-to-bottom-ripple")) {
        val progress = phase.value
        val radius = size.minDimension / 2f + 10.dp.toPx() * progress
        // Exactly one circle; unlike the drawer's three staggered waves.
        repeat(ScrollToBottomRippleCount) {
            drawCircle(accent.copy(alpha = accent.alpha * generatingRippleAlpha(progress)), radius,
                style = Stroke(1.dp.toPx()))
        }
    }
}
