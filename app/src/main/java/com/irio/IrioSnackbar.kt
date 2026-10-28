package com.irio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.ui.components.Text
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonSize
import com.composables.ui.components.ButtonStyle
import com.composables.ui.theme.colors
import com.composables.ui.theme.largeShape
import com.composables.ui.theme.onPrimaryColor
import com.composables.ui.theme.primaryColor
import com.composables.ui.theme.shapes
import com.composeunstyled.theme.Theme
import com.composeunstyled.LocalContentColor
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Bottom snackbar. Swipe sideways to dismiss. No close button.
 * The outer box is what sits at the bottom; [AnimatedVisibility] inside a
 * [Box] would otherwise pin itself to the top.
 */
@Composable
internal fun BoxScope.IrioSnackbar(
    visible: Boolean,
    message: String,
    bottomInset: Dp,
    onDismiss: () -> Unit,
    restartKey: Int = 0,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    var displayedMessage by remember { mutableStateOf(message) }
    var replacing by remember { mutableStateOf(false) }
    var wasVisible by remember { mutableStateOf(visible) }
    // Reuse an on-screen surface so interrupted swipes can spring back. Once it
    // has left the viewport, create an explicitly hidden transition for a new entrance.
    val trigger = Triple(visible, message, restartKey)
    var previousTrigger by remember { mutableStateOf(trigger) }
    var motion by remember { mutableStateOf(SnackbarSwipeMotion(visible)) }
    if (trigger != previousTrigger) {
        previousTrigger = trigger
        if (visible && !motion.isOnScreen()) motion = SnackbarSwipeMotion(false)
    }
    val swipe = motion
    var offsetX by swipe.offsetX
    var swipeOff by swipe.swipeOff
    val currentSwipe by rememberUpdatedState(swipe)
    val currentTrigger by rememberUpdatedState(trigger)
    val currentlyVisible by rememberUpdatedState(visible)
    DisposableEffect(swipe, trigger) {
        onDispose { swipe.job?.cancel() }
    }
    LaunchedEffect(swipe, trigger) {
        if (!visible) {
            replacing = false
            wasVisible = false
            return@LaunchedEffect
        }
        swipeOff = false
        if (abs(offsetX) > 0.5f) {
            displayedMessage = message
            replacing = false
            wasVisible = true
            swipe.job = scope.launch {
                animate(offsetX, 0f, animationSpec = irioSpring()) { value, _ -> offsetX = value }
            }
            return@LaunchedEffect
        }
        if (!wasVisible || swipe.needsFreshContent) {
            displayedMessage = message
            swipe.needsFreshContent = false
            replacing = false
            wasVisible = true
            return@LaunchedEffect
        }
        if (displayedMessage == message) return@LaunchedEffect
        replacing = true
        delay(EXIT_DURATION_MS.toLong())
        if (!visible) return@LaunchedEffect
        displayedMessage = message
        replacing = false
    }
    LaunchedEffect(visible, displayedMessage, replacing, restartKey) {
        if (!visible || replacing) return@LaunchedEffect
        delay(5_000L)
        if (!replacing && !swipeOff && abs(offsetX) < 1f) dismiss()
    }
    Box(
        modifier = Modifier
            .testTag("irio-snackbar")
            .align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 16.dp)
            .fillMaxWidth(),
    ) {
        key(swipe) {
            swipe.visibility.targetState = visible && !replacing
            AnimatedVisibility(
                visibleState = swipe.visibility,
                enter = slideInVertically(initialOffsetY = { it }) + scaleIn(
                    initialScale = 0.35f,
                    animationSpec = irioSpring(),
                ),
                exit = if (swipeOff) {
                    ExitTransition.None
                } else {
                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(EXIT_DURATION_MS)) + scaleOut(
                        targetScale = 0.35f,
                        animationSpec = tween(EXIT_DURATION_MS),
                    )
                },
            ) {
                val surfaceModifier = Modifier
                    .pointerInput(swipe, trigger) {
                        val travel = size.width + 32.dp.toPx()
                        fun settle(target: Float, dismissAfter: Boolean = false) {
                            if (currentSwipe !== swipe || currentTrigger != trigger || !currentlyVisible) return
                            swipe.job?.cancel()
                            swipeOff = dismissAfter
                            swipe.job = scope.launch {
                                animate(
                                    initialValue = offsetX,
                                    targetValue = target,
                                    animationSpec = irioSpring(),
                                ) { value, _ -> offsetX = value }
                                if (dismissAfter && currentSwipe === swipe && currentTrigger == trigger && currentlyVisible) dismiss()
                            }
                        }
                        detectHorizontalDragGestures(
                            onDragStart = {
                                swipe.job?.cancel()
                                swipeOff = false
                            },
                            onHorizontalDrag = { _, amount -> offsetX += amount },
                            onDragCancel = { settle(0f) },
                            onDragEnd = {
                                if (abs(offsetX) > 120f) {
                                    val target = if (offsetX < 0f) -travel else travel
                                    settle(target, dismissAfter = true)
                                } else {
                                    settle(0f)
                                }
                            },
                        )
                    }
                    .graphicsLayer { translationX = offsetX }
                    .onGloballyPositioned { swipe.coordinates = it }
                    .fillMaxWidth()
                    .testTag("irio-snackbar-surface")
                    .background(Theme[colors][primaryColor], Theme[shapes][largeShape])
                val text = if (visible && !wasVisible) message else displayedMessage
                if (actionLabel != null && onAction != null) {
                    Row(
                        modifier = surfaceModifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text, modifier = Modifier.weight(1f).padding(end = 8.dp),
                            color = Theme[colors][onPrimaryColor], fontSize = 16.sp, lineHeight = 21.sp)
                        Button(onClick = onAction, style = ButtonStyle.Ghost, buttonSize = ButtonSize.Small,
                            shape = ContinuousCapsule, contentColor = Theme[colors][onPrimaryColor],
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(actionLabel, color = LocalContentColor.current)
                        }
                    }
                } else {
                    Text(text, modifier = surfaceModifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = Theme[colors][onPrimaryColor], fontSize = 16.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

private class SnackbarSwipeMotion(initialVisible: Boolean) {
    val visibility = MutableTransitionState(initialVisible)
    var needsFreshContent = !initialVisible
    val offsetX = mutableFloatStateOf(0f)
    val swipeOff = mutableStateOf(false)
    var job: Job? = null
    var coordinates: LayoutCoordinates? = null

    fun isOnScreen(): Boolean {
        val layout = coordinates?.takeIf { it.isAttached } ?: return false
        if (!visibility.currentState && !visibility.targetState) return false
        val bounds = layout.boundsInRoot()
        val root = layout.findRootCoordinates()
        return bounds.width > 1f && bounds.height > 1f && bounds.right > 0f &&
            bounds.left < root.size.width && bounds.bottom > 0f && bounds.top < root.size.height
    }
}

private const val EXIT_DURATION_MS = 180
