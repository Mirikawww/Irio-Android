package com.irio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs
import kotlinx.coroutines.delay
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
    var offsetX by remember { mutableFloatStateOf(0f) }
    var swipeOff by remember { mutableStateOf(false) }
    LaunchedEffect(visible, message) {
        if (!visible) {
            displayedMessage = message
            replacing = false
            wasVisible = false
            return@LaunchedEffect
        }
        if (!wasVisible) {
            displayedMessage = message
            replacing = false
            wasVisible = true
            return@LaunchedEffect
        }
        if (displayedMessage == message) return@LaunchedEffect
        replacing = true
        delay(EXIT_DURATION_MS.toLong())
        if (!visible) return@LaunchedEffect
        displayedMessage = message
        offsetX = 0f
        swipeOff = false
        replacing = false
    }
    LaunchedEffect(visible, displayedMessage, replacing, restartKey) {
        if (!visible || replacing) return@LaunchedEffect
        offsetX = 0f
        swipeOff = false
        delay(5_000L)
        if (!replacing && !swipeOff && abs(offsetX) < 1f) dismiss()
    }
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 16.dp)
            .fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = visible && !replacing,
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
                    .graphicsLayer { translationX = offsetX }
                    .pointerInput(Unit) {
                        val travel = size.width + 32.dp.toPx()
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, amount -> offsetX += amount },
                            onDragEnd = {
                                if (abs(offsetX) > 120f) {
                                    swipeOff = true
                                    val target = if (offsetX < 0f) -travel else travel
                                    scope.launch {
                                        animate(
                                            initialValue = offsetX,
                                            targetValue = target,
                                            animationSpec = irioSpring(),
                                        ) { value, _ ->
                                            offsetX = value
                                        }
                                        dismiss()
                                    }
                                } else {
                                    scope.launch {
                                        animate(
                                            initialValue = offsetX,
                                            targetValue = 0f,
                                            animationSpec = irioSpring(),
                                        ) { value, _ ->
                                            offsetX = value
                                        }
                                    }
                                }
                            },
                        )
                    }
                    .fillMaxWidth()
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
                        Text(actionLabel)
                    }
                }
            } else {
                Text(text, modifier = surfaceModifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = Theme[colors][onPrimaryColor], fontSize = 16.sp, lineHeight = 21.sp)
            }
        }
    }
}

private const val EXIT_DURATION_MS = 180
